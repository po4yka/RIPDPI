use std::io;
use std::time::Duration;

use ripdpi_tls_profiles::profile_catalog_version;

use crate::backend::{build_backend, RelayBackend};
use crate::config::{
    ChainRelayConfig, CloudflareTunnelRelayConfig, CommonRelayConfig, Hysteria2RelayConfig, MasqueRelayConfig,
    RelayBackendConfig, ResolvedRelayFinalmaskConfig, ResolvedRelayRuntimeConfig, ResolvedShadowTlsInnerRelayConfig,
    ShadowTlsRelayConfig, TuicRelayConfig, VlessRealityRelayConfig,
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
        other => RelayBackendConfig::Unsupported(crate::config::UnsupportedRelayConfig { kind: other.to_string() }),
    };
    ResolvedRelayRuntimeConfig { common, backend }
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
    assert_eq!(Some(profile_catalog_version()), telemetry.tls_profile_catalog_version.as_deref());
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
