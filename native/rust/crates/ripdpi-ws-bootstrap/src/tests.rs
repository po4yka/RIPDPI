use std::net::{IpAddr, SocketAddr};
use std::sync::OnceLock;

use local_network_fixture::{FixtureConfig, FixtureStack};
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyEncryptedDnsContext, ProxyRuntimeContext};
use ripdpi_ws_transport_port::TelegramDc;

use crate::catalog::default_encrypted_dns_context;
use crate::policy::runtime_encrypted_dns_context_for_host;
use crate::protect_hooks::build_direct_connect_hooks;
use crate::resolver::{
    encrypted_dns_ip_answers_for_host, resolve_host_via_encrypted_dns_with_default,
    resolve_ws_tunnel_addr_with_default, ws_tunnel_host,
};
use crate::{resolve_host_via_encrypted_dns, resolve_ws_tunnel_addr};

/// Lazily-started fixture shared across all tests in this binary.
///
/// Each test only reads from the fixture (DNS lookups, hostname resolution),
/// so a single shared stack is safe and avoids the workspace-mode socket
/// contention seen when every test spawned its own ~10-server stack.
fn shared_fixture() -> &'static FixtureStack {
    static SHARED: OnceLock<FixtureStack> = OnceLock::new();
    SHARED.get_or_init(|| FixtureStack::start(dynamic_fixture_config()).expect("start fixture"))
}

#[test]
fn resolve_ws_tunnel_addr_uses_runtime_context_when_present() {
    let stack = shared_fixture();
    let runtime_context = fixture_runtime_context(stack.manifest().dns_http_port);

    let addr = resolve_ws_tunnel_addr(TelegramDc::production(3), Some(&runtime_context), None)
        .expect("resolve ws tunnel addr");

    assert_eq!(addr, SocketAddr::new(stack.manifest().dns_answer_ipv4.parse().expect("fixture ip"), 443));
}

#[test]
fn resolve_ws_tunnel_addr_uses_default_context_when_runtime_context_is_absent() {
    let stack = shared_fixture();

    let addr = resolve_ws_tunnel_addr_with_default(TelegramDc::production(2), None, None, || {
        fixture_encrypted_dns_context(stack.manifest().dns_http_port)
    })
    .expect("resolve ws tunnel addr");

    assert_eq!(addr, SocketAddr::new(stack.manifest().dns_answer_ipv4.parse().expect("fixture ip"), 443));
}

#[test]
fn default_encrypted_dns_context_uses_cloudflare_doh() {
    let context = default_encrypted_dns_context();

    assert_eq!(context.resolver_id.as_deref(), Some("cloudflare"));
    assert_eq!(context.host, "cloudflare-dns.com");
    assert_eq!(context.doh_url.as_deref(), Some("https://cloudflare-dns.com/dns-query"));
    assert_eq!(context.bootstrap_ips, vec!["1.1.1.1".to_string(), "1.0.0.1".to_string()]);
}

#[test]
fn build_direct_connect_hooks_only_installs_protected_connectors_when_path_present() {
    let empty = build_direct_connect_hooks(None);
    assert!(empty.direct_tcp_connector.is_none());
    assert!(empty.direct_udp_binder.is_none());
    assert!(empty.dot_tls_connector_builder.is_some());

    let protected = build_direct_connect_hooks(Some("/tmp/ripdpi-protect.sock"));
    assert!(protected.direct_tcp_connector.is_some());
    assert!(protected.direct_udp_binder.is_some());
    assert!(protected.dot_tls_connector_builder.is_some());
}

#[test]
fn resolve_host_via_encrypted_dns_uses_runtime_context_for_regular_runtime_resolution() {
    let stack = shared_fixture();
    let runtime_context = fixture_runtime_context(stack.manifest().dns_http_port);

    let addr =
        resolve_host_via_encrypted_dns("fixture.test", Some(&runtime_context), None, false).expect("resolve host");

    assert_eq!(addr.ip(), stack.manifest().dns_answer_ipv4.parse::<IpAddr>().expect("fixture ip"));
}

#[test]
fn resolve_host_via_encrypted_dns_uses_supplied_default_context_for_fixture_resolution() {
    let stack = shared_fixture();

    let addr = resolve_host_via_encrypted_dns_with_default("fixture.test", None, None, false, || {
        fixture_encrypted_dns_context(stack.manifest().dns_http_port)
    })
    .expect("resolve host");

    assert_eq!(addr.ip(), stack.manifest().dns_answer_ipv4.parse::<IpAddr>().expect("fixture ip"));
}

#[test]
fn encrypted_dns_ip_answers_return_policy_label_and_parsed_addresses() {
    let stack = shared_fixture();
    let runtime_context = fixture_runtime_context(stack.manifest().dns_http_port);

    let answer_set =
        encrypted_dns_ip_answers_for_host("fixture.test", Some(&runtime_context), None).expect("resolve answers");

    assert_eq!(answer_set.label, format!("http://127.0.0.1:{}/dns-query", stack.manifest().dns_http_port));
    assert_eq!(answer_set.answers, vec![stack.manifest().dns_answer_ipv4.parse::<IpAddr>().expect("fixture ip")]);
}

#[test]
fn authority_dns_hint_selects_primary_doh_context() {
    let mut runtime_context = fixture_runtime_context(443);
    runtime_context.direct_path_capabilities = vec![ProxyDirectPathCapability {
        authority: "fixture.test:443".to_string(),
        dns_mode: "DOH_PRIMARY".to_string(),
        ..fixture_direct_path_capability("fixture.test:443")
    }];

    let context = runtime_encrypted_dns_context_for_host("fixture.test", Some(&runtime_context));

    assert_eq!(context.resolver_id.as_deref(), Some("adguard"));
    assert_eq!(context.protocol, "doh");
    assert_eq!(context.host, "dns.adguard-dns.com");
    assert_eq!(context.doh_url.as_deref(), Some("https://dns.adguard-dns.com/dns-query"));
}

#[test]
fn authority_transport_hint_downgrades_doq_when_udp_not_clean() {
    let runtime_context = ProxyRuntimeContext {
        encrypted_dns: Some(ProxyEncryptedDnsContext {
            resolver_id: Some("fixture-doq".to_string()),
            protocol: "doq".to_string(),
            host: "dns.example".to_string(),
            port: 853,
            tls_server_name: Some("dns.example".to_string()),
            bootstrap_ips: vec!["203.0.113.53".to_string()],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }),
        protect_path: None,
        preferred_edges: std::collections::BTreeMap::default(),
        direct_path_capabilities: vec![ProxyDirectPathCapability {
            authority: "fixture.test:443".to_string(),
            quic_usable: Some(false),
            udp_usable: Some(false),
            quic_mode: "SOFT_DISABLE".to_string(),
            ..fixture_direct_path_capability("fixture.test:443")
        }],
        morph_policy: None,
        connection_concurrency: None,
    };

    let context = runtime_encrypted_dns_context_for_host("fixture.test", Some(&runtime_context));

    assert_eq!(context.protocol, "doh");
    assert_eq!(context.port, 443);
    assert_eq!(context.doh_url.as_deref(), Some("https://dns.example/dns-query"));
}

#[test]
fn ws_tunnel_host_supports_test_gateways() {
    assert_eq!(ws_tunnel_host(TelegramDc::from_raw(10_004).expect("test dc")), "kws4-test.web.telegram.org");
}

fn fixture_runtime_context(dns_http_port: u16) -> ProxyRuntimeContext {
    ProxyRuntimeContext {
        encrypted_dns: Some(fixture_encrypted_dns_context(dns_http_port)),
        protect_path: None,
        preferred_edges: std::collections::BTreeMap::default(),
        direct_path_capabilities: Vec::new(),
        morph_policy: None,
        connection_concurrency: None,
    }
}

fn fixture_encrypted_dns_context(dns_http_port: u16) -> ProxyEncryptedDnsContext {
    ProxyEncryptedDnsContext {
        resolver_id: Some("fixture-doh".to_string()),
        protocol: "doh".to_string(),
        host: "127.0.0.1".to_string(),
        port: dns_http_port,
        tls_server_name: None,
        bootstrap_ips: vec!["127.0.0.1".to_string()],
        doh_url: Some(format!("http://127.0.0.1:{dns_http_port}/dns-query")),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    }
}

fn fixture_direct_path_capability(authority: &str) -> ProxyDirectPathCapability {
    ProxyDirectPathCapability {
        authority: authority.to_string(),
        quic_usable: None,
        udp_usable: None,
        fallback_required: None,
        repeated_handshake_failure_class: None,
        transport_policy_version: 0,
        ip_set_digest: String::new(),
        dns_classification: None,
        quic_mode: "ALLOW".to_string(),
        preferred_stack: "H3".to_string(),
        dns_mode: "SYSTEM".to_string(),
        tcp_family: "NONE".to_string(),
        outcome: "TRANSPARENT_OK".to_string(),
        transport_class: None,
        reason_code: None,
        cooldown_until: None,
        updated_at: 0,
    }
}

fn dynamic_fixture_config() -> FixtureConfig {
    FixtureConfig {
        tcp_echo_port: 0,
        udp_echo_port: 0,
        tls_echo_port: 0,
        dns_udp_port: 0,
        dns_http_port: 0,
        dns_dot_port: 0,
        dns_dnscrypt_port: 0,
        dns_doq_port: 0,
        dns_odoh_proxy_port: 0,
        dns_odoh_target_port: 0,
        socks5_port: 0,
        control_port: 0,
        ..FixtureConfig::default()
    }
}
