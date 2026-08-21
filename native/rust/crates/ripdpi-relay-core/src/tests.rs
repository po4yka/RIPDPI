use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::Duration;

use android_support::EventRingLayer;
use local_network_fixture::{
    AnyTlsLoopback, AnyTlsLoopbackConfig, ShadowsocksLoopback, TrojanLoopback, VlessRealityLoopback,
    XhttpRealityLoopback,
};
use ripdpi_failure_classifier::FailureClass;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tracing_subscriber::prelude::*;

mod backend_fixture_tests;
mod cross_stack;
mod relay_attempt_trace;
mod shadowtls_version;
mod shutdown_drain;
mod shutdown_leak;
mod tls_in_tls_exposure;
mod transport_registry;

use crate::backend::RelayBackend;
use crate::backend::builder::build_backend;
use crate::bootstrap::bootstrap_relay_endpoints;
use crate::config::{
    AnyTlsRelayConfig, ChainRelayConfig, CloudflareTunnelRelayConfig, CommonRelayConfig, Hysteria2RelayConfig,
    MasqueRelayConfig, MieruRelayConfig, NaiveProxyRelayConfig, RelayBackendConfig, ResolvedChainRelayHopConfig,
    ResolvedRelayFinalmaskConfig, ResolvedRelayRuntimeConfig, ResolvedShadowTlsInnerRelayConfig, ShadowTlsRelayConfig,
    ShadowsocksRelayConfig, SshRelayConfig, TorPluggableTransportConfig, TorRelayConfig, TrojanRelayConfig,
    TuicRelayConfig, VlessRealityRelayConfig, VlessRelayConfig,
};
use crate::runtime::RelayRuntime;
use crate::runtime_validation::{
    describe_upstream, planned_backend_capabilities, pool_config_for_backend, validate_finalmask_config,
    validate_runtime_config,
};
use crate::socks::RelayTargetAddr;
use backend_fixture_tests::relay_backend_kind_id;

fn sample_config(kind: &str) -> ResolvedRelayRuntimeConfig {
    let common = CommonRelayConfig {
        enabled: true,
        profile_id: "default".to_string(),
        outbound_bind_ip: String::new(),
        socket_protection: crate::config::SocketProtection::Inactive,
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
            insecure: false,
        }),
        "tuic_v5" => RelayBackendConfig::TuicV5(TuicRelayConfig {
            uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            password: Some("secret".to_string()),
            zero_rtt: false,
            congestion_control: "bbr".to_string(),
        }),
        "vless" => RelayBackendConfig::Vless(VlessRelayConfig {
            vless_flow: "xtls-rprx-vision".to_string(),
            vless_transport: "xhttp".to_string(),
            xhttp_path: "/xhttp".to_string(),
            xhttp_host: "relay.example".to_string(),
            xhttp_mode: "auto".to_string(),
            uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
        }),
        "vless_reality" => RelayBackendConfig::VlessReality(VlessRealityRelayConfig {
            reality_public_key: String::new(),
            reality_short_id: String::new(),
            vless_flow: "xtls-rprx-vision".to_string(),
            vless_transport: "reality_tcp".to_string(),
            xhttp_path: String::new(),
            xhttp_host: String::new(),
            xhttp_mode: "auto".to_string(),
            vless_mux_protocol: String::new(),
            vless_mux_max_concurrent_streams: 0,
            vless_mux_per_connection_kbps: 0,
            vless_mux_padding_max: 0,
            uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
        }),
        "mieru" => RelayBackendConfig::Mieru(MieruRelayConfig {
            server: "relay.example".to_string(),
            port: 443,
            username: Some("alice".to_string()),
            password: Some("secret".to_string()),
            protocol: "tcp".to_string(),
            multiplexing: "middle".to_string(),
            mtu: 1400,
        }),
        "ssh" => RelayBackendConfig::Ssh(SshRelayConfig {
            host: "relay.example".to_string(),
            port: 22,
            username: Some("alice".to_string()),
            auth_type: "password".to_string(),
            password: Some("secret".to_string()),
            private_key: None,
            private_key_passphrase: None,
            host_key_fingerprint: None,
            strict_host_key: false,
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
        "trojan" => RelayBackendConfig::Trojan(TrojanRelayConfig {
            password: Some("secret".to_string()),
            root_certificate_pem: None,
        }),
        "anytls" => RelayBackendConfig::AnyTls(AnyTlsRelayConfig {
            password: Some("secret".to_string()),
            root_certificate_pem: None,
        }),
        "shadowsocks" => RelayBackendConfig::Shadowsocks(ShadowsocksRelayConfig {
            method: "aes-256-gcm".to_string(),
            password: Some("secret".to_string()),
        }),
        "tor" => RelayBackendConfig::Tor(TorRelayConfig {
            state_dir: std::env::temp_dir().join("ripdpi-relay-core-tor-state").to_string_lossy().into_owned(),
            cache_dir: std::env::temp_dir().join("ripdpi-relay-core-tor-cache").to_string_lossy().into_owned(),
            bridge_lines: vec!["Bridge obfs4 192.0.2.55:38114 316E643333645F6D79216558614D3931657A5F5F cert=YXJlIGZyZXF1ZW50bHkgZnVsbCBvZiBsaXR0bGUgbWVzc2FnZXMgeW91IGNhbiBmaW5kLg iat-mode=0".to_string()],
            transports: vec![TorPluggableTransportConfig {
                protocols: vec!["obfs4".to_string()],
                binary_path: "/usr/local/bin/ripdpi-obfs4".to_string(),
                arguments: Vec::new(),
                run_on_startup: false,
            }],
        }),
        "naiveproxy" => RelayBackendConfig::NaiveProxy(NaiveProxyRelayConfig::default()),
        other => RelayBackendConfig::Unsupported(crate::config::UnsupportedRelayConfig { kind: other.to_string() }),
    };
    ResolvedRelayRuntimeConfig { common, backend }
}

#[test]
fn socket_protection_wire_defaults_inactive_and_accepts_vpn_required() {
    let proxy = sample_config("vless_reality");
    let proxy_json = serde_json::to_value(&proxy).expect("serialize proxy relay config");
    assert_eq!(proxy_json["socketProtection"], serde_json::json!("inactive"));

    let mut vpn_json = proxy_json;
    vpn_json["socketProtection"] = serde_json::json!("vpn_required");
    let vpn: ResolvedRelayRuntimeConfig = serde_json::from_value(vpn_json).expect("deserialize VPN relay config");
    assert_eq!(vpn.common.socket_protection, crate::config::SocketProtection::VpnRequired);

    let mut legacy_json = serde_json::to_value(proxy).expect("serialize legacy relay config");
    legacy_json.as_object_mut().expect("object").remove("socketProtection");
    let legacy: ResolvedRelayRuntimeConfig =
        serde_json::from_value(legacy_json).expect("deserialize legacy relay config");
    assert_eq!(legacy.common.socket_protection, crate::config::SocketProtection::Inactive);
}

#[tokio::test]
async fn tor_vpn_mode_fails_before_arti_can_open_unprotected_sockets() {
    let mut config = sample_config("tor");
    config.common.socket_protection = crate::config::SocketProtection::VpnRequired;

    let Err(error) = build_backend(&config).await else {
        panic!("Tor VPN mode must fail closed");
    };
    assert_eq!(error.kind(), io::ErrorKind::Unsupported);
}

#[tokio::test]
async fn relay_endpoint_bootstrap_preserves_common_hostname_and_sni_without_direct_dns() {
    let config = sample_config("vless_reality");

    let bootstrapped = bootstrap_relay_endpoints(&config).await.expect("bootstrap endpoints");

    assert_eq!(bootstrapped.common.server, "relay.example");
    assert_eq!(bootstrapped.common.server_name, "relay.example");
}

#[tokio::test]
async fn relay_endpoint_bootstrap_emits_no_direct_lookup_or_endpoint_event() {
    let buffers = android_support::EventRingBuffers::default();
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
    let dispatch = tracing::Dispatch::new(subscriber);
    let _guard = tracing::dispatcher::set_default(&dispatch);
    let config = sample_config("vless_reality");

    let _bootstrapped = bootstrap_relay_endpoints(&config).await.expect("bootstrap endpoints");

    let events = buffers.drain_relay();
    assert!(
        events.iter().all(|event| event.kind.as_deref() != Some("relay_endpoint_bootstrap_direct_lookup")),
        "bootstrap must not publish direct DNS lookup telemetry",
    );
    let serialized = format!("{events:?}");
    assert!(!serialized.contains("relay.example"), "bootstrap telemetry leaked relay host: {serialized}");
    assert!(!serialized.contains("443"), "bootstrap telemetry leaked relay port: {serialized}");
}

#[tokio::test]
async fn relay_endpoint_bootstrap_skips_ip_literals() {
    let mut config = sample_config("trojan");
    config.common.server = "198.51.100.8".to_string();

    let bootstrapped = bootstrap_relay_endpoints(&config).await.expect("bootstrap endpoints");

    assert_eq!(bootstrapped.common.server, "198.51.100.8");
    assert_eq!(bootstrapped.common.server_name, "relay.example");
}

#[tokio::test]
async fn relay_endpoint_bootstrap_preserves_chain_entry_and_exit_hostnames() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry_server = "entry.example".to_string();
    chain.entry_server_name = "entry.example".to_string();
    chain.exit_server = "exit.example".to_string();
    chain.exit_server_name = "exit.example".to_string();

    let bootstrapped = bootstrap_relay_endpoints(&config).await.expect("bootstrap endpoints");

    let RelayBackendConfig::ChainRelay(chain) = bootstrapped.backend else {
        panic!("expected chain config");
    };
    assert_eq!(chain.entry_server, "entry.example");
    assert_eq!(chain.entry_server_name, "entry.example");
    assert_eq!(chain.exit_server, "exit.example");
    assert_eq!(chain.exit_server_name, "exit.example");
}

#[tokio::test]
async fn relay_endpoint_bootstrap_preserves_resolved_chain_entry_and_exit_hostnames() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry_server = "stale-entry.example".to_string();
    chain.entry_port = 443;
    chain.exit_server = "stale-exit.example".to_string();
    chain.exit_port = 443;
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "vless_reality".to_string(),
        profile_id: "entry".to_string(),
        server: "entry.example".to_string(),
        server_port: 443,
        server_name: "entry.example".to_string(),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "vless_reality".to_string(),
        profile_id: "exit".to_string(),
        server: "exit.example".to_string(),
        server_port: 443,
        server_name: "exit.example".to_string(),
        ..ResolvedChainRelayHopConfig::default()
    }));

    let bootstrapped = bootstrap_relay_endpoints(&config).await.expect("bootstrap endpoints");

    let RelayBackendConfig::ChainRelay(chain) = bootstrapped.backend else {
        panic!("expected chain config");
    };
    let entry = chain.entry.expect("resolved entry");
    let exit = chain.exit.expect("resolved exit");
    assert_eq!(entry.server, "entry.example");
    assert_eq!(entry.server_name, "entry.example");
    assert_eq!(exit.server, "exit.example");
    assert_eq!(exit.server_name, "exit.example");
}

#[tokio::test]
async fn relay_endpoint_bootstrap_preserves_shadowtls_outer_and_inner_hostnames() {
    let mut config = sample_config("shadowtls_v3");
    config.common.server = "outer.example".to_string();
    config.common.server_name = "outer.example".to_string();
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
        vless_flow: "xtls-rprx-vision".to_string(),
        vless_transport: "reality_tcp".to_string(),
        xhttp_mode: "auto".to_string(),
        vless_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
        tls_fingerprint_profile: "firefox_stable".to_string(),
    });

    let bootstrapped = bootstrap_relay_endpoints(&config).await.expect("bootstrap endpoints");

    assert_eq!(bootstrapped.common.server, "outer.example");
    assert_eq!(bootstrapped.common.server_name, "outer.example");
    let RelayBackendConfig::ShadowTlsV3(shadowtls) = bootstrapped.backend else {
        panic!("expected shadowtls config");
    };
    let inner = shadowtls.inner.expect("inner relay config");
    assert_eq!(inner.server, "inner.example");
    assert_eq!(inner.server_name, "inner.example");
}

#[tokio::test]
async fn relay_endpoint_bootstrap_preserves_masque_url_without_proxy_socket_resolution() {
    let mut config = sample_config("masque");
    config.common.server = "unused-common.example".to_string();
    let RelayBackendConfig::Masque(masque) = &mut config.backend else {
        panic!("expected MASQUE config");
    };
    masque.url = "https://masque.example:8443/.well-known/masque/ip".to_string();

    let bootstrapped = bootstrap_relay_endpoints(&config).await.expect("bootstrap endpoints");

    let RelayBackendConfig::Masque(masque) = bootstrapped.backend else {
        panic!("expected MASQUE config");
    };
    assert_eq!(masque.url, "https://masque.example:8443/.well-known/masque/ip");
    assert_eq!(masque.proxy_socket_addr, None);
}

#[test]
fn relay_runtime_config_round_trips_flattened_backend_fields() {
    for kind in [
        "hysteria2",
        "tuic_v5",
        "vless",
        "vless_reality",
        "mieru",
        "ssh",
        "cloudflare_tunnel",
        "chain_relay",
        "masque",
        "shadowtls_v3",
        "trojan",
        "anytls",
        "shadowsocks",
    ] {
        let mut config = sample_config(kind);
        if let RelayBackendConfig::ChainRelay(chain) = &mut config.backend {
            chain.entry = Some(Box::new(vless_hop("entry", 443, "entry.example")));
            chain.exit = Some(Box::new(vless_hop("exit", 443, "exit.example")));
        }
        let serialized = serde_json::to_value(&config).expect("serialize relay config");

        assert_eq!(kind, serialized["kind"].as_str().expect("kind field"));
        assert!(serialized.get("localSocksHost").is_some(), "common fields stay flattened");

        let round_trip: ResolvedRelayRuntimeConfig =
            serde_json::from_value(serialized.clone()).expect("deserialize relay config");

        assert_eq!(kind, round_trip.kind_id());
        assert_eq!(serialized, serde_json::to_value(&round_trip).expect("reserialize relay config"));
    }
}

#[test]
fn relay_runtime_config_rejects_unknown_wire_fields() {
    let mut serialized = serde_json::to_value(sample_config("trojan")).expect("serialize relay config");
    serialized["sentinelUnknownField"] = serde_json::json!(true);

    let error = serde_json::from_value::<ResolvedRelayRuntimeConfig>(serialized)
        .expect_err("unknown wire fields must fail closed instead of silently defaulting");
    assert!(error.to_string().contains("sentinelUnknownField"), "the error must name the unknown field, got: {error}");
}

#[test]
fn masque_tcp_protocol_round_trips_independently_from_udp_fallback() {
    let mut config = sample_config("masque");
    let masque = masque_config_mut(&mut config);
    masque.tcp_protocol = "http3".to_string();
    masque.use_http2_fallback = false;

    let serialized = serde_json::to_value(&config).expect("serialize MASQUE config");
    assert_eq!(serde_json::json!("http3"), serialized["masqueTcpProtocol"]);
    assert_eq!(serde_json::json!(false), serialized["masqueUseHttp2Fallback"]);

    let round_trip: ResolvedRelayRuntimeConfig = serde_json::from_value(serialized).expect("deserialize MASQUE config");
    let RelayBackendConfig::Masque(masque) = round_trip.backend else {
        panic!("expected MASQUE config");
    };
    assert_eq!("http3", masque.tcp_protocol);
    assert!(!masque.use_http2_fallback);
}

#[test]
fn vless_flow_round_trips_through_flat_native_config() {
    let mut config = sample_config("vless_reality");
    vless_config_mut(&mut config).vless_flow = "xtls-rprx-vision-udp443".to_string();

    let serialized = serde_json::to_value(&config).expect("serialize VLESS config");
    assert_eq!(
        serde_json::json!("xtls-rprx-vision-udp443"),
        serialized["vlessFlow"],
        "flat native config must carry imported VLESS flow",
    );

    let mut round_trip: ResolvedRelayRuntimeConfig =
        serde_json::from_value(serialized).expect("deserialize VLESS config");
    assert_eq!("xtls-rprx-vision-udp443", vless_config_mut(&mut round_trip).vless_flow,);
}

#[test]
fn vless_flow_defaults_only_when_flat_native_config_omits_field() {
    let mut config = sample_config("vless_reality");
    vless_config_mut(&mut config).vless_flow = String::new();
    let explicit_empty = serde_json::to_value(&config).expect("serialize VLESS config");
    let mut explicit_round_trip: ResolvedRelayRuntimeConfig =
        serde_json::from_value(explicit_empty).expect("deserialize explicit empty flow");
    assert_eq!("", vless_config_mut(&mut explicit_round_trip).vless_flow);

    let mut missing_field = serde_json::to_value(&config).expect("serialize VLESS config");
    missing_field.as_object_mut().expect("flat relay config object").remove("vlessFlow");
    let mut legacy_round_trip: ResolvedRelayRuntimeConfig =
        serde_json::from_value(missing_field).expect("deserialize legacy missing flow");
    assert_eq!("xtls-rprx-vision", vless_config_mut(&mut legacy_round_trip).vless_flow);
}

#[test]
fn vless_xhttp_mode_round_trips_through_flat_native_config() {
    let mut config = sample_config("vless_reality");
    let vless = vless_config_mut(&mut config);
    vless.vless_transport = "xhttp".to_string();
    vless.xhttp_mode = "stream-one".to_string();

    let serialized = serde_json::to_value(&config).expect("serialize VLESS config");
    assert_eq!(
        serde_json::json!("stream-one"),
        serialized["xhttpMode"],
        "flat native config must carry imported xHTTP mode",
    );

    let mut round_trip: ResolvedRelayRuntimeConfig =
        serde_json::from_value(serialized).expect("deserialize VLESS config");
    assert_eq!("stream-one", vless_config_mut(&mut round_trip).xhttp_mode);
}

#[test]
fn vless_mux_round_trips_through_flat_native_config_without_xhttp_fallback() {
    let mut config = sample_config("vless_reality");
    let vless = vless_config_mut(&mut config);
    vless.vless_mux_protocol = "yamux".to_string();
    vless.vless_mux_max_concurrent_streams = 3;

    let serialized = serde_json::to_value(&config).expect("serialize VLESS mux config");
    assert_eq!(serde_json::json!("yamux"), serialized["vlessMuxProtocol"]);
    assert_eq!(serde_json::json!(3), serialized["vlessMuxMaxConcurrentStreams"]);

    let mut round_trip: ResolvedRelayRuntimeConfig =
        serde_json::from_value(serialized).expect("deserialize VLESS mux config");
    let vless = vless_config_mut(&mut round_trip);
    assert_eq!("yamux", vless.vless_mux_protocol);
    assert_eq!(3, vless.vless_mux_max_concurrent_streams);
    assert_eq!("reality_tcp", vless.vless_transport, "mux must not coerce the transport to xHTTP");
}

#[tokio::test]
async fn vless_reality_mux_interleaves_three_streams_over_one_carrier() {
    let fixture = VlessRealityLoopback::start().await.expect("start VLESS Reality mux fixture");
    let mut config = sample_config("vless_reality");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    let vless = vless_config_mut(&mut config);
    vless.reality_public_key = valid_reality_public_key();
    vless.vless_flow = "none".to_string();
    vless.vless_mux_protocol = "yamux".to_string();
    vless.vless_mux_max_concurrent_streams = 3;

    let backend = build_backend(&config).await.expect("build VLESS Reality mux backend");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.target_port()));
    let (first, second, third) =
        tokio::join!(backend.connect_tcp(&target), backend.connect_tcp(&target), backend.connect_tcp(&target));
    let (mut first, mut second, mut third) = (
        first.expect("open first mux stream"),
        second.expect("open second mux stream"),
        third.expect("open third mux stream"),
    );

    let (first_write, second_write, third_write) =
        tokio::join!(first.write_all(b"one"), second.write_all(b"two"), third.write_all(b"tri"),);
    first_write.expect("write first");
    second_write.expect("write second");
    third_write.expect("write third");

    let mut first_reply = [0u8; 3];
    let mut second_reply = [0u8; 3];
    let mut third_reply = [0u8; 3];
    let (first_read, second_read, third_read) = tokio::join!(
        first.read_exact(&mut first_reply),
        second.read_exact(&mut second_reply),
        third.read_exact(&mut third_reply),
    );
    first_read.expect("read first");
    second_read.expect("read second");
    third_read.expect("read third");
    assert_eq!(first_reply, *b"one");
    assert_eq!(second_reply, *b"two");
    assert_eq!(third_reply, *b"tri");
    assert_eq!(fixture.observed_target(), Some(ripdpi_vless::mux::SING_MUX_DESTINATION.to_string()));
}

#[test]
fn nested_vless_identity_fields_round_trip_without_coercion() {
    let inner: ResolvedShadowTlsInnerRelayConfig = serde_json::from_value(serde_json::json!({
        "kind": "vless_reality",
        "profileId": "inner",
        "server": "inner.example",
        "serverPort": 443,
        "serverName": "inner.example",
        "realityPublicKey": "public",
        "realityShortId": "short",
        "vlessTransport": "reality_tcp",
        "vlessFlow": "xtls-rprx-vision-udp443",
        "xhttpMode": "stream-one",
        "vlessUuid": "11111111-1111-1111-1111-111111111111",
        "tlsFingerprintProfile": "firefox_stable"
    }))
    .expect("deserialize Kotlin ShadowTLS inner wire config");
    assert_eq!("xtls-rprx-vision-udp443", inner.vless_flow);
    assert_eq!("stream-one", inner.xhttp_mode);
    assert_eq!("firefox_stable", inner.tls_fingerprint_profile);

    let hop: ResolvedChainRelayHopConfig = serde_json::from_value(serde_json::json!({
        "kind": "vless_reality",
        "profileId": "hop",
        "vlessFlow": "none",
        "tlsFingerprintProfile": "firefox_stable"
    }))
    .expect("deserialize Kotlin chain hop wire config");
    assert_eq!("none", hop.vless_flow);
    assert_eq!(serde_json::json!("none"), serde_json::to_value(hop).expect("serialize hop")["vlessFlow"]);
}

#[test]
fn chain_relay_heterogeneous_hop_config_round_trips() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "vless_reality".to_string(),
        profile_id: "entry-hop".to_string(),
        server: "entry.example".to_string(),
        server_port: 443,
        server_name: "entry-sni.example".to_string(),
        reality_public_key: "entry-public".to_string(),
        reality_short_id: "entry-short".to_string(),
        vless_uuid: Some("11111111-1111-1111-1111-111111111111".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "masque".to_string(),
        profile_id: "masque-exit".to_string(),
        masque_url: "https://masque.example/.well-known/masque/tcp/".to_string(),
        masque_use_http2_fallback: true,
        masque_auth_mode: Some("bearer".to_string()),
        masque_auth_token: Some("relay-fixture-placeholder".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));

    let serialized = serde_json::to_value(&config).expect("serialize chain config");
    assert_eq!(serialized["chainEntry"]["kind"], serde_json::json!("vless_reality"));
    assert_eq!(serialized["chainExit"]["kind"], serde_json::json!("masque"));
    assert_eq!(
        serialized["chainExit"]["masqueUrl"],
        serde_json::json!("https://masque.example/.well-known/masque/tcp/"),
    );

    let round_trip: ResolvedRelayRuntimeConfig =
        serde_json::from_value(serialized.clone()).expect("deserialize chain config");
    assert_eq!(serialized, serde_json::to_value(&round_trip).expect("reserialize chain config"));
}

#[test]
fn chain_relay_hop_config_uses_kotlin_wire_defaults() {
    let err = serde_json::from_value::<ResolvedChainRelayHopConfig>(serde_json::json!({
        "kind": "masque",
        "profileId": "masque-exit"
    }))
    .expect_err("chain hop without tlsFingerprintProfile should be rejected");

    assert!(err.to_string().contains("tlsFingerprintProfile"));

    let hop: ResolvedChainRelayHopConfig = serde_json::from_value(serde_json::json!({
        "kind": "masque",
        "profileId": "masque-exit",
        "tlsFingerprintProfile": "firefox_stable"
    }))
    .expect("deserialize explicit Kotlin-style chain hop config");

    assert_eq!(443, hop.server_port);
    assert_eq!("reality_tcp", hop.vless_transport);
    assert_eq!("consume_existing", hop.cloudflare_tunnel_mode);
    assert!(hop.masque_use_http2_fallback);
    assert_eq!("bbr", hop.tuic_congestion_control);
    assert_eq!("firefox_stable", hop.tls_fingerprint_profile);
    assert_eq!("off", hop.finalmask.r#type);
}

#[tokio::test]
async fn chain_relay_builds_vless_entry_masque_exit_from_resolved_hops() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "vless_reality".to_string(),
        profile_id: "entry-hop".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 443,
        server_name: "entry.example".to_string(),
        reality_public_key: valid_reality_public_key(),
        reality_short_id: String::new(),
        vless_flow: "none".to_string(),
        vless_uuid: Some("11111111-1111-1111-1111-111111111111".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "masque".to_string(),
        profile_id: "masque-exit".to_string(),
        masque_url: "https://masque.example/.well-known/masque/tcp/".to_string(),
        masque_use_http2_fallback: true,
        masque_auth_mode: Some("bearer".to_string()),
        masque_auth_token: Some("relay-fixture-placeholder".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));

    let backend = build_backend(&config).await.expect("chain backend builds mixed VLESS entry and MASQUE exit");

    assert_eq!(Some("chain_relay"), relay_backend_kind_id(&backend));
}

#[tokio::test]
async fn chain_relay_builds_masque_entry_vless_exit_from_resolved_hops() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "masque".to_string(),
        profile_id: "masque-entry".to_string(),
        masque_url: "https://masque.example/.well-known/masque/tcp/".to_string(),
        masque_use_http2_fallback: true,
        masque_auth_mode: Some("bearer".to_string()),
        masque_auth_token: Some("relay-fixture-placeholder".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "vless_reality".to_string(),
        profile_id: "exit-hop".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 443,
        server_name: "exit.example".to_string(),
        reality_public_key: valid_reality_public_key(),
        reality_short_id: String::new(),
        vless_uuid: Some("22222222-2222-2222-2222-222222222222".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));

    let backend = build_backend(&config).await.expect("chain backend builds mixed MASQUE entry and VLESS exit");

    assert_eq!(Some("chain_relay"), relay_backend_kind_id(&backend));
}

#[tokio::test]
async fn chain_relay_builds_trojan_entry_trojan_exit_from_resolved_hops() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "trojan".to_string(),
        profile_id: "trojan-entry".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 443,
        server_name: "entry.example".to_string(),
        trojan_password: Some("entry-secret".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "trojan".to_string(),
        profile_id: "trojan-exit".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 8443,
        server_name: "exit.example".to_string(),
        trojan_password: Some("exit-secret".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));

    let backend = build_backend(&config).await.expect("chain backend builds Trojan entry and Trojan exit");

    assert_eq!(Some("chain_relay"), relay_backend_kind_id(&backend));
}

#[tokio::test]
async fn chain_relay_routes_tcp_through_shadowsocks_entry_and_exit() {
    const PAYLOAD: &[u8] = b"chain shadowsocks entry exit payload";

    let entry =
        ShadowsocksLoopback::start("aes-256-gcm", "entry-secret").await.expect("start entry shadowsocks fixture");
    let exit = ShadowsocksLoopback::start("aes-256-gcm", "exit-secret").await.expect("start exit shadowsocks fixture");
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "shadowsocks".to_string(),
        profile_id: "shadowsocks-entry".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: i32::from(entry.port()),
        shadowsocks_method: Some("aes-256-gcm".to_string()),
        shadowsocks_password: Some("entry-secret".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "shadowsocks".to_string(),
        profile_id: "shadowsocks-exit".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: i32::from(exit.port()),
        shadowsocks_method: Some("aes-256-gcm".to_string()),
        shadowsocks_password: Some("exit-secret".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));

    let backend = build_backend(&config).await.expect("chain backend builds Shadowsocks entry and exit");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), exit.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("connect through Shadowsocks chain");
    stream.write_all(PAYLOAD).await.expect("write chain payload");
    let mut echoed = vec![0_u8; PAYLOAD.len()];
    stream.read_exact(&mut echoed).await.expect("read chain payload");

    assert_eq!(echoed, PAYLOAD);
}

fn shadowsocks_hop(profile_id: &str, port: u16, password: &str) -> ResolvedChainRelayHopConfig {
    ResolvedChainRelayHopConfig {
        kind: "shadowsocks".to_string(),
        profile_id: profile_id.to_string(),
        server: "127.0.0.1".to_string(),
        server_port: i32::from(port),
        shadowsocks_method: Some("aes-256-gcm".to_string()),
        shadowsocks_password: Some(password.to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }
}

fn vless_hop(profile_id: &str, port: u16, server_name: &str) -> ResolvedChainRelayHopConfig {
    ResolvedChainRelayHopConfig {
        kind: "vless_reality".to_string(),
        profile_id: profile_id.to_string(),
        server: "127.0.0.1".to_string(),
        server_port: i32::from(port),
        server_name: server_name.to_string(),
        reality_public_key: valid_reality_public_key(),
        reality_short_id: String::new(),
        vless_flow: "none".to_string(),
        vless_uuid: Some("11111111-1111-1111-1111-111111111111".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }
}

/// Chain-relay happy path through a real VLESS+Reality entry hop and a real
/// VLESS+Reality exit hop on loopback. The entry hop tunnels the exit hop's
/// nested TLS-in-TLS handshake (`VlessRealityClient::connect_over`), and the
/// exit hop proxies to its embedded echo. Two round-trips assert bidirectional
/// payload integrity across the two-hop tunnel. Closes the first open criterion
/// on `audit-vless-chained-connect-over-relay-end-to-end-tests`.
#[tokio::test]
async fn chain_relay_routes_tcp_through_vless_entry_and_vless_exit() {
    let entry = VlessRealityLoopback::start().await.expect("start entry vless fixture");
    let exit = VlessRealityLoopback::start().await.expect("start exit vless fixture");

    let mut config = sample_config("chain_relay");
    chain_config_mut(&mut config).hops = vec![
        vless_hop("vless-entry", entry.port(), entry.server_name()),
        vless_hop("vless-exit", exit.port(), exit.server_name()),
    ];

    let backend = build_backend(&config).await.expect("chain backend builds VLESS entry and VLESS exit");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), exit.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("connect through VLESS-over-VLESS chain");

    for payload in [b"chain vless entry exit payload".as_slice(), b"second round-trip over the same tunnel".as_slice()]
    {
        stream.write_all(payload).await.expect("write chain payload");
        let mut echoed = vec![0_u8; payload.len()];
        stream.read_exact(&mut echoed).await.expect("read chain payload");
        assert_eq!(echoed, payload, "two-hop VLESS chain must echo byte-for-byte in both directions");
    }

    // The exit hop's request target is the caller's final destination.
    assert_eq!(exit.observed_target(), Some(format!("127.0.0.1:{}", exit.target_port())));
}

/// Chain-relay negative path: the second (exit) hop fails — it cannot reach the
/// final destination because the target port has no listener. The exit hop
/// closes without a VLESS response. Because VLESS response validation is lazy,
/// `connect_over` first returns a writable stream; the first downlink read must
/// then surface a recognizable connection-failure error rather than hanging or
/// silently succeeding. Closes the negative-path criterion on
/// `audit-vless-chained-connect-over-relay-end-to-end-tests`.
#[tokio::test]
async fn chain_relay_vless_second_hop_failure_surfaces_recognizable_error() {
    let entry = VlessRealityLoopback::start().await.expect("start entry vless fixture");
    let exit = VlessRealityLoopback::start().await.expect("start exit vless fixture");

    // A loopback port with no listener: bind, capture the port, drop the
    // listener. Connecting to it from the exit hop refuses deterministically.
    let dead_port = {
        let probe = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind probe listener");
        probe.local_addr().expect("probe addr").port()
    };

    let mut config = sample_config("chain_relay");
    chain_config_mut(&mut config).hops = vec![
        vless_hop("vless-entry", entry.port(), entry.server_name()),
        vless_hop("vless-exit", exit.port(), exit.server_name()),
    ];

    let backend = build_backend(&config).await.expect("chain backend builds for the failure case");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), dead_port));
    let mut stream =
        backend.connect_tcp(&target).await.expect("lazy VLESS response validation returns a writable chained stream");
    stream.write_all(b"trigger failed exit target").await.expect("write chained request payload");
    let mut response = [0_u8; 1];
    let error = tokio::time::timeout(Duration::from_secs(2), stream.read(&mut response))
        .await
        .expect("a second-hop failure must not hang the first downlink read")
        .expect_err("a second-hop failure to reach the destination must surface an error on read");

    assert!(
        matches!(
            error.kind(),
            io::ErrorKind::UnexpectedEof
                | io::ErrorKind::ConnectionReset
                | io::ErrorKind::ConnectionAborted
                | io::ErrorKind::ConnectionRefused
                | io::ErrorKind::BrokenPipe
        ),
        "second-hop failure must be a recognizable connection-failure class, got {:?}",
        error.kind(),
    );
}

#[tokio::test]
async fn chain_relay_routes_tcp_through_three_shadowsocks_hops() {
    const PAYLOAD: &[u8] = b"chain three-hop shadowsocks payload";

    let entry = ShadowsocksLoopback::start("aes-256-gcm", "entry-secret").await.expect("start entry hop");
    let middle = ShadowsocksLoopback::start("aes-256-gcm", "middle-secret").await.expect("start middle hop");
    let exit = ShadowsocksLoopback::start("aes-256-gcm", "exit-secret").await.expect("start exit hop");

    let mut config = sample_config("chain_relay");
    chain_config_mut(&mut config).hops = vec![
        shadowsocks_hop("ss-entry", entry.port(), "entry-secret"),
        shadowsocks_hop("ss-middle", middle.port(), "middle-secret"),
        shadowsocks_hop("ss-exit", exit.port(), "exit-secret"),
    ];

    let backend = build_backend(&config).await.expect("three-hop chain backend builds");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), exit.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("connect through three-hop chain");
    stream.write_all(PAYLOAD).await.expect("write three-hop payload");
    let mut echoed = vec![0_u8; PAYLOAD.len()];
    stream.read_exact(&mut echoed).await.expect("read three-hop payload");

    assert_eq!(echoed, PAYLOAD);
}

#[tokio::test]
async fn chain_relay_routes_tcp_through_four_shadowsocks_hops() {
    const PAYLOAD: &[u8] = b"chain four-hop shadowsocks payload";

    let entry = ShadowsocksLoopback::start("aes-256-gcm", "entry-secret").await.expect("start entry hop");
    let middle_a = ShadowsocksLoopback::start("aes-256-gcm", "middle-a-secret").await.expect("start middle hop a");
    let middle_b = ShadowsocksLoopback::start("aes-256-gcm", "middle-b-secret").await.expect("start middle hop b");
    let exit = ShadowsocksLoopback::start("aes-256-gcm", "exit-secret").await.expect("start exit hop");

    let mut config = sample_config("chain_relay");
    chain_config_mut(&mut config).hops = vec![
        shadowsocks_hop("ss-entry", entry.port(), "entry-secret"),
        shadowsocks_hop("ss-middle-a", middle_a.port(), "middle-a-secret"),
        shadowsocks_hop("ss-middle-b", middle_b.port(), "middle-b-secret"),
        shadowsocks_hop("ss-exit", exit.port(), "exit-secret"),
    ];

    let backend = build_backend(&config).await.expect("four-hop chain backend builds");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), exit.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("connect through four-hop chain");
    stream.write_all(PAYLOAD).await.expect("write four-hop payload");
    let mut echoed = vec![0_u8; PAYLOAD.len()];
    stream.read_exact(&mut echoed).await.expect("read four-hop payload");

    assert_eq!(echoed, PAYLOAD);
}

#[tokio::test]
async fn chain_relay_builds_three_heterogeneous_hops() {
    // VLESS entry -> Trojan middle -> Shadowsocks exit: exercises the
    // entry-connect + two chained connect_over folds across mixed kinds.
    let mut config = sample_config("chain_relay");
    chain_config_mut(&mut config).hops = vec![
        ResolvedChainRelayHopConfig {
            kind: "vless_reality".to_string(),
            profile_id: "vless-entry".to_string(),
            server: "127.0.0.1".to_string(),
            server_port: 443,
            server_name: "entry.example".to_string(),
            reality_public_key: valid_reality_public_key(),
            reality_short_id: String::new(),
            vless_uuid: Some("11111111-1111-1111-1111-111111111111".to_string()),
            ..ResolvedChainRelayHopConfig::default()
        },
        ResolvedChainRelayHopConfig {
            kind: "trojan".to_string(),
            profile_id: "trojan-middle".to_string(),
            server: "127.0.0.1".to_string(),
            server_port: 8443,
            server_name: "middle.example".to_string(),
            trojan_password: Some("middle-secret".to_string()),
            ..ResolvedChainRelayHopConfig::default()
        },
        shadowsocks_hop("ss-exit", 9443, "exit-secret"),
    ];

    let backend = build_backend(&config).await.expect("three heterogeneous hops build");
    assert_eq!(Some("chain_relay"), relay_backend_kind_id(&backend));
}

#[tokio::test]
async fn chain_relay_rejects_single_hop_chain() {
    let mut config = sample_config("chain_relay");
    chain_config_mut(&mut config).hops = vec![shadowsocks_hop("ss-only", 443, "secret")];

    let result = build_backend(&config).await;
    let error = result.err().expect("a one-hop chain must be rejected");
    assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
}

#[tokio::test]
async fn chain_relay_rejects_five_hop_chain() {
    let mut config = sample_config("chain_relay");
    chain_config_mut(&mut config).hops =
        (0..5).map(|index| shadowsocks_hop(&format!("ss-{index}"), 443 + index, "secret")).collect();

    let result = build_backend(&config).await;
    let error = result.err().expect("a five-hop chain must be rejected");
    assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
}

#[tokio::test]
async fn chain_relay_rejects_quic_kind_as_non_entry_hop() {
    // Hysteria2 can only be the entry hop; as a middle hop nothing can tunnel
    // through it, so the build must reject it.
    let mut config = sample_config("chain_relay");
    chain_config_mut(&mut config).hops = vec![
        shadowsocks_hop("ss-entry", 443, "entry-secret"),
        ResolvedChainRelayHopConfig {
            kind: "hysteria2".to_string(),
            profile_id: "hysteria-middle".to_string(),
            server: "127.0.0.1".to_string(),
            server_port: 8443,
            server_name: "middle.example".to_string(),
            hysteria_password: Some("middle-secret".to_string()),
            ..ResolvedChainRelayHopConfig::default()
        },
        shadowsocks_hop("ss-exit", 9443, "exit-secret"),
    ];

    let result = build_backend(&config).await;
    let error = result.err().expect("QUIC middle hop must be rejected");
    assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
}

#[test]
fn chain_relay_hop_count_bounds_match_kotlin_model() {
    assert_eq!(2, crate::config::CHAIN_RELAY_MIN_HOPS);
    assert_eq!(4, crate::config::CHAIN_RELAY_MAX_HOPS);
    assert!(ChainRelayConfig::validate_hop_count(2).is_ok());
    assert!(ChainRelayConfig::validate_hop_count(4).is_ok());
    assert!(ChainRelayConfig::validate_hop_count(1).is_err());
    assert!(ChainRelayConfig::validate_hop_count(5).is_err());
}

#[test]
fn chain_relay_ordered_hops_rejects_scalar_only_legacy_chain() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry_server = "entry.example".to_string();
    chain.entry_server_name = "entry.example".to_string();
    chain.exit_server = "exit.example".to_string();
    chain.exit_server_name = "exit.example".to_string();

    let ordered = chain.ordered_hops();
    assert!(ordered.is_empty());
    assert_eq!(0, chain.hop_count());
    assert!(ChainRelayConfig::validate_hop_count(chain.hop_count()).is_err());
}

#[test]
fn chain_relay_three_hop_list_round_trips_through_flat_wire() {
    // Author a 3-hop chain via the ordered `hops` list and confirm it crosses
    // the flat wire (serialize -> JSON -> deserialize) without being folded into
    // the legacy two-hop entry/exit scalars. This exercises the additive v7
    // `chainHops` wire field end-to-end inside relay-core.
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.hops = vec![
        shadowsocks_hop("hop-entry", 4431, "entry-secret"),
        shadowsocks_hop("hop-middle", 4432, "middle-secret"),
        shadowsocks_hop("hop-exit", 4433, "exit-secret"),
    ];

    let serialized = serde_json::to_value(&config).expect("serialize three-hop chain");
    let wire_hops = serialized["chainHops"].as_array().expect("chainHops array on wire");
    assert_eq!(3, wire_hops.len(), "ordered hops are carried over the wire");
    assert_eq!(serde_json::json!("hop-entry"), wire_hops[0]["profileId"]);
    assert_eq!(serde_json::json!("hop-middle"), wire_hops[1]["profileId"]);
    assert_eq!(serde_json::json!("hop-exit"), wire_hops[2]["profileId"]);

    let round_trip: ResolvedRelayRuntimeConfig =
        serde_json::from_value(serialized.clone()).expect("deserialize three-hop chain");
    let restored = match &round_trip.backend {
        RelayBackendConfig::ChainRelay(chain) => chain,
        other => panic!("expected chain relay config, got {other:?}"),
    };
    let ordered = restored.ordered_hops();
    assert_eq!(3, ordered.len(), "3-hop list survives the wire and is not folded to 2");
    assert_eq!("hop-entry", ordered[0].profile_id);
    assert_eq!("hop-middle", ordered[1].profile_id);
    assert_eq!("hop-exit", ordered[2].profile_id);
    assert!(ChainRelayConfig::validate_hop_count(ordered.len()).is_ok());

    // Re-serialization is lossless: the wire shape is stable across a full trip.
    assert_eq!(serialized, serde_json::to_value(&round_trip).expect("reserialize three-hop chain"));
}

#[test]
fn chain_relay_wire_rejects_out_of_range_hop_count() {
    // A 5-hop list is expressible over the wire (deserialization is additive and
    // does not bound-check) but the builder's `validate_hop_count` rejects it
    // with a typed InvalidInput error rather than silently truncating.
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.hops = vec![
        shadowsocks_hop("h0", 4431, "s0"),
        shadowsocks_hop("h1", 4432, "s1"),
        shadowsocks_hop("h2", 4433, "s2"),
        shadowsocks_hop("h3", 4434, "s3"),
        shadowsocks_hop("h4", 4435, "s4"),
    ];

    let serialized = serde_json::to_value(&config).expect("serialize five-hop chain");
    let round_trip: ResolvedRelayRuntimeConfig =
        serde_json::from_value(serialized).expect("deserialize five-hop chain");
    let restored = match &round_trip.backend {
        RelayBackendConfig::ChainRelay(chain) => chain,
        other => panic!("expected chain relay config, got {other:?}"),
    };
    assert_eq!(5, restored.ordered_hops().len());
    let error =
        ChainRelayConfig::validate_hop_count(restored.ordered_hops().len()).expect_err("5-hop chain must be rejected");
    assert_eq!(io::ErrorKind::InvalidInput, error.kind());
}

#[tokio::test]
async fn chain_relay_builds_anytls_entry_shadowsocks_exit_from_resolved_hops() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "anytls".to_string(),
        profile_id: "anytls-entry".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 443,
        server_name: "entry.example".to_string(),
        anytls_password: Some("entry-secret".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "shadowsocks".to_string(),
        profile_id: "shadowsocks-exit".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 8443,
        shadowsocks_method: Some("aes-256-gcm".to_string()),
        shadowsocks_password: Some("exit-secret".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));

    let backend = build_backend(&config).await.expect("chain backend builds AnyTLS entry and Shadowsocks exit");

    assert_eq!(Some("chain_relay"), relay_backend_kind_id(&backend));
}

#[tokio::test]
async fn chain_relay_builds_shadowtls_entry_shadowsocks_exit_from_resolved_hops() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "shadowtls_v3".to_string(),
        profile_id: "shadowtls-entry".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 443,
        server_name: "entry.example".to_string(),
        shadow_tls_password: Some("entry-secret".to_string()),
        shadow_tls_inner: Some(ResolvedShadowTlsInnerRelayConfig {
            kind: "vless_reality".to_string(),
            profile_id: "entry-inner".to_string(),
            server: "inner-entry.example".to_string(),
            server_port: 443,
            server_name: "inner-entry.example".to_string(),
            reality_public_key: valid_reality_public_key(),
            reality_short_id: String::new(),
            vless_flow: "xtls-rprx-vision".to_string(),
            vless_transport: "reality_tcp".to_string(),
            xhttp_mode: "auto".to_string(),
            vless_uuid: Some("33333333-3333-3333-3333-333333333333".to_string()),
            tls_fingerprint_profile: "firefox_stable".to_string(),
        }),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "shadowsocks".to_string(),
        profile_id: "shadowsocks-exit".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 8443,
        shadowsocks_method: Some("aes-256-gcm".to_string()),
        shadowsocks_password: Some("exit-secret".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));

    let backend = build_backend(&config).await.expect("chain backend builds ShadowTLS entry and Shadowsocks exit");

    assert_eq!(Some("chain_relay"), relay_backend_kind_id(&backend));
}

#[tokio::test]
async fn chain_relay_builds_shadowsocks_entry_shadowtls_exit_from_resolved_hops() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "shadowsocks".to_string(),
        profile_id: "shadowsocks-entry".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 443,
        shadowsocks_method: Some("aes-256-gcm".to_string()),
        shadowsocks_password: Some("entry-secret".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "shadowtls_v3".to_string(),
        profile_id: "shadowtls-exit".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 8443,
        server_name: "exit.example".to_string(),
        shadow_tls_password: Some("exit-secret".to_string()),
        shadow_tls_inner: Some(ResolvedShadowTlsInnerRelayConfig {
            kind: "vless_reality".to_string(),
            profile_id: "exit-inner".to_string(),
            server: "inner-exit.example".to_string(),
            server_port: 443,
            server_name: "inner-exit.example".to_string(),
            reality_public_key: valid_reality_public_key(),
            reality_short_id: String::new(),
            vless_flow: "xtls-rprx-vision".to_string(),
            vless_transport: "reality_tcp".to_string(),
            xhttp_mode: "auto".to_string(),
            vless_uuid: Some("44444444-4444-4444-4444-444444444444".to_string()),
            tls_fingerprint_profile: "safari_stable".to_string(),
        }),
        ..ResolvedChainRelayHopConfig::default()
    }));

    let backend = build_backend(&config).await.expect("chain backend builds Shadowsocks entry and ShadowTLS exit");

    assert_eq!(Some("chain_relay"), relay_backend_kind_id(&backend));
}

#[tokio::test]
async fn chain_relay_builds_hysteria2_entry_shadowsocks_exit_from_resolved_hops() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "hysteria2".to_string(),
        profile_id: "hysteria-entry".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 443,
        server_name: "entry.example".to_string(),
        hysteria_password: Some("entry-secret".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "shadowsocks".to_string(),
        profile_id: "shadowsocks-exit".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 8443,
        shadowsocks_method: Some("aes-256-gcm".to_string()),
        shadowsocks_password: Some("exit-secret".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));

    let backend = build_backend(&config).await.expect("chain backend builds Hysteria2 entry and Shadowsocks exit");

    assert_eq!(Some("chain_relay"), relay_backend_kind_id(&backend));
}

#[tokio::test]
async fn chain_relay_builds_tuic_entry_shadowsocks_exit_from_resolved_hops() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "tuic_v5".to_string(),
        profile_id: "tuic-entry".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 443,
        server_name: "entry.example".to_string(),
        tuic_uuid: Some("55555555-5555-5555-5555-555555555555".to_string()),
        tuic_password: Some("entry-secret".to_string()),
        tuic_zero_rtt: false,
        tuic_congestion_control: "bbr".to_string(),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "shadowsocks".to_string(),
        profile_id: "shadowsocks-exit".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: 8443,
        shadowsocks_method: Some("aes-256-gcm".to_string()),
        shadowsocks_password: Some("exit-secret".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));

    let backend = build_backend(&config).await.expect("chain backend builds TUIC entry and Shadowsocks exit");

    assert_eq!(Some("chain_relay"), relay_backend_kind_id(&backend));
}

#[tokio::test]
async fn chain_relay_routes_tcp_through_shadowsocks_entry_and_anytls_exit() {
    const PAYLOAD: &[u8] = b"chain shadowsocks entry anytls exit payload";

    let entry =
        ShadowsocksLoopback::start("aes-256-gcm", "entry-secret").await.expect("start entry shadowsocks fixture");
    let exit =
        AnyTlsLoopback::start("exit-secret", AnyTlsLoopbackConfig::default()).await.expect("start exit anytls fixture");
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "shadowsocks".to_string(),
        profile_id: "shadowsocks-entry".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: i32::from(entry.port()),
        shadowsocks_method: Some("aes-256-gcm".to_string()),
        shadowsocks_password: Some("entry-secret".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "anytls".to_string(),
        profile_id: "anytls-exit".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: i32::from(exit.port()),
        server_name: exit.server_name().to_string(),
        anytls_password: Some("exit-secret".to_string()),
        anytls_root_certificate_pem: Some(exit.certificate_pem().to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));

    let backend = build_backend(&config).await.expect("chain backend builds Shadowsocks entry and AnyTLS exit");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), exit.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("connect through Shadowsocks to AnyTLS exit");
    stream.write_all(PAYLOAD).await.expect("write chain payload");
    let mut echoed = vec![0_u8; PAYLOAD.len()];
    stream.read_exact(&mut echoed).await.expect("read chain payload");

    assert_eq!(echoed, PAYLOAD);
    assert_eq!(exit.observed().tls_session_count, 1);
}

#[tokio::test]
async fn chain_relay_dead_resolved_entry_does_not_bypass_to_legacy_entry() {
    let legacy_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind legacy entry probe");
    let legacy_addr = legacy_listener.local_addr().expect("legacy entry address");
    let dead_entry_port = reserve_unused_local_port().await;
    let (legacy_entry_seen_tx, legacy_entry_seen_rx) = oneshot::channel();
    tokio::spawn(async move {
        if legacy_listener.accept().await.is_ok() {
            let _ = legacy_entry_seen_tx.send(());
        }
    });

    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry_server = legacy_addr.ip().to_string();
    chain.entry_port = i32::from(legacy_addr.port());
    chain.entry_server_name = "legacy-entry.example".to_string();
    chain.entry_public_key = valid_reality_public_key();
    chain.entry_short_id = String::new();
    chain.entry_uuid = Some("00000000-0000-0000-0000-000000000000".to_string());
    chain.exit_server = "127.0.0.1".to_string();
    chain.exit_port = 443;
    chain.exit_server_name = "exit.example".to_string();
    chain.exit_public_key = valid_reality_public_key();
    chain.exit_short_id = String::new();
    chain.exit_uuid = Some("00000000-0000-0000-0000-000000000000".to_string());
    chain.entry = Some(Box::new(ResolvedChainRelayHopConfig {
        kind: "vless_reality".to_string(),
        profile_id: "dead-entry".to_string(),
        server: "127.0.0.1".to_string(),
        server_port: i32::from(dead_entry_port),
        server_name: "dead-entry.example".to_string(),
        reality_public_key: valid_reality_public_key(),
        reality_short_id: String::new(),
        vless_uuid: Some("11111111-1111-1111-1111-111111111111".to_string()),
        ..ResolvedChainRelayHopConfig::default()
    }));
    chain.exit = Some(Box::new(vless_hop("exit", 443, "exit.example")));

    let backend = build_backend(&config).await.expect("chain backend builds from resolved hops");
    let connect_result = tokio::time::timeout(
        Duration::from_secs(2),
        backend.connect_tcp(&RelayTargetAddr::Domain("target.example".to_string(), 443)),
    )
    .await
    .expect("dead entry must fail promptly");

    assert!(connect_result.is_err(), "dead resolved entry must fail the whole chain");
    assert!(
        tokio::time::timeout(Duration::from_millis(100), legacy_entry_seen_rx).await.is_err(),
        "chain must not route around a dead resolved entry by dialing stale legacy entry fields",
    );
}

async fn reserve_unused_local_port() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("reserve local port");
    listener.local_addr().expect("reserved local address").port()
}

fn valid_reality_public_key() -> String {
    "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=".to_string()
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

fn chain_config_mut(config: &mut ResolvedRelayRuntimeConfig) -> &mut ChainRelayConfig {
    match &mut config.backend {
        RelayBackendConfig::ChainRelay(chain) => chain,
        _ => panic!("expected chain relay config"),
    }
}

fn vless_config_mut(config: &mut ResolvedRelayRuntimeConfig) -> &mut VlessRealityRelayConfig {
    match &mut config.backend {
        RelayBackendConfig::VlessReality(vless) => vless,
        _ => panic!("expected VLESS Reality config"),
    }
}

fn plain_vless_config_mut(config: &mut ResolvedRelayRuntimeConfig) -> &mut VlessRelayConfig {
    match &mut config.backend {
        RelayBackendConfig::Vless(vless) => vless,
        _ => panic!("expected VLESS config"),
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

fn trojan_config_mut(config: &mut ResolvedRelayRuntimeConfig) -> &mut TrojanRelayConfig {
    match &mut config.backend {
        RelayBackendConfig::Trojan(trojan) => trojan,
        _ => panic!("expected Trojan config"),
    }
}

fn anytls_config_mut(config: &mut ResolvedRelayRuntimeConfig) -> &mut AnyTlsRelayConfig {
    match &mut config.backend {
        RelayBackendConfig::AnyTls(anytls) => anytls,
        _ => panic!("expected AnyTLS config"),
    }
}

fn shadowsocks_config_mut(config: &mut ResolvedRelayRuntimeConfig) -> &mut ShadowsocksRelayConfig {
    match &mut config.backend {
        RelayBackendConfig::Shadowsocks(shadowsocks) => shadowsocks,
        _ => panic!("expected Shadowsocks config"),
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
async fn relay_runtime_accepts_xudp_enabled_vless_reality_vision() {
    let mut config = sample_config("vless_reality");
    config.common.udp_enabled = true;
    vless_config_mut(&mut config).reality_public_key = valid_reality_public_key();
    let backend = build_backend(&config).await.expect("VLESS Reality backend");

    assert!(backend.capabilities().udp, "Vision Reality profile should expose XUDP");
    validate_runtime_config(&config, &backend).expect("XUDP-enabled Reality should validate");
}

#[tokio::test]
async fn relay_runtime_rejects_xudp_for_flowless_reality_and_xhttp() {
    let mut flowless = sample_config("vless_reality");
    flowless.common.udp_enabled = true;
    let flowless_config = vless_config_mut(&mut flowless);
    flowless_config.reality_public_key = valid_reality_public_key();
    flowless_config.vless_flow.clear();
    let flowless_backend = build_backend(&flowless).await.expect("flowless Reality backend");
    let flowless_error = validate_runtime_config(&flowless, &flowless_backend).expect_err("flowless XUDP must fail");
    assert_eq!(flowless_error.kind(), io::ErrorKind::Unsupported);

    let mut xhttp = sample_config("vless_reality");
    xhttp.common.udp_enabled = true;
    let xhttp_config = vless_config_mut(&mut xhttp);
    xhttp_config.reality_public_key = valid_reality_public_key();
    xhttp_config.vless_transport = "xhttp".to_string();
    xhttp_config.vless_flow.clear();
    let xhttp_backend = build_backend(&xhttp).await.expect("xHTTP backend");
    let xhttp_error = validate_runtime_config(&xhttp, &xhttp_backend).expect_err("xHTTP UDP must fail");
    assert_eq!(xhttp_error.kind(), io::ErrorKind::Unsupported);
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
fn confirm_good_observation_is_limited_to_catalog_validated_classic_reality() {
    let classic = RelayRuntime::new(sample_config("vless_reality"));
    assert!(classic.confirm_good_dpi_eligible());
    assert!(classic.telemetry().confirm_good_dpi_eligible);

    let mut unknown_profile = sample_config("vless_reality");
    unknown_profile.common.tls_fingerprint_profile = "unknown_fallback".to_string();
    assert!(!RelayRuntime::new(unknown_profile).confirm_good_dpi_eligible());

    let mut xhttp = sample_config("vless_reality");
    vless_config_mut(&mut xhttp).vless_transport = "xhttp".to_string();
    assert!(!RelayRuntime::new(xhttp).confirm_good_dpi_eligible());

    assert!(!RelayRuntime::new(sample_config("chain_relay")).confirm_good_dpi_eligible());
}

#[test]
fn upstream_telemetry_omits_masque_credentials_path_and_query() {
    let mut config = sample_config("masque");
    let RelayBackendConfig::Masque(masque) = &mut config.backend else {
        panic!("expected MASQUE config");
    };
    masque.url = "https://user:sentinel-password@masque.example:8443/secret-path?token=sentinel-query".to_string();

    let upstream = describe_upstream(&config);
    assert_eq!("masque.example:8443", upstream);
    assert!(!upstream.contains("sentinel"));
}

#[test]
fn relay_runtime_routes_vless_xhttp_through_tcp_only_backend() {
    let mut config = sample_config("vless_reality");
    let vless = vless_config_mut(&mut config);
    vless.vless_transport = "xhttp".to_string();
    vless.xhttp_path = "/api/v1/stream".to_string();

    let capabilities = planned_backend_capabilities(&config);
    assert_eq!((true, false), (capabilities.tcp, capabilities.udp));
    assert_eq!("relay.example:443", describe_upstream(&config));
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
