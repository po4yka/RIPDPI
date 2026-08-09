use std::net::{IpAddr, Ipv4Addr};
use std::time::Duration;

use hickory_proto::op::{Message, MessageType, OpCode, Query};
use hickory_proto::rr::{Name, RecordType};
use local_network_fixture::{FixtureConfig, FixtureStack};
use ripdpi_dns_resolver::{
    EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport, ODOH_MESSAGE_MEDIA_TYPE,
    OdohConfigSource, OdohEndpointConfig, extract_ip_answers,
};

#[test]
fn odoh_exchange_posts_oblivious_body_to_proxy_and_decrypts_target_response() {
    let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture stack");
    let manifest = stack.manifest();
    let query = dns_query(&manifest.fixture_domain);

    let resolver = EncryptedDnsResolver::with_timeout(
        odoh_endpoint(manifest.bind_host.as_str(), manifest.dns_odoh_proxy_port, manifest.dns_odoh_target_port),
        EncryptedDnsTransport::Direct,
        Duration::from_secs(2),
    )
    .expect("build ODoH resolver");

    let response = resolver.exchange_blocking(&query).expect("ODoH exchange");

    assert_eq!(extract_ip_answers(&response).expect("ODoH answers"), vec![manifest.dns_answer_ipv4.clone()]);
    let events = stack.events().snapshot();
    let proxy = events.iter().find(|event| event.service == "dns_odoh_proxy").expect("proxy event");
    assert!(proxy.detail.contains("targethost=127.0.0.1:"));
    assert!(proxy.detail.contains("targetpath=/dns-query"));
    assert!(proxy.detail.contains(ODOH_MESSAGE_MEDIA_TYPE));
    assert!(!proxy.detail.contains(&manifest.fixture_domain));
    assert!(events.iter().any(|event| event.service == "dns_odoh_target" && event.detail == manifest.fixture_domain));
}

#[test]
fn odoh_exchange_uses_socks_transport_for_proxy_leg_when_configured() {
    let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture stack");
    let manifest = stack.manifest();
    let query = dns_query(&manifest.fixture_domain);

    let resolver = EncryptedDnsResolver::with_timeout(
        odoh_endpoint(manifest.bind_host.as_str(), manifest.dns_odoh_proxy_port, manifest.dns_odoh_target_port),
        EncryptedDnsTransport::Socks5 {
            host: manifest.bind_host.clone(),
            port: manifest.socks5_port,
            credentials: None,
        },
        Duration::from_secs(2),
    )
    .expect("build ODoH resolver");

    let response = resolver.exchange_blocking(&query).expect("ODoH exchange through SOCKS");

    assert_eq!(extract_ip_answers(&response).expect("ODoH answers"), vec![manifest.dns_answer_ipv4.clone()]);
    let events = stack.events().snapshot();
    assert!(events.iter().any(|event| event.service == "socks5_relay" && event.protocol == "tcp"));
    assert!(events.iter().any(|event| event.service == "dns_odoh_proxy"));
    assert!(events.iter().any(|event| event.service == "dns_odoh_target"));
}

fn odoh_endpoint(bind_host: &str, proxy_port: u16, target_port: u16) -> EncryptedDnsEndpoint {
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Odoh,
        resolver_id: Some("fixture-odoh".to_string()),
        host: String::new(),
        port: 0,
        tls_server_name: None,
        bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
        doh_url: None,
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
        odoh: Some(OdohEndpointConfig {
            proxy_url: format!("http://{bind_host}:{proxy_port}/proxy"),
            proxy_operator_id: "fixture-proxy".to_string(),
            target_host: format!("{bind_host}:{target_port}"),
            target_path: "/dns-query".to_string(),
            target_operator_id: "fixture-target".to_string(),
            config_source: OdohConfigSource::Bundled {
                configs_wire: hex::decode(local_network_fixture::ODOH_TARGET_CONFIGS_HEX)
                    .expect("fixture ODoH config hex"),
                retrieved_at_secs: 0,
                ttl_secs: u64::MAX / 2,
            },
        }),
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

fn dns_query(name: &str) -> Vec<u8> {
    let mut message = Message::new(42, MessageType::Query, OpCode::Query);
    message.metadata.recursion_desired = true;
    message.add_query(Query::new(Name::from_ascii(name).expect("query name"), RecordType::A));
    message.to_vec().expect("query serializes")
}
