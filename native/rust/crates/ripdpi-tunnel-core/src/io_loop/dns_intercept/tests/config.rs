use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use hickory_proto::op::{Message, MessageType, OpCode, Query};
use hickory_proto::rr::rdata::AAAA;
use hickory_proto::rr::{Name, RData, Record, RecordType};
use ripdpi_dns_resolver::{EncryptedDnsProtocol, EncryptedDnsSocks5Credentials, EncryptedDnsTransport};

use super::super::protect_hooks::encrypted_dns_connect_hooks;
use super::super::{build_encrypted_dns_resolver, mapdns_resolver_transport, parse_dns_cache, parse_mapdns_runtime};
use super::support::{empty_split_dns_policy, mapdns_config, tunnel_config_with_mapdns};

#[test]
fn parse_mapdns_runtime_uses_address_defaults_for_network_and_mask() {
    let mut mapdns = mapdns_config(8);
    mapdns.port = 5300;
    let config = tunnel_config_with_mapdns(Some(mapdns));

    let runtime = parse_mapdns_runtime(&config).expect("runtime").expect("mapdns runtime");

    assert_eq!(runtime.intercept_addr, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 5300));
    assert_eq!(runtime.synthetic_net, u32::from(Ipv4Addr::new(198, 18, 0, 0)));
    assert_eq!(runtime.synthetic_mask, u32::from(Ipv4Addr::new(255, 254, 0, 0)));
    assert_eq!(runtime.intercept_port, 5300);
}

#[test]
fn parse_mapdns_runtime_normalizes_explicit_network_with_host_bits() {
    let mut mapdns = mapdns_config(8);
    mapdns.port = 5300;
    mapdns.network = Some("100.64.0.123".to_string());
    mapdns.netmask = Some("255.192.0.0".to_string());
    let config = tunnel_config_with_mapdns(Some(mapdns));

    let runtime = parse_mapdns_runtime(&config).expect("runtime").expect("mapdns runtime");

    assert_eq!(runtime.synthetic_net, u32::from(Ipv4Addr::new(100, 64, 0, 0)));
    assert_eq!(runtime.synthetic_mask, u32::from(Ipv4Addr::new(255, 192, 0, 0)));
}

#[test]
fn parse_dns_cache_rejects_zero_cache_size() {
    let config = tunnel_config_with_mapdns(Some(mapdns_config(0)));

    let Err(err) = parse_dns_cache(&config, None) else {
        panic!("zero cache size should fail");
    };

    assert_eq!(err.kind(), std::io::ErrorKind::InvalidInput);
    assert_eq!(err.to_string(), "mapdns.cache_size must be greater than zero");
}

#[test]
fn parse_dns_cache_preserves_aaaa_when_tunnel_ipv6_is_enabled() {
    let mut config = tunnel_config_with_mapdns(Some(mapdns_config(8)));
    config.tunnel.ipv6 = Some("fd00::1/128".to_string());
    let mut cache = parse_dns_cache(&config, None).expect("cache parses").expect("MapDNS cache");
    let name = Name::from_ascii("fixture.test").expect("name");
    let mut query = Message::new(0x1234, MessageType::Query, OpCode::Query);
    query.add_query(Query::query(name.clone(), RecordType::AAAA));
    let query = query.to_vec().expect("query encodes");
    let mut upstream = Message::response(0x1234, OpCode::Query);
    upstream.add_query(Query::query(name.clone(), RecordType::AAAA));
    upstream.add_answer(Record::from_rdata(
        name,
        120,
        RData::AAAA(AAAA("2001:db8::10".parse().expect("IPv6 address"))),
    ));

    let rewritten =
        cache.rewrite_response(&query, &upstream.to_vec().expect("response encodes")).expect("response rewrites");
    let rewritten = Message::from_vec(&rewritten.response).expect("rewritten response parses");

    assert!(
        rewritten.answers.iter().any(|record| matches!(&record.data, RData::AAAA(_))),
        "an IPv6-capable tunnel must retain AAAA answers",
    );
}

#[test]
fn build_encrypted_dns_resolver_uses_doh_url_defaults() {
    let mut mapdns = mapdns_config(16);
    mapdns.resolver_id = Some("fixture".to_string());
    mapdns.encrypted_dns_bootstrap_ips = vec!["1.1.1.1".to_string(), "1.0.0.1".to_string()];
    mapdns.encrypted_dns_doh_url = Some("https://dns.example.test/dns-query".to_string());
    mapdns.dns_query_timeout_ms = 2500;
    let config = tunnel_config_with_mapdns(Some(mapdns));

    let resolver = build_encrypted_dns_resolver(&config).expect("resolver build").expect("resolver");
    let endpoint = resolver.endpoint();

    assert_eq!(endpoint.protocol, EncryptedDnsProtocol::Doh);
    assert_eq!(endpoint.host, "dns.example.test");
    assert_eq!(endpoint.port, 443);
    assert_eq!(endpoint.tls_server_name.as_deref(), Some("dns.example.test"));
    assert_eq!(
        endpoint.bootstrap_ips,
        vec![IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), IpAddr::V4(Ipv4Addr::new(1, 0, 0, 1))],
    );
    assert_eq!(endpoint.doh_url.as_deref(), Some("https://dns.example.test/dns-query"));
}

#[test]
fn mapdns_resolver_transport_uses_local_socks5_when_relay_dns_is_active() {
    let mut mapdns = mapdns_config(16);
    mapdns.route_dns_through_socks5 = true;
    let mut config = tunnel_config_with_mapdns(Some(mapdns.clone()));
    config.socks5.username = Some("ripdpi".to_string());
    config.socks5.password = Some("session-secret".to_string());

    let transport = mapdns_resolver_transport(&config, &mapdns);

    assert_eq!(
        transport,
        EncryptedDnsTransport::Socks5 {
            host: "127.0.0.1".to_string(),
            port: 1080,
            credentials: Some(EncryptedDnsSocks5Credentials {
                username: "ripdpi".to_string(),
                password: "session-secret".to_string(),
            }),
        },
    );
}

#[test]
fn active_split_dns_forces_encrypted_resolver_through_socks5() {
    let mapdns = mapdns_config(16);
    let mut config = tunnel_config_with_mapdns(Some(mapdns.clone()));
    config.split_dns_policy = Some(empty_split_dns_policy());

    assert_eq!(
        mapdns_resolver_transport(&config, &mapdns),
        EncryptedDnsTransport::Socks5 { host: "127.0.0.1".to_string(), port: 1080, credentials: None },
    );
}

#[test]
fn active_split_dns_rejects_doq_over_socks_at_startup() {
    let mut mapdns = mapdns_config(16);
    mapdns.encrypted_dns_protocol = Some("doq".to_string());
    mapdns.encrypted_dns_host = Some("dns.example.test".to_string());
    mapdns.encrypted_dns_port = Some(853);
    mapdns.encrypted_dns_bootstrap_ips = vec!["192.0.2.53".to_string()];
    let mut config = tunnel_config_with_mapdns(Some(mapdns));
    config.split_dns_policy = Some(empty_split_dns_policy());

    let error = build_encrypted_dns_resolver(&config).expect_err("DoQ over SOCKS must fail during setup");

    assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
    assert_eq!(error.to_string(), "DoQ over SOCKS5 is not supported");
}

#[test]
fn build_encrypted_dns_resolver_uses_odoh_config_and_socks_transport() {
    let mut mapdns = mapdns_config(16);
    mapdns.resolver_id = Some("fixture-odoh".to_string());
    mapdns.encrypted_dns_protocol = Some("odoh".to_string());
    mapdns.encrypted_dns_bootstrap_ips = vec!["127.0.0.1".to_string()];
    mapdns.encrypted_dns_odoh_proxy_url = Some("https://proxy.example.test/proxy".to_string());
    mapdns.encrypted_dns_odoh_proxy_operator_id = Some("ProxyNet".to_string());
    mapdns.encrypted_dns_odoh_target_host = Some("target.example.test".to_string());
    mapdns.encrypted_dns_odoh_target_path = Some("/dns-query".to_string());
    mapdns.encrypted_dns_odoh_target_operator_id = Some("TargetNet".to_string());
    mapdns.encrypted_dns_odoh_config_source = Some("custom_bytes".to_string());
    mapdns.encrypted_dns_odoh_configs_hex = Some(local_network_fixture::ODOH_TARGET_CONFIGS_HEX.to_string());
    mapdns.encrypted_dns_odoh_configs_retrieved_at_secs = Some(1_700_000_000);
    mapdns.encrypted_dns_odoh_configs_ttl_secs = Some(86_400);
    mapdns.route_dns_through_socks5 = true;
    let config = tunnel_config_with_mapdns(Some(mapdns));

    let resolver = build_encrypted_dns_resolver(&config).expect("resolver build").expect("resolver");
    let endpoint = resolver.endpoint();
    let odoh = endpoint.odoh.as_ref().expect("odoh endpoint config");

    assert_eq!(endpoint.protocol, EncryptedDnsProtocol::Odoh);
    assert_eq!(endpoint.host, "proxy.example.test");
    assert_eq!(endpoint.port, 443);
    assert_eq!(endpoint.tls_server_name.as_deref(), Some("proxy.example.test"));
    assert_eq!(odoh.proxy_url, "https://proxy.example.test/proxy");
    assert_eq!(odoh.proxy_operator_id, "ProxyNet");
    assert_eq!(odoh.target_host, "target.example.test");
    assert_eq!(odoh.target_path, "/dns-query");
    assert_eq!(odoh.target_operator_id, "TargetNet");
    assert_eq!(
        mapdns_resolver_transport(&config, config.mapdns.as_ref().expect("mapdns")),
        EncryptedDnsTransport::Socks5 { host: "127.0.0.1".to_string(), port: 1080, credentials: None }
    );
}

#[test]
fn build_encrypted_dns_resolver_rejects_invalid_tls_roots_pem() {
    let mut mapdns = mapdns_config(16);
    mapdns.encrypted_dns_protocol = Some("dot".to_string());
    mapdns.encrypted_dns_host = Some("fixture.test".to_string());
    mapdns.encrypted_dns_port = Some(853);
    mapdns.encrypted_dns_tls_server_name = Some("fixture.test".to_string());
    mapdns.encrypted_dns_bootstrap_ips = vec!["127.0.0.1".to_string()];
    mapdns.encrypted_dns_tls_roots_pem =
        Some("-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----".to_string());
    let config = tunnel_config_with_mapdns(Some(mapdns));

    let err = build_encrypted_dns_resolver(&config).expect_err("invalid PEM");

    assert_eq!(err.kind(), std::io::ErrorKind::InvalidInput);
    assert!(err.to_string().contains("invalid encrypted DNS TLS root"));
}

#[test]
fn encrypted_dns_connect_hooks_install_protected_connectors() {
    let hooks = encrypted_dns_connect_hooks(Some("/tmp/ripdpi-protect.sock".to_string()));

    assert!(hooks.direct_tcp_connector.is_some());
    assert!(hooks.direct_udp_binder.is_some());
}

#[tokio::test]
async fn android_tun_context_requires_protect() {
    use std::net::{Ipv4Addr, TcpListener};
    use std::time::Duration;

    use ripdpi_dns_resolver::DirectTcpConnection;

    // Serialize against other tests that mutate the process-global protect slot
    // (see crate::PROTECT_CALLBACK_TEST_LOCK) so the no-callback precondition is stable
    // under the multi-threaded `cargo test` runner.
    let _protect_lock = crate::PROTECT_CALLBACK_TEST_LOCK.lock().await;

    // No protect callback is registered and no protect path is supplied: the
    // dns_intercept hooks run under an active TUN, so a non-loopback socket must
    // fail closed rather than dial unprotected (vpnservice-protect-invariant.md).
    assert!(!ripdpi_runtime_platform::protect::has_protect_callback(), "precondition: no protect callback registered",);

    let hooks = encrypted_dns_connect_hooks(None);
    let tcp = hooks.direct_tcp_connector.as_ref().expect("tcp connector installed");
    let udp = hooks.direct_udp_binder.as_ref().expect("udp binder installed");

    // Non-loopback TCP target (TEST-NET-1, never actually dialed) fails closed.
    let non_loopback = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1)), 853);
    let Err(err) = tcp(non_loopback, Duration::from_millis(50)).await else {
        panic!("non-loopback connect must fail closed without protect");
    };
    assert_eq!(err.kind(), std::io::ErrorKind::NotConnected);

    // The UDP binder cannot prove a loopback destination, so it always fails closed.
    let bind_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
    let err = udp(bind_addr).expect_err("udp bind must fail closed without protect");
    assert_eq!(err.kind(), std::io::ErrorKind::NotConnected);

    // A loopback TCP target is exempt and connects without any protect mechanism.
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind loopback listener");
    let loopback = listener.local_addr().expect("loopback addr");
    let connection = tcp(loopback, Duration::from_secs(1)).await.expect("loopback connect is exempt from protect");
    assert!(matches!(connection, DirectTcpConnection::Std(_)));
}
