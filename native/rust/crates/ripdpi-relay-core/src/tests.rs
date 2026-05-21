use std::io;
use std::time::Duration;

use crate::backend::{build_backend, RelayBackend};
use crate::config::{
    ChainRelayConfig, CloudflareTunnelRelayConfig, CommonRelayConfig, Hysteria2RelayConfig, MasqueRelayConfig,
    NaiveProxyRelayConfig, RelayBackendConfig, RelayKind, ResolvedRelayFinalmaskConfig, ResolvedRelayRuntimeConfig,
    ResolvedShadowTlsInnerRelayConfig, ShadowTlsRelayConfig, TuicRelayConfig, VlessRealityRelayConfig,
};
use crate::runtime::RelayRuntime;
use crate::runtime_validation::{
    describe_upstream, planned_backend_capabilities, pool_config_for_backend, validate_finalmask_config,
    validate_runtime_config,
};

fn sample_config(kind: &str) -> ResolvedRelayRuntimeConfig {
    let common = CommonRelayConfig {
        enabled: true,
        profile_id: "default".to_string(),
        outbound_bind_ip: String::new(),
        server: "relay.example".to_string(),
        server_port: 443,
        server_name: "relay.example".to_string(),
        local_socks_host: "127.0.0.1".to_string(),
        local_socks_port: 10_80,
        udp_enabled: false,
        tcp_fallback_enabled: true,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        tls_fingerprint_profile: "chrome_stable".to_string(),
        finalmask: ResolvedRelayFinalmaskConfig::default(),
    };
    let backend = match kind {
        "hysteria2" => RelayBackendConfig::Hysteria2(Hysteria2RelayConfig {
            password: Some("secret".to_string()),
            salamander_key: None,
        }),
        "tuic_v5" => RelayBackendConfig::TuicV5(TuicRelayConfig {
            uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            password: Some("secret".to_string()),
            zero_rtt: false,
            congestion_control: "bbr".to_string(),
        }),
        "vless_reality" => RelayBackendConfig::VlessReality(VlessRealityRelayConfig {
            reality_public_key: String::new(),
            reality_short_id: String::new(),
            vless_transport: "reality_tcp".to_string(),
            xhttp_path: String::new(),
            xhttp_host: String::new(),
            uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
        }),
        "cloudflare_tunnel" => RelayBackendConfig::CloudflareTunnel(CloudflareTunnelRelayConfig {
            uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            xhttp_path: String::new(),
            xhttp_host: String::new(),
            tunnel_mode: "consume_existing".to_string(),
            publish_local_origin_url: String::new(),
            credentials_ref: String::new(),
            tunnel_token: None,
            tunnel_credentials_json: None,
        }),
        "chain_relay" => RelayBackendConfig::ChainRelay(ChainRelayConfig {
            entry_port: 443,
            exit_port: 443,
            entry_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            exit_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            ..ChainRelayConfig::default()
        }),
        "masque" => RelayBackendConfig::Masque(MasqueRelayConfig {
            url: "https://masque.example/".to_string(),
            use_http2_fallback: true,
            auth_mode: Some("token".to_string()),
            auth_token: Some("token".to_string()),
            ..MasqueRelayConfig::default()
        }),
        "shadowtls_v3" => RelayBackendConfig::ShadowTlsV3(ShadowTlsRelayConfig {
            password: Some("secret".to_string()),
            ..ShadowTlsRelayConfig::default()
        }),
        "naiveproxy" => RelayBackendConfig::NaiveProxy(NaiveProxyRelayConfig::default()),
        other => RelayBackendConfig::Unsupported(crate::config::UnsupportedRelayConfig { kind: other.to_string() }),
    };
    ResolvedRelayRuntimeConfig { common, backend }
}

#[test]
fn relay_runtime_config_round_trips_flattened_backend_fields() {
    for kind in ["hysteria2", "tuic_v5", "vless_reality", "cloudflare_tunnel", "chain_relay", "masque", "shadowtls_v3"]
    {
        let config = sample_config(kind);
        let serialized = serde_json::to_value(&config).expect("serialize relay config");

        assert_eq!(kind, serialized["kind"].as_str().expect("kind field"));
        assert!(serialized.get("localSocksHost").is_some(), "common fields stay flattened");

        let round_trip: ResolvedRelayRuntimeConfig =
            serde_json::from_value(serialized.clone()).expect("deserialize relay config");

        assert_eq!(kind, round_trip.kind_id());
        assert_eq!(serialized, serde_json::to_value(&round_trip).expect("reserialize relay config"));
    }
}

fn hysteria_config_mut(config: &mut ResolvedRelayRuntimeConfig) -> &mut Hysteria2RelayConfig {
    match &mut config.backend {
        RelayBackendConfig::Hysteria2(hysteria) => hysteria,
        _ => panic!("expected Hysteria2 config"),
    }
}

fn tuic_config_mut(config: &mut ResolvedRelayRuntimeConfig) -> &mut TuicRelayConfig {
    match &mut config.backend {
        RelayBackendConfig::TuicV5(tuic) => tuic,
        _ => panic!("expected TUIC config"),
    }
}

fn vless_config_mut(config: &mut ResolvedRelayRuntimeConfig) -> &mut VlessRealityRelayConfig {
    match &mut config.backend {
        RelayBackendConfig::VlessReality(vless) => vless,
        _ => panic!("expected VLESS Reality config"),
    }
}

fn cloudflare_config_mut(config: &mut ResolvedRelayRuntimeConfig) -> &mut CloudflareTunnelRelayConfig {
    match &mut config.backend {
        RelayBackendConfig::CloudflareTunnel(cloudflare) => cloudflare,
        _ => panic!("expected Cloudflare tunnel config"),
    }
}

fn masque_config_mut(config: &mut ResolvedRelayRuntimeConfig) -> &mut MasqueRelayConfig {
    match &mut config.backend {
        RelayBackendConfig::Masque(masque) => masque,
        _ => panic!("expected MASQUE config"),
    }
}

fn shadowtls_config_mut(config: &mut ResolvedRelayRuntimeConfig) -> &mut ShadowTlsRelayConfig {
    match &mut config.backend {
        RelayBackendConfig::ShadowTlsV3(shadowtls) => shadowtls,
        _ => panic!("expected ShadowTLS config"),
    }
}

#[test]
fn relay_runtime_allows_hysteria_udp_and_salamander() {
    let mut config = sample_config("hysteria2");
    config.common.udp_enabled = true;
    hysteria_config_mut(&mut config).salamander_key = Some("salamander".to_string());
    let capabilities = planned_backend_capabilities(&config);
    assert!(capabilities.udp, "Hysteria2 should report UDP capability");
}

#[test]
fn relay_runtime_allows_tuic_udp_and_zero_rtt() {
    let mut config = sample_config("tuic_v5");
    config.common.udp_enabled = true;
    tuic_config_mut(&mut config).zero_rtt = true;

    let capabilities = planned_backend_capabilities(&config);
    assert!(capabilities.tcp, "TUIC should report TCP capability");
    assert!(capabilities.udp, "TUIC should report UDP capability");
    assert_eq!("relay.example:443", describe_upstream(&config));
}

#[tokio::test]
async fn relay_runtime_rejects_udp_without_backend_support() {
    let mut config = sample_config("vless_reality");
    config.common.udp_enabled = true;
    let backend = RelayBackend::Unsupported { kind: "vless_reality".to_string() };

    let error = validate_runtime_config(&config, &backend).expect_err("UDP must fail fast");
    assert_eq!(error.kind(), io::ErrorKind::Unsupported);
}

#[tokio::test]
async fn relay_runtime_allows_masque_udp_and_privacy_pass_provider() {
    let mut config = sample_config("masque");
    config.common.udp_enabled = true;
    let masque = masque_config_mut(&mut config);
    masque.auth_mode = Some("privacy_pass".to_string());
    masque.auth_token = None;
    masque.client_certificate_chain_pem = None;
    masque.client_private_key_pem = None;
    masque.cloudflare_geohash_header = None;
    masque.privacy_pass_provider_url = Some("https://provider.example/token".to_string());

    let capabilities = planned_backend_capabilities(&config);
    assert!(capabilities.udp, "MASQUE should report UDP capability");
    let backend = build_backend(&config).await.expect("masque backend");
    validate_runtime_config(&config, &backend).expect("MASQUE privacy pass should validate");
}

#[test]
fn relay_runtime_preserves_cloudflare_mtls_material() {
    let mut config = sample_config("masque");
    let masque = masque_config_mut(&mut config);
    masque.auth_mode = Some("cloudflare_mtls".to_string());
    masque.auth_token = None;
    masque.client_certificate_chain_pem = Some("cert-chain".to_string());
    masque.client_private_key_pem = Some("private-key".to_string());
    masque.cloudflare_geohash_header = Some("u4pruyd-GB".to_string());

    assert_eq!(masque.auth_mode.as_deref(), Some("cloudflare_mtls"));
    assert_eq!(masque.cloudflare_geohash_header.as_deref(), Some("u4pruyd-GB"));
}

#[test]
fn relay_runtime_rejects_finalmask_for_unsupported_transport() {
    let mut config = sample_config("vless_reality");
    config.common.finalmask = ResolvedRelayFinalmaskConfig {
        r#type: "header_custom".to_string(),
        header_hex: "abcd".to_string(),
        ..ResolvedRelayFinalmaskConfig::default()
    };

    let error = validate_finalmask_config(&config).expect_err("finalmask should be rejected");
    assert_eq!(error.kind(), io::ErrorKind::Unsupported);
}

#[test]
fn relay_runtime_accepts_finalmask_for_xhttp_vless() {
    let mut config = sample_config("vless_reality");
    vless_config_mut(&mut config).vless_transport = "xhttp".to_string();
    config.common.finalmask = ResolvedRelayFinalmaskConfig {
        r#type: "fragment".to_string(),
        fragment_packets: 3,
        fragment_min_bytes: 32,
        fragment_max_bytes: 96,
        ..ResolvedRelayFinalmaskConfig::default()
    };

    validate_finalmask_config(&config).expect("xhttp finalmask should validate");
}

#[test]
fn relay_runtime_accepts_finalmask_for_cloudflare_xhttp() {
    let mut config = sample_config("cloudflare_tunnel");
    config.common.finalmask = ResolvedRelayFinalmaskConfig {
        r#type: "sudoku".to_string(),
        sudoku_seed: "fixture-seed".to_string(),
        ..ResolvedRelayFinalmaskConfig::default()
    };

    validate_finalmask_config(&config).expect("cloudflare xhttp finalmask should validate");
}

#[test]
fn relay_runtime_accepts_noise_for_xhttp_transports() {
    let mut config = sample_config("cloudflare_tunnel");
    config.common.finalmask = ResolvedRelayFinalmaskConfig {
        r#type: "noise".to_string(),
        rand_range: "8-12".to_string(),
        ..ResolvedRelayFinalmaskConfig::default()
    };

    validate_finalmask_config(&config).expect("noise should validate");
}

#[test]
fn relay_telemetry_reports_tls_catalog_version() {
    let runtime = RelayRuntime::new(sample_config("masque"));

    let telemetry = runtime.telemetry();

    assert_eq!(Some("chrome_stable"), telemetry.tls_profile_id.as_deref());
    assert_eq!(Some(ripdpi_xhttp::tls_profile_catalog_version()), telemetry.tls_profile_catalog_version.as_deref());
}

#[test]
fn relay_runtime_routes_vless_xhttp_through_tcp_only_backend() {
    let mut config = sample_config("vless_reality");
    let vless = vless_config_mut(&mut config);
    vless.vless_transport = "xhttp".to_string();
    vless.xhttp_path = "/api/v1/stream".to_string();

    let capabilities = planned_backend_capabilities(&config);
    assert_eq!((true, false), (capabilities.tcp, capabilities.udp));
    assert_eq!("relay.example:443/api/v1/stream", describe_upstream(&config));
}

#[test]
fn relay_runtime_assigns_central_pool_policy_by_backend_family() {
    let hysteria = pool_config_for_backend(&sample_config("hysteria2"));
    let mut xhttp_config = sample_config("vless_reality");
    vless_config_mut(&mut xhttp_config).vless_transport = "xhttp".to_string();
    let xhttp = pool_config_for_backend(&xhttp_config);
    let chain = pool_config_for_backend(&sample_config("chain_relay"));

    assert_eq!(64, hysteria.max_active_leases);
    assert_eq!(Duration::from_secs(45), hysteria.idle_timeout);
    assert_eq!(48, xhttp.max_active_leases);
    assert_eq!(Duration::from_secs(20), xhttp.idle_timeout);
    assert_eq!(16, chain.max_active_leases);
    assert_eq!(Duration::from_secs(5), chain.idle_timeout);
}

#[tokio::test]
async fn relay_runtime_routes_cloudflare_tunnel_through_xhttp_backend() {
    let mut config = sample_config("cloudflare_tunnel");
    config.common.server = "edge.example.com".to_string();
    config.common.server_name = "edge.example.com".to_string();
    cloudflare_config_mut(&mut config).xhttp_path = "/cdn/api".to_string();

    let backend = build_backend(&config).await;
    assert!(backend.is_ok(), "cloudflare tunnel backend should resolve");
    assert_eq!("edge.example.com:443/cdn/api", describe_upstream(&config));
}

#[test]
fn relay_runtime_rejects_invalid_outbound_bind_ip() {
    let mut config = sample_config("vless_reality");
    config.common.outbound_bind_ip = "not-an-ip".to_string();
    let backend = RelayBackend::Unsupported { kind: "vless_reality".to_string() };

    let error = validate_runtime_config(&config, &backend).expect_err("invalid bind ip must fail");
    assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
}

#[test]
fn relay_runtime_rejects_bind_ip_for_unsupported_backend() {
    let mut config = sample_config("hysteria2");
    config.common.outbound_bind_ip = "203.0.113.10".to_string();
    let backend = RelayBackend::Unsupported { kind: "hysteria2".to_string() };

    let error = validate_runtime_config(&config, &backend).expect_err("unsupported bind ip must fail");
    assert_eq!(error.kind(), io::ErrorKind::Unsupported);
}

#[tokio::test]
async fn relay_runtime_builds_shadowtls_backend_with_inner_vless_profile() {
    let mut config = sample_config("shadowtls_v3");
    let shadowtls = shadowtls_config_mut(&mut config);
    shadowtls.inner_profile_id = "inner-vless".to_string();
    shadowtls.inner = Some(ResolvedShadowTlsInnerRelayConfig {
        kind: "vless_reality".to_string(),
        profile_id: "inner-vless".to_string(),
        server: "inner.example".to_string(),
        server_port: 443,
        server_name: "inner.example".to_string(),
        reality_public_key: "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=".to_string(),
        reality_short_id: String::new(),
        vless_transport: "reality_tcp".to_string(),
        vless_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
    });

    let backend = build_backend(&config).await.expect("shadowtls backend");
    match backend {
        RelayBackend::ShadowTls(_) => {}
        other => panic!("expected ShadowTLS backend, got {:?}", std::mem::discriminant(&other)),
    }
}

/// Asserts whether `validate_runtime_config` accepts an outbound bind IP for a
/// relay kind, exercising the descriptor-driven capability gate end to end.
fn assert_outbound_bind_ip_support(kind_id: &str, base: &ResolvedRelayRuntimeConfig, supported: bool) {
    let mut config = base.clone();
    config.common.outbound_bind_ip = "203.0.113.10".to_string();
    let backend = RelayBackend::Unsupported { kind: kind_id.to_string() };

    let result = validate_runtime_config(&config, &backend);
    if supported {
        result.unwrap_or_else(|error| panic!("{kind_id} should accept an outbound bind IP: {error}"));
    } else {
        let error = result.expect_err("outbound bind IP must be rejected");
        assert_eq!(error.kind(), io::ErrorKind::Unsupported, "{kind_id} bind-IP rejection error kind");
    }
}

/// Pins the planned SOCKS capability profile and outbound-bind-IP support for
/// every `RelayKind`. `planned_backend_capabilities` and the bind-IP gate now
/// resolve through `RELAY_TRANSPORT_DESCRIPTORS`, so a wrong descriptor row is
/// caught here against literal expectations.
#[test]
fn relay_planned_capabilities_are_pinned_for_every_kind() {
    // kind_id, tcp, udp, reusable, supports_outbound_bind_ip
    let pinned: [(&str, bool, bool, bool, bool); 8] = [
        ("hysteria2", true, true, true, false),
        ("tuic_v5", true, true, true, true),
        ("vless_reality", true, false, false, true),
        ("cloudflare_tunnel", true, false, true, true),
        ("chain_relay", true, false, false, true),
        ("masque", true, true, true, false),
        ("shadowtls_v3", true, false, false, true),
        ("naiveproxy", true, false, false, true),
    ];

    for (kind_id, tcp, udp, reusable, bind_ip) in pinned {
        let config = sample_config(kind_id);
        let capabilities = planned_backend_capabilities(&config);
        assert_eq!(
            (tcp, udp, reusable),
            (capabilities.tcp, capabilities.udp, capabilities.reusable),
            "planned capabilities drifted for {kind_id}",
        );
        assert_outbound_bind_ip_support(kind_id, &config, bind_ip);
    }

    // VLESS Reality's `xhttp` sub-mode shares the single `vless_reality`
    // descriptor: its capability profile is identical to `reality_tcp`.
    let mut vless_xhttp = sample_config("vless_reality");
    vless_config_mut(&mut vless_xhttp).vless_transport = "xhttp".to_string();
    let xhttp = planned_backend_capabilities(&vless_xhttp);
    assert_eq!(
        (true, false, false),
        (xhttp.tcp, xhttp.udp, xhttp.reusable),
        "VLESS xhttp sub-mode must share the vless_reality capability profile",
    );

    // The `Unsupported` catch-all has no descriptor: it reports the empty
    // capability profile and keeps the permissive outbound-bind-IP default.
    let unsupported = sample_config("totally_unknown");
    let caps = planned_backend_capabilities(&unsupported);
    assert_eq!(
        (false, false, false),
        (caps.tcp, caps.udp, caps.reusable),
        "Unsupported relay kind must report no capabilities",
    );
    assert_outbound_bind_ip_support("totally_unknown", &unsupported, true);
}

/// Drift guard: every `RelayKind` maps to exactly one transport descriptor and
/// every descriptor `kind_id` maps back to a supported (non-`Unsupported`)
/// `RelayKind`. The `descriptor_kind_id` match is exhaustive, so a new
/// `RelayKind` variant fails to compile here until the table is extended.
#[test]
fn relay_transport_descriptors_cover_every_kind_exactly_once() {
    use crate::transport_descriptor::{relay_transport_descriptor, RELAY_TRANSPORT_DESCRIPTORS};

    fn descriptor_kind_id(kind: RelayKind<'_>) -> Option<&'static str> {
        match kind {
            RelayKind::Hysteria2 => Some("hysteria2"),
            RelayKind::TuicV5 => Some("tuic_v5"),
            RelayKind::VlessReality { .. } => Some("vless_reality"),
            RelayKind::CloudflareTunnel => Some("cloudflare_tunnel"),
            RelayKind::ChainRelay => Some("chain_relay"),
            RelayKind::Masque => Some("masque"),
            RelayKind::ShadowTlsV3 => Some("shadowtls_v3"),
            RelayKind::NaiveProxy => Some("naiveproxy"),
            RelayKind::Unsupported(_) => None,
        }
    }

    // One config per concrete RelayKind, both VLESS sub-modes, and an
    // unsupported kind. `from_config` drives the classification.
    let mut vless_xhttp = sample_config("vless_reality");
    vless_config_mut(&mut vless_xhttp).vless_transport = "xhttp".to_string();
    let configs = [
        sample_config("hysteria2"),
        sample_config("tuic_v5"),
        sample_config("vless_reality"),
        vless_xhttp,
        sample_config("cloudflare_tunnel"),
        sample_config("chain_relay"),
        sample_config("masque"),
        sample_config("shadowtls_v3"),
        sample_config("naiveproxy"),
        sample_config("totally_unknown"),
    ];

    // Forward: every concrete kind resolves to exactly one descriptor row;
    // the Unsupported catch-all resolves to none.
    let mut covered = std::collections::BTreeSet::new();
    for config in &configs {
        match descriptor_kind_id(RelayKind::from_config(config)) {
            Some(kind_id) => {
                assert_eq!(kind_id, config.kind_id(), "RelayKind / config kind_id disagree");
                let rows = RELAY_TRANSPORT_DESCRIPTORS.iter().filter(|d| d.kind_id == kind_id).count();
                assert_eq!(1, rows, "{kind_id} must have exactly one descriptor row");
                assert!(relay_transport_descriptor(kind_id).is_some(), "{kind_id} must resolve to a descriptor");
                covered.insert(kind_id);
            }
            None => assert!(
                relay_transport_descriptor(config.kind_id()).is_none(),
                "Unsupported relay kind {} must not resolve to a descriptor",
                config.kind_id(),
            ),
        }
    }

    // Reverse: every descriptor row is reachable from a supported RelayKind,
    // and the table holds exactly the concrete kinds -- no orphan rows.
    for descriptor in RELAY_TRANSPORT_DESCRIPTORS {
        assert!(descriptor.tcp, "{} descriptor: every relay transport relays TCP", descriptor.kind_id);
        assert!(!descriptor.label.is_empty(), "{} descriptor needs a label", descriptor.kind_id);
        assert_eq!(
            Some(descriptor),
            relay_transport_descriptor(descriptor.kind_id),
            "{} descriptor lookup must round-trip",
            descriptor.kind_id,
        );
        assert!(
            !matches!(RelayKind::from_config(&sample_config(descriptor.kind_id)), RelayKind::Unsupported(_)),
            "descriptor kind {} must map to a supported RelayKind",
            descriptor.kind_id,
        );
        assert!(covered.contains(descriptor.kind_id), "descriptor kind {} is unreachable", descriptor.kind_id);
    }
    assert_eq!(
        RELAY_TRANSPORT_DESCRIPTORS.len(),
        covered.len(),
        "descriptor count must equal the number of concrete relay kinds",
    );

    assert!(relay_transport_descriptor("off").is_none(), "\"off\" is not a relay transport");
    assert!(relay_transport_descriptor("totally_unknown").is_none(), "unknown kinds have no descriptor");
}
