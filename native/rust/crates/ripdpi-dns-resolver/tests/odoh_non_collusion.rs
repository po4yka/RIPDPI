use std::net::{IpAddr, Ipv4Addr};
use std::time::Duration;

use ripdpi_dns_resolver::{
    EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport,
    OdohConfigSource, OdohEndpointConfig,
};

#[test]
fn odoh_endpoint_rejects_same_operator_proxy_and_target() {
    let error = build_resolver(odoh_endpoint("ExampleNet", " examplenet ")).expect_err("same operator pair rejected");

    assert_invalid_endpoint_contains(error, "non-colluding");
}

#[test]
fn odoh_endpoint_rejects_missing_operator_metadata() {
    let proxy_error = build_resolver(odoh_endpoint(" ", "TargetNet")).expect_err("blank proxy operator rejected");
    assert_invalid_endpoint_contains(proxy_error, "proxy operator");

    let target_error = build_resolver(odoh_endpoint("ProxyNet", "")).expect_err("blank target operator rejected");
    assert_invalid_endpoint_contains(target_error, "target operator");
}

#[test]
fn odoh_endpoint_accepts_distinct_proxy_and_target_operators() {
    build_resolver(odoh_endpoint("ProxyNet", "TargetNet")).expect("distinct ODoH operators accepted");
}

fn build_resolver(endpoint: EncryptedDnsEndpoint) -> Result<EncryptedDnsResolver, EncryptedDnsError> {
    EncryptedDnsResolver::with_timeout(endpoint, EncryptedDnsTransport::Direct, Duration::from_secs(2))
}

fn odoh_endpoint(proxy_operator_id: &str, target_operator_id: &str) -> EncryptedDnsEndpoint {
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Odoh,
        resolver_id: Some("odoh-non-collusion-test".to_string()),
        host: String::new(),
        port: 0,
        tls_server_name: None,
        bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
        doh_url: None,
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
        odoh: Some(OdohEndpointConfig {
            proxy_url: "https://proxy.example/proxy".to_string(),
            proxy_operator_id: proxy_operator_id.to_string(),
            target_host: "target.example".to_string(),
            target_path: "/dns-query".to_string(),
            target_operator_id: target_operator_id.to_string(),
            config_source: OdohConfigSource::Bundled {
                configs_wire: hex::decode(local_network_fixture::ODOH_TARGET_CONFIGS_HEX)
                    .expect("fixture ODoH config hex"),
                retrieved_at_secs: 0,
                ttl_secs: u64::MAX / 2,
            },
        }),
    }
}

fn assert_invalid_endpoint_contains(error: EncryptedDnsError, needle: &str) {
    match error {
        EncryptedDnsError::InvalidEndpoint(message) => assert!(
            message.contains(needle),
            "expected invalid endpoint message to contain {needle:?}, got {message:?}",
        ),
        other => panic!("expected InvalidEndpoint, got {other:?}"),
    }
}
