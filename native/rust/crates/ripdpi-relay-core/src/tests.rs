use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::Duration;

use android_support::EventRingLayer;
use local_network_fixture::{AnyTlsLoopback, AnyTlsLoopbackConfig, ShadowsocksLoopback, TrojanLoopback};
use ripdpi_relay_mux::RelayPoolConfig;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tracing_subscriber::prelude::*;

use crate::backend::{build_backend, RelayBackend};
use crate::bootstrap::{bootstrap_relay_endpoints_with, RelayEndpointBootstrapResolver};
use crate::config::{
    AnyTlsRelayConfig, ChainRelayConfig, CloudflareTunnelRelayConfig, CommonRelayConfig, Hysteria2RelayConfig,
    MasqueRelayConfig, NaiveProxyRelayConfig, RelayBackendConfig, RelayKind, ResolvedChainRelayHopConfig,
    ResolvedRelayFinalmaskConfig, ResolvedRelayRuntimeConfig, ResolvedShadowTlsInnerRelayConfig, ShadowTlsRelayConfig,
    ShadowsocksRelayConfig, TrojanRelayConfig, TuicRelayConfig, VlessRealityRelayConfig,
};
use crate::runtime::RelayRuntime;
use crate::runtime_validation::{
    describe_upstream, planned_backend_capabilities, planned_backend_fallback_mode, pool_config_for_backend,
    validate_finalmask_config, validate_runtime_config,
};
use crate::socks::RelayTargetAddr;

#[derive(Default)]
struct FakeBootstrapResolver {
    requests: Vec<(String, u16)>,
}

impl RelayEndpointBootstrapResolver for FakeBootstrapResolver {
    async fn resolve_direct(&mut self, host: &str, port: u16) -> io::Result<SocketAddr> {
        self.requests.push((host.to_string(), port));
        let octet = u8::try_from(self.requests.len()).expect("fixture request count fits u8");
        Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, octet)), port))
    }
}

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
        "naiveproxy" => RelayBackendConfig::NaiveProxy(NaiveProxyRelayConfig::default()),
        other => RelayBackendConfig::Unsupported(crate::config::UnsupportedRelayConfig { kind: other.to_string() }),
    };
    ResolvedRelayRuntimeConfig { common, backend }
}

#[tokio::test]
async fn relay_endpoint_bootstrap_resolves_common_hostname_once_and_preserves_sni() {
    let config = sample_config("vless_reality");
    let mut resolver = FakeBootstrapResolver::default();

    let bootstrapped = bootstrap_relay_endpoints_with(&config, &mut resolver).await.expect("bootstrap endpoints");

    assert_eq!(resolver.requests, vec![("relay.example".to_string(), 443)]);
    assert_eq!(bootstrapped.common.server, "203.0.113.1");
    assert_eq!(bootstrapped.common.server_name, "relay.example");
}

#[tokio::test]
async fn relay_endpoint_bootstrap_emits_direct_lookup_event() {
    let buffers = android_support::EventRingBuffers::default();
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
    let dispatch = tracing::Dispatch::new(subscriber);
    let _guard = tracing::dispatcher::set_default(&dispatch);
    let config = sample_config("vless_reality");
    let mut resolver = FakeBootstrapResolver::default();

    let _bootstrapped = bootstrap_relay_endpoints_with(&config, &mut resolver).await.expect("bootstrap endpoints");

    let events = buffers.drain_relay();
    assert!(
        events.iter().any(|event| event.kind.as_deref() == Some("relay_endpoint_bootstrap_direct_lookup")),
        "bootstrap must publish the one allowed direct DNS lookup",
    );
}

#[tokio::test]
async fn relay_endpoint_bootstrap_skips_ip_literals() {
    let mut config = sample_config("trojan");
    config.common.server = "198.51.100.8".to_string();
    let mut resolver = FakeBootstrapResolver::default();

    let bootstrapped = bootstrap_relay_endpoints_with(&config, &mut resolver).await.expect("bootstrap endpoints");

    assert!(resolver.requests.is_empty(), "IP relay endpoints must not trigger bootstrap DNS");
    assert_eq!(bootstrapped.common.server, "198.51.100.8");
    assert_eq!(bootstrapped.common.server_name, "relay.example");
}

#[tokio::test]
async fn relay_endpoint_bootstrap_resolves_chain_entry_direct_and_leaves_exit_for_relay_dns() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry_server = "entry.example".to_string();
    chain.entry_server_name = "entry.example".to_string();
    chain.exit_server = "exit.example".to_string();
    chain.exit_server_name = "exit.example".to_string();
    let mut resolver = FakeBootstrapResolver::default();

    let bootstrapped = bootstrap_relay_endpoints_with(&config, &mut resolver).await.expect("bootstrap endpoints");

    assert_eq!(resolver.requests, vec![("entry.example".to_string(), 443)]);
    let RelayBackendConfig::ChainRelay(chain) = bootstrapped.backend else {
        panic!("expected chain config");
    };
    assert_eq!(chain.entry_server, "203.0.113.1");
    assert_eq!(chain.entry_server_name, "entry.example");
    assert_eq!(chain.exit_server, "exit.example");
    assert_eq!(chain.exit_server_name, "exit.example");
}

#[tokio::test]
async fn relay_endpoint_bootstrap_resolves_resolved_chain_entry_and_leaves_resolved_exit_for_relay_dns() {
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
    let mut resolver = FakeBootstrapResolver::default();

    let bootstrapped = bootstrap_relay_endpoints_with(&config, &mut resolver).await.expect("bootstrap endpoints");

    assert_eq!(resolver.requests, vec![("entry.example".to_string(), 443)]);
    let RelayBackendConfig::ChainRelay(chain) = bootstrapped.backend else {
        panic!("expected chain config");
    };
    let entry = chain.entry.expect("resolved entry");
    let exit = chain.exit.expect("resolved exit");
    assert_eq!(entry.server, "203.0.113.1");
    assert_eq!(entry.server_name, "entry.example");
    assert_eq!(exit.server, "exit.example");
    assert_eq!(exit.server_name, "exit.example");
}

#[tokio::test]
async fn relay_endpoint_bootstrap_resolves_shadowtls_outer_and_inner_endpoints() {
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
        vless_transport: "reality_tcp".to_string(),
        vless_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
    });
    let mut resolver = FakeBootstrapResolver::default();

    let bootstrapped = bootstrap_relay_endpoints_with(&config, &mut resolver).await.expect("bootstrap endpoints");

    assert_eq!(resolver.requests, vec![("outer.example".to_string(), 443), ("inner.example".to_string(), 443)]);
    assert_eq!(bootstrapped.common.server, "203.0.113.1");
    assert_eq!(bootstrapped.common.server_name, "outer.example");
    let RelayBackendConfig::ShadowTlsV3(shadowtls) = bootstrapped.backend else {
        panic!("expected shadowtls config");
    };
    let inner = shadowtls.inner.expect("inner relay config");
    assert_eq!(inner.server, "203.0.113.2");
    assert_eq!(inner.server_name, "inner.example");
}

#[tokio::test]
async fn relay_endpoint_bootstrap_resolves_masque_url_host_without_rewriting_authority() {
    let mut config = sample_config("masque");
    config.common.server = "unused-common.example".to_string();
    let RelayBackendConfig::Masque(masque) = &mut config.backend else {
        panic!("expected MASQUE config");
    };
    masque.url = "https://masque.example:8443/.well-known/masque/ip".to_string();
    let mut resolver = FakeBootstrapResolver::default();

    let bootstrapped = bootstrap_relay_endpoints_with(&config, &mut resolver).await.expect("bootstrap endpoints");

    assert_eq!(resolver.requests, vec![("masque.example".to_string(), 8443)]);
    let RelayBackendConfig::Masque(masque) = bootstrapped.backend else {
        panic!("expected MASQUE config");
    };
    assert_eq!(masque.url, "https://masque.example:8443/.well-known/masque/ip");
    assert_eq!(masque.proxy_socket_addr.expect("MASQUE bootstrap addr").to_string(), "203.0.113.1:8443");
}

#[test]
fn relay_runtime_config_round_trips_flattened_backend_fields() {
    for kind in [
        "hysteria2",
        "tuic_v5",
        "vless_reality",
        "cloudflare_tunnel",
        "chain_relay",
        "masque",
        "shadowtls_v3",
        "trojan",
        "anytls",
        "shadowsocks",
    ] {
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
    let hop: ResolvedChainRelayHopConfig = serde_json::from_value(serde_json::json!({
        "kind": "masque",
        "profileId": "masque-exit"
    }))
    .expect("deserialize sparse Kotlin-style chain hop config");

    assert_eq!(443, hop.server_port);
    assert_eq!("reality_tcp", hop.vless_transport);
    assert_eq!("consume_existing", hop.cloudflare_tunnel_mode);
    assert!(hop.masque_use_http2_fallback);
    assert_eq!("bbr", hop.tuic_congestion_control);
    assert_eq!("chrome_stable", hop.tls_fingerprint_profile);
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

#[tokio::test]
async fn relay_runtime_builds_trojan_backend_and_connects_tcp_fixture() {
    const PAYLOAD: &[u8] = b"relay-core trojan tcp payload";

    let fixture = TrojanLoopback::start("secret").await.expect("start trojan fixture");
    let mut config = sample_config("trojan");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    trojan_config_mut(&mut config).root_certificate_pem = Some(fixture.certificate_pem().to_string());

    let backend = build_backend(&config).await.expect("trojan backend");
    match &backend {
        RelayBackend::Trojan(_) => {}
        other => panic!("expected Trojan backend, got {:?}", std::mem::discriminant(other)),
    }

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("trojan connect tcp");
    stream.write_all(PAYLOAD).await.expect("write tunnel payload");
    let mut echoed = vec![0_u8; PAYLOAD.len()];
    stream.read_exact(&mut echoed).await.expect("read tunnel payload");
    assert_eq!(echoed, PAYLOAD);
}

#[tokio::test]
async fn relay_runtime_builds_trojan_udp_associate_fixture() {
    const PAYLOAD: &[u8] = b"relay-core trojan udp payload";

    let fixture = TrojanLoopback::start("secret").await.expect("start trojan fixture");
    let mut config = sample_config("trojan");
    config.common.udp_enabled = true;
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    trojan_config_mut(&mut config).root_certificate_pem = Some(fixture.certificate_pem().to_string());

    let capabilities = planned_backend_capabilities(&config);
    assert!(capabilities.udp, "Trojan should report UDP capability");
    let backend = build_backend(&config).await.expect("trojan backend");
    validate_runtime_config(&config, &backend).expect("trojan udp should validate");

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.udp_target_port()));
    let mut udp = backend.open_udp_session().await.expect("trojan udp session");
    udp.send_to(&target, PAYLOAD).await.expect("send udp payload");
    let (source, echoed) = udp.recv_from().await.expect("receive udp payload");
    assert_eq!(source, target);
    assert_eq!(echoed, PAYLOAD);
}

#[tokio::test]
async fn relay_runtime_builds_anytls_backend_and_connects_tcp_fixture() {
    const PAYLOAD: &[u8] = b"relay-core anytls tcp payload";

    let fixture = AnyTlsLoopback::start("secret", AnyTlsLoopbackConfig::default()).await.expect("start anytls fixture");
    let mut config = sample_config("anytls");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    anytls_config_mut(&mut config).root_certificate_pem = Some(fixture.certificate_pem().to_string());

    let backend = build_backend(&config).await.expect("anytls backend");
    match &backend {
        RelayBackend::AnyTls(_) => {}
        other => panic!("expected AnyTLS backend, got {:?}", std::mem::discriminant(other)),
    }

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("anytls connect tcp");
    stream.write_all(PAYLOAD).await.expect("write tunnel payload");
    let mut echoed = vec![0_u8; PAYLOAD.len()];
    stream.read_exact(&mut echoed).await.expect("read tunnel payload");
    assert_eq!(echoed, PAYLOAD);
    assert_eq!(fixture.observed().tls_session_count, 1);
}

#[tokio::test]
async fn relay_runtime_builds_anytls_udp_over_tcp_fixture() {
    const PAYLOAD: &[u8] = b"relay-core anytls udp payload";

    let fixture = AnyTlsLoopback::start("secret", AnyTlsLoopbackConfig::default()).await.expect("start anytls fixture");
    let mut config = sample_config("anytls");
    config.common.udp_enabled = true;
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    anytls_config_mut(&mut config).root_certificate_pem = Some(fixture.certificate_pem().to_string());

    let capabilities = planned_backend_capabilities(&config);
    assert_eq!((true, true, true), (capabilities.tcp, capabilities.udp, capabilities.reusable));
    let backend = build_backend(&config).await.expect("anytls backend");
    validate_runtime_config(&config, &backend).expect("anytls udp should validate");

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.udp_target_port()));
    let mut udp = backend.open_udp_session().await.expect("anytls udp-over-tcp session");
    udp.send_to(&target, PAYLOAD).await.expect("send udp payload");
    let (source, echoed) = udp.recv_from().await.expect("receive udp payload");
    assert_eq!(source, target);
    assert_eq!(echoed, PAYLOAD);
    assert_eq!(fixture.observed().udp_magic_targets, vec!["sp.v2.udp-over-tcp.arpa:0".to_string()]);
}

#[tokio::test]
async fn relay_runtime_builds_shadowsocks_backend_and_connects_tcp_fixture() {
    const PAYLOAD: &[u8] = b"relay-core shadowsocks tcp payload";

    let fixture = ShadowsocksLoopback::start("aes-256-gcm", "secret").await.expect("start shadowsocks fixture");
    let mut config = sample_config("shadowsocks");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    shadowsocks_config_mut(&mut config).method = "aes-256-gcm".to_string();

    let backend = build_backend(&config).await.expect("shadowsocks backend");
    match &backend {
        RelayBackend::Shadowsocks(_) => {}
        other => panic!("expected Shadowsocks backend, got {:?}", std::mem::discriminant(other)),
    }

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("shadowsocks connect tcp");
    stream.write_all(PAYLOAD).await.expect("write tunnel payload");
    let mut echoed = vec![0_u8; PAYLOAD.len()];
    stream.read_exact(&mut echoed).await.expect("read tunnel payload");
    assert_eq!(echoed, PAYLOAD);
}

#[tokio::test]
async fn relay_runtime_builds_shadowsocks_udp_associate_fixture() {
    const PAYLOAD: &[u8] = b"relay-core shadowsocks udp payload";

    let fixture = ShadowsocksLoopback::start("aes-256-gcm", "secret").await.expect("start shadowsocks fixture");
    let mut config = sample_config("shadowsocks");
    config.common.udp_enabled = true;
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());

    let capabilities = planned_backend_capabilities(&config);
    assert!(capabilities.udp, "Shadowsocks should report UDP capability");
    let backend = build_backend(&config).await.expect("shadowsocks backend");
    validate_runtime_config(&config, &backend).expect("shadowsocks udp should validate");

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.udp_target_port()));
    let mut udp = backend.open_udp_session().await.expect("shadowsocks udp session");
    udp.send_to(&target, PAYLOAD).await.expect("send udp payload");
    let (source, echoed) = udp.recv_from().await.expect("receive udp payload");
    assert_eq!(source, target);
    assert_eq!(echoed, PAYLOAD);
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
/// every `RelayKind`. `planned_backend_capabilities` and the bind-IP gate
/// resolve through the `RELAY_TRANSPORT_REGISTRATIONS` descriptors, so a wrong
/// descriptor row is caught here against literal expectations.
#[test]
fn relay_planned_capabilities_are_pinned_for_every_kind() {
    // kind_id, tcp, udp, reusable, supports_outbound_bind_ip
    let pinned: [(&str, bool, bool, bool, bool); 11] = [
        ("hysteria2", true, true, true, false),
        ("tuic_v5", true, true, true, true),
        ("vless_reality", true, false, false, true),
        ("cloudflare_tunnel", true, false, true, true),
        ("chain_relay", true, false, false, true),
        ("masque", true, true, true, false),
        ("shadowtls_v3", true, false, false, true),
        ("trojan", true, true, false, true),
        ("anytls", true, true, true, true),
        ("shadowsocks", true, true, false, true),
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

/// How the relay runtime dispatches a concrete `relay_kind`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RelayDispatchClass {
    /// Served in-process: the kind's `RELAY_TRANSPORT_REGISTRATIONS` entry
    /// carries a backend builder that constructs a `RelayBackend` variant.
    InProcessBackend,
    /// A registered kind with a descriptor but no in-process builder; it routes
    /// through an out-of-process subprocess. NaiveProxy is the only such kind
    /// today -- its registration's `fallback_mode` is `"subprocess"` and
    /// `build_backend` deliberately yields `RelayBackend::Unsupported`.
    SubprocessFallback,
    /// Not a relay transport (`"off"`, unknown kinds): no registration.
    Unsupported,
}

/// Exhaustive dispatch classification for every `RelayKind`. Adding a new
/// `RelayKind` variant fails to compile here until its dispatch path is
/// declared -- the drift guard tying the registration table to the
/// runtime-dispatch surface.
fn relay_dispatch_class(kind: RelayKind<'_>) -> RelayDispatchClass {
    match kind {
        RelayKind::Hysteria2
        | RelayKind::TuicV5
        | RelayKind::VlessReality { .. }
        | RelayKind::CloudflareTunnel
        | RelayKind::ChainRelay
        | RelayKind::Masque
        | RelayKind::ShadowTlsV3
        | RelayKind::Trojan
        | RelayKind::AnyTls
        | RelayKind::Shadowsocks => RelayDispatchClass::InProcessBackend,
        RelayKind::NaiveProxy => RelayDispatchClass::SubprocessFallback,
        RelayKind::Unsupported(_) => RelayDispatchClass::Unsupported,
    }
}

/// Compile-time drift guard for the runtime-dispatch backend enum. The
/// `dispatch_pooled_backend!` macro routes SOCKS traffic by `RelayBackend`
/// variant; this maps each variant back to the `relay_kind` it serves. A new
/// `RelayBackend` variant fails to compile here until it is mapped, which
/// forces a matching registration. `Unsupported` carries no `relay_kind`.
fn relay_backend_kind_id(backend: &RelayBackend) -> Option<&'static str> {
    match backend {
        RelayBackend::Hysteria2(_) => Some("hysteria2"),
        RelayBackend::Tuic(_) => Some("tuic_v5"),
        RelayBackend::VlessReality(_) | RelayBackend::Xhttp(_) => Some("vless_reality"),
        RelayBackend::ChainRelay { .. } => Some("chain_relay"),
        RelayBackend::Masque(_) => Some("masque"),
        RelayBackend::ShadowTls(_) => Some("shadowtls_v3"),
        RelayBackend::Trojan(_) => Some("trojan"),
        RelayBackend::AnyTls(_) => Some("anytls"),
        RelayBackend::Shadowsocks(_) => Some("shadowsocks"),
        RelayBackend::Unsupported { .. } => None,
    }
}

/// Drift guard for the merged relay transport registry, tying
/// `RELAY_TRANSPORT_REGISTRATIONS` to the runtime-dispatch surface:
///
/// * every concrete `RelayKind` has exactly one registration;
/// * every registration carries a descriptor whose `kind_id` round-trips and
///   whose planned capabilities it reproduces;
/// * exactly NaiveProxy has no in-process builder (subprocess fallback);
/// * `"off"` / unknown kinds do not register.
///
/// `relay_dispatch_class` and `relay_backend_kind_id` are both exhaustive
/// matches, so a new `RelayKind` or `RelayBackend` variant fails to compile
/// until the registry is extended.
#[test]
fn relay_transport_registry_is_consistent() {
    use crate::transport_descriptor::{
        relay_transport_descriptor, relay_transport_registration, RELAY_TRANSPORT_REGISTRATIONS,
    };

    // One config per concrete RelayKind, both VLESS sub-modes, plus "off" and an
    // unknown kind. `from_config` drives the classification.
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
        sample_config("trojan"),
        sample_config("anytls"),
        sample_config("shadowsocks"),
        sample_config("naiveproxy"),
        sample_config("off"),
        sample_config("totally_unknown"),
    ];

    // Forward: every dispatched (in-process or subprocess) kind resolves to
    // exactly one registration; "off"/unknown kinds resolve to none.
    let mut registered = std::collections::BTreeSet::new();
    for config in &configs {
        let kind_id = config.kind_id();
        let registration = relay_transport_registration(kind_id);
        match relay_dispatch_class(RelayKind::from_config(config)) {
            RelayDispatchClass::InProcessBackend | RelayDispatchClass::SubprocessFallback => {
                let registration =
                    registration.unwrap_or_else(|| panic!("dispatched relay kind {kind_id} must have a registration"));
                assert_eq!(kind_id, registration.descriptor.kind_id, "registration kind_id round-trip for {kind_id}");
                let rows =
                    RELAY_TRANSPORT_REGISTRATIONS.iter().filter(|entry| entry.descriptor.kind_id == kind_id).count();
                assert_eq!(1, rows, "{kind_id} must have exactly one registration");
                registered.insert(kind_id.to_string());
            }
            RelayDispatchClass::Unsupported => {
                assert!(registration.is_none(), "unsupported/off relay kind {kind_id} must not register");
            }
        }
    }

    // NaiveProxy is the one documented exception: a concrete, descriptor-backed
    // kind with no in-process builder -- it dispatches to the subprocess
    // fallback rather than a `RelayBackend` variant.
    assert_eq!(RelayDispatchClass::SubprocessFallback, relay_dispatch_class(RelayKind::NaiveProxy));

    // Reverse: every registration is a dispatched kind with a well-formed
    // descriptor that round-trips and reproduces its planned capabilities;
    // exactly NaiveProxy is the builderless subprocess-fallback kind.
    for registration in RELAY_TRANSPORT_REGISTRATIONS {
        let descriptor = registration.descriptor;
        assert!(descriptor.tcp, "{} registration: every relay transport relays TCP", descriptor.kind_id);
        assert!(!descriptor.label.is_empty(), "{} registration needs a label", descriptor.kind_id);
        assert_eq!(
            Some(&descriptor),
            relay_transport_descriptor(descriptor.kind_id),
            "{} descriptor lookup must round-trip",
            descriptor.kind_id,
        );
        assert_ne!(
            RelayDispatchClass::Unsupported,
            relay_dispatch_class(RelayKind::from_config(&sample_config(descriptor.kind_id))),
            "registration kind {} must map to a dispatched RelayKind",
            descriptor.kind_id,
        );
        assert!(registered.contains(descriptor.kind_id), "registration kind {} is unreachable", descriptor.kind_id);

        let capabilities = planned_backend_capabilities(&sample_config(descriptor.kind_id));
        assert_eq!(
            (descriptor.tcp, descriptor.udp, descriptor.reusable),
            (capabilities.tcp, capabilities.udp, capabilities.reusable),
            "{} planned capabilities must match its registered descriptor",
            descriptor.kind_id,
        );

        if descriptor.kind_id == "naiveproxy" {
            assert!(registration.builder.is_none(), "naiveproxy is subprocess-backed: no in-process builder");
            assert_eq!(Some("subprocess"), registration.fallback_mode, "naiveproxy keeps the subprocess fallback");
        } else {
            assert!(registration.builder.is_some(), "{} must register an in-process builder", descriptor.kind_id);
            assert_eq!(None, registration.fallback_mode, "{} is in-process: no fallback mode", descriptor.kind_id);
        }
    }
    assert_eq!(
        RELAY_TRANSPORT_REGISTRATIONS.len(),
        registered.len(),
        "registration count must equal the number of dispatched relay kinds",
    );

    // Every in-process `RelayBackend` dispatch variant maps to a registered
    // kind. `relay_backend_kind_id` is exhaustive: a new variant breaks
    // compilation until mapped.
    assert_eq!(None, relay_backend_kind_id(&RelayBackend::Unsupported { kind: "off".to_string() }));
    for kind_id in [
        "hysteria2",
        "tuic_v5",
        "vless_reality",
        "chain_relay",
        "masque",
        "shadowtls_v3",
        "trojan",
        "anytls",
        "shadowsocks",
    ] {
        assert!(
            relay_transport_registration(kind_id).is_some(),
            "runtime-dispatch backend kind {kind_id} must have a registration",
        );
    }

    assert!(relay_transport_registration("off").is_none(), "\"off\" is not a relay transport");
    assert!(relay_transport_registration("totally_unknown").is_none(), "unknown kinds have no registration");
}

/// The single `vless_reality` registration's builder dispatches by transport
/// sub-mode: classic Reality builds the TCP `VlessReality` backend, `xhttp`
/// builds the multiplexed `Xhttp` backend. This sub-mode split deliberately
/// stays explicit inside the builder rather than becoming a registry key.
#[tokio::test]
async fn relay_transport_registry_dispatches_vless_sub_modes() {
    let mut reality = sample_config("vless_reality");
    vless_config_mut(&mut reality).reality_public_key = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=".to_string();
    match build_backend(&reality).await.expect("vless reality_tcp backend") {
        RelayBackend::VlessReality(_) => {}
        other => panic!("reality_tcp must build VlessReality, got {:?}", std::mem::discriminant(&other)),
    }

    let mut xhttp = reality.clone();
    vless_config_mut(&mut xhttp).vless_transport = "xhttp".to_string();
    match build_backend(&xhttp).await.expect("vless xhttp backend") {
        RelayBackend::Xhttp(_) => {}
        other => panic!("xhttp must build Xhttp, got {:?}", std::mem::discriminant(&other)),
    }
}

/// Pins `planned_backend_fallback_mode` and `pool_config_for_backend` for every
/// concrete `RelayKind`. `planned_backend_fallback_mode` now reads each kind's
/// registration `fallback_mode`; `pool_config_for_backend` stays a `match
/// RelayKind` decision -- pool sizing is transport-family tuning that varies by
/// VLESS sub-mode. This test pins both against literal expectations so a wrong
/// value is caught here.
///
/// (`planned_backend_capabilities` and outbound-bind-IP validation are pinned
/// for every kind by `relay_planned_capabilities_are_pinned_for_every_kind`.)
#[test]
fn relay_planned_runtime_policy_is_pinned_for_every_kind() {
    // kind_id, fallback_mode, pool max_active_leases, pool idle_timeout (secs)
    let pinned: [(&str, Option<&str>, usize, u64); 11] = [
        ("hysteria2", None, 64, 45),
        ("tuic_v5", None, 64, 45),
        ("vless_reality", None, 16, 5), // reality_tcp sub-mode
        ("cloudflare_tunnel", None, 48, 20),
        ("chain_relay", None, 16, 5),
        ("masque", None, 64, 45),
        ("shadowtls_v3", None, 16, 5),
        ("trojan", None, 16, 5),
        ("anytls", None, 64, 45),
        ("shadowsocks", None, 16, 5),
        ("naiveproxy", Some("subprocess"), 16, 5),
    ];

    for (kind_id, fallback, leases, idle_secs) in pinned {
        let config = sample_config(kind_id);
        assert_eq!(
            fallback.map(str::to_string),
            planned_backend_fallback_mode(&config),
            "planned fallback mode drifted for {kind_id}",
        );
        let pool = pool_config_for_backend(&config);
        assert_eq!(leases, pool.max_active_leases, "pool leases drifted for {kind_id}");
        assert_eq!(Duration::from_secs(idle_secs), pool.idle_timeout, "pool idle timeout drifted for {kind_id}");
    }

    // VLESS Reality's `xhttp` sub-mode keeps the capability-bearing (None)
    // fallback mode but takes the xhttp-family pool policy -- a `match
    // RelayKind` sub-mode decision that the single `vless_reality` descriptor
    // cannot express.
    let mut vless_xhttp = sample_config("vless_reality");
    vless_config_mut(&mut vless_xhttp).vless_transport = "xhttp".to_string();
    assert_eq!(None, planned_backend_fallback_mode(&vless_xhttp));
    let xhttp_pool = pool_config_for_backend(&vless_xhttp);
    assert_eq!(48, xhttp_pool.max_active_leases, "vless xhttp pool leases");
    assert_eq!(Duration::from_secs(20), xhttp_pool.idle_timeout, "vless xhttp pool idle timeout");

    // The Unsupported catch-all reports an `unsupported:<kind>` fallback and the
    // default pool config.
    let unsupported = sample_config("totally_unknown");
    assert_eq!(
        Some("unsupported:totally_unknown".to_string()),
        planned_backend_fallback_mode(&unsupported),
        "unsupported relay kind must report an unsupported fallback mode",
    );
    let default_pool = pool_config_for_backend(&unsupported);
    assert_eq!(RelayPoolConfig::default().max_active_leases, default_pool.max_active_leases);
    assert_eq!(RelayPoolConfig::default().idle_timeout, default_pool.idle_timeout);
}

// --- schemaVersion envelope tests ---

/// A minimal valid relay config JSON object, derived from `sample_config`.
/// The `schemaVersion` key is removed so the legacy-payload case is exercised.
fn relay_config_json_object() -> serde_json::Map<String, serde_json::Value> {
    let mut value = serde_json::to_value(sample_config("hysteria2")).expect("serialize relay config");
    let object = value.as_object_mut().expect("relay config object");
    object.remove("schemaVersion");
    object.clone()
}

#[test]
fn legacy_payload_without_schema_version_defaults_to_current_version() {
    let object = relay_config_json_object();
    assert!(!object.contains_key("schemaVersion"), "legacy payload must not carry schemaVersion");

    let config: ResolvedRelayRuntimeConfig = serde_json::from_value(serde_json::Value::Object(object))
        .expect("legacy payload without schemaVersion should deserialize");

    assert_eq!("hysteria2", config.kind_id());
    // The flat form re-serializes with the defaulted `schemaVersion`.
    let reserialized = serde_json::to_value(&config).expect("reserialize relay config");
    assert_eq!(reserialized["schemaVersion"], serde_json::json!(5), "absent schemaVersion defaults to 5");
}

#[test]
fn payload_with_explicit_schema_version_five_deserializes() {
    let mut object = relay_config_json_object();
    object.insert("schemaVersion".to_string(), serde_json::json!(5));

    let config: ResolvedRelayRuntimeConfig = serde_json::from_value(serde_json::Value::Object(object))
        .expect("payload with schemaVersion 5 should deserialize");

    assert_eq!("hysteria2", config.kind_id());
}

#[test]
fn payload_with_unsupported_schema_version_is_rejected() {
    let mut object = relay_config_json_object();
    object.insert("schemaVersion".to_string(), serde_json::json!(6));

    let err = serde_json::from_value::<ResolvedRelayRuntimeConfig>(serde_json::Value::Object(object))
        .expect_err("payload with schemaVersion 6 should be rejected");

    assert!(
        err.to_string().contains("unsupported native config schemaVersion 6"),
        "error should name the found version, got: {err}"
    );
}
