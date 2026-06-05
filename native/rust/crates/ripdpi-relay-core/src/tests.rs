use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::Duration;

use android_support::EventRingLayer;
use local_network_fixture::{
    AnyTlsLoopback, AnyTlsLoopbackConfig, ShadowsocksLoopback, TrojanLoopback, VlessRealityLoopback,
    XhttpRealityLoopback,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tracing_subscriber::prelude::*;

mod transport_registry;

use crate::backend::{RelayBackend, build_backend};
use crate::bootstrap::{RelayEndpointBootstrapResolver, bootstrap_relay_endpoints_with};
use crate::config::{
    AnyTlsRelayConfig, ChainRelayConfig, CloudflareTunnelRelayConfig, CommonRelayConfig, Hysteria2RelayConfig,
    MasqueRelayConfig, MieruRelayConfig, NaiveProxyRelayConfig, RelayBackendConfig, ResolvedChainRelayHopConfig,
    ResolvedRelayFinalmaskConfig, ResolvedRelayRuntimeConfig, ResolvedShadowTlsInnerRelayConfig, ShadowTlsRelayConfig,
    ShadowsocksRelayConfig, SshRelayConfig, TorPluggableTransportConfig, TorRelayConfig, TrojanRelayConfig,
    TuicRelayConfig, VlessRealityRelayConfig,
};
use crate::runtime::RelayRuntime;
use crate::runtime_validation::{
    describe_upstream, planned_backend_capabilities, pool_config_for_backend, validate_finalmask_config,
    validate_runtime_config,
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

/// Cross-stack: VLESS-over-xHTTP-over-Reality, single stream. Drives the real
/// `ripdpi-xhttp` client (through the relay VLESS-Reality xHTTP backend) against
/// a loopback that emulates the whole server stack — Reality TLS, the HTTP/2
/// stream-up wire shape, and the VLESS handshake carried in the H2 bodies — then
/// proxies to its echo. A correctness regression in any one layer (Reality TLS,
/// xHTTP framing, or VLESS) breaks this even when the per-crate tests pass.
/// Closes the single-stream cross-stack criterion on
/// `add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality`.
#[tokio::test]
async fn cross_stack_vless_over_xhttp_over_reality_single_stream() {
    let server = XhttpRealityLoopback::start().await.expect("start xhttp/reality fixture");

    let mut config = sample_config("vless_reality");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(server.port());
    config.common.server_name = server.server_name().to_string();
    let vless = vless_config_mut(&mut config);
    vless.vless_transport = "xhttp".to_string();
    vless.reality_public_key = valid_reality_public_key();
    vless.reality_short_id = String::new();
    vless.uuid = Some("11111111-1111-1111-1111-111111111111".to_string());
    vless.xhttp_path = "/tunnel".to_string();

    let backend = build_backend(&config).await.expect("build VLESS-over-xHTTP-over-Reality backend");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), server.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("connect VLESS-over-xHTTP-over-Reality");

    for payload in
        [b"cross-stack xhttp reality payload".as_slice(), b"second round-trip over the same xhttp stream".as_slice()]
    {
        stream.write_all(payload).await.expect("write cross-stack payload");
        let mut echoed = vec![0_u8; payload.len()];
        stream.read_exact(&mut echoed).await.expect("read cross-stack payload");
        assert_eq!(echoed, payload, "VLESS-over-xHTTP-over-Reality must echo byte-for-byte in both directions");
    }
}

/// Chain-relay negative path: the second (exit) hop fails — it cannot reach the
/// final destination because the target port has no listener. The exit hop
/// closes without a VLESS response, so the chained `connect_over` surfaces a
/// recognizable connection-failure error to the caller rather than hanging or
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
    let error = backend
        .connect_tcp(&target)
        .await
        .err()
        .expect("a second-hop failure to reach the destination must surface an error");

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
fn chain_relay_ordered_hops_folds_legacy_entry_exit_when_list_is_empty() {
    let mut config = sample_config("chain_relay");
    let chain = chain_config_mut(&mut config);
    chain.entry_server = "entry.example".to_string();
    chain.entry_server_name = "entry.example".to_string();
    chain.exit_server = "exit.example".to_string();
    chain.exit_server_name = "exit.example".to_string();

    let ordered = chain.ordered_hops();
    assert_eq!(2, ordered.len());
    assert_eq!("entry.example", ordered[0].server);
    assert_eq!("exit.example", ordered[1].server);
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
            vless_transport: "reality_tcp".to_string(),
            vless_uuid: Some("33333333-3333-3333-3333-333333333333".to_string()),
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
            vless_transport: "reality_tcp".to_string(),
            vless_uuid: Some("44444444-4444-4444-4444-444444444444".to_string()),
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

#[tokio::test]
async fn tor_backend_builds_in_process_and_rejects_udp() {
    let mut config = sample_config("tor");
    config.common.udp_enabled = true;

    let backend = build_backend(&config).await.expect("tor backend builds");

    assert_eq!(Some("tor"), relay_backend_kind_id(&backend));
    assert!(!backend.udp_capable(), "Tor is TCP-only");
    let error = validate_runtime_config(&config, &backend).expect_err("Tor backend must reject UDP");
    assert_eq!(io::ErrorKind::Unsupported, error.kind());
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
    let pinned: [(&str, bool, bool, bool, bool); 14] = [
        ("hysteria2", true, true, true, false),
        ("tuic_v5", true, true, true, true),
        ("vless_reality", true, false, false, true),
        ("mieru", true, false, false, false),
        ("ssh", true, false, false, false),
        ("cloudflare_tunnel", true, false, true, true),
        ("chain_relay", true, false, false, true),
        ("masque", true, true, true, false),
        ("shadowtls_v3", true, false, false, true),
        ("trojan", true, true, false, true),
        ("anytls", true, true, true, true),
        ("shadowsocks", true, true, false, true),
        ("tor", true, false, true, false),
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
        RelayBackend::Mieru(_) => Some("mieru"),
        RelayBackend::Ssh(_) => Some("ssh"),
        RelayBackend::ChainRelay { .. } => Some("chain_relay"),
        RelayBackend::Masque(_) => Some("masque"),
        RelayBackend::ShadowTls(_) => Some("shadowtls_v3"),
        RelayBackend::Trojan(_) => Some("trojan"),
        RelayBackend::AnyTls(_) => Some("anytls"),
        RelayBackend::Shadowsocks(_) => Some("shadowsocks"),
        RelayBackend::Tor(_) => Some("tor"),
        RelayBackend::Unsupported { .. } => None,
    }
}
