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

fn build_with(
    edit: impl FnOnce(&mut EncryptedDnsEndpoint, &mut OdohEndpointConfig),
) -> Result<EncryptedDnsResolver, EncryptedDnsError> {
    let mut endpoint = odoh_endpoint("ProxyNet", "TargetNet");
    let mut odoh = endpoint.odoh.take().expect("odoh config");
    edit(&mut endpoint, &mut odoh);
    endpoint.odoh = Some(odoh);
    build_resolver(endpoint)
}

#[test]
fn odoh_proxy_url_must_be_https_unless_fully_loopback() {
    let error = build_with(|endpoint, odoh| {
        odoh.proxy_url = "http://odoh-proxy.example/dns-query".into();
        endpoint.bootstrap_ips = vec![IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10))];
    })
    .expect_err("plain http proxy rejected");
    assert!(matches!(error, EncryptedDnsError::InvalidUrl(ref m) if m.contains("https")), "{error:?}");

    // A loopback URL host is not enough when the connection goes to a remote bootstrap IP.
    let error = build_with(|endpoint, odoh| {
        odoh.proxy_url = "http://127.0.0.1:8053/p".into();
        endpoint.bootstrap_ips = vec![IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10))];
    })
    .expect_err("loopback URL with remote bootstrap rejected");
    assert!(matches!(error, EncryptedDnsError::InvalidUrl(ref m) if m.contains("https")), "{error:?}");

    build_with(|_, odoh| {
        odoh.proxy_url = "http://127.0.0.1:8053/p".into();
        odoh.target_host = "127.0.0.1:9053".into();
    })
    .expect("loopback fixture builds");
}

#[test]
fn odoh_proxy_url_must_not_carry_template_or_target_variables() {
    for (url, needle) in [
        ("https://proxy.example/dns-query?targethost=evil.example", "targethost or targetpath"),
        ("https://proxy.example/dns-query?x=1&targetpath=%2Fx", "targethost or targetpath"),
        ("https://proxy.example/dns-query?%74argethost=evil.example", "targethost or targetpath"),
        ("https://proxy.example/dns-query{?targethost,targetpath}", "template"),
        ("https://proxy.example/{x}", "template"),
    ] {
        let error = build_with(|_, odoh| odoh.proxy_url = url.into()).expect_err(url);
        assert!(matches!(error, EncryptedDnsError::InvalidUrl(ref m) if m.contains(needle)), "{url}: {error:?}");
    }
}

#[test]
fn odoh_target_host_and_path_are_validated() {
    let error = build_with(|_, odoh| odoh.target_path = "dns-query".into()).expect_err("path without slash");
    assert_invalid_endpoint_contains(error, "target path");
    let error = build_with(|_, odoh| odoh.target_host = "  ".into()).expect_err("blank target host");
    assert_invalid_endpoint_contains(error, "target host");
    for host in
        ["same.example@other.example", "target.example/x", "target.example?x", "target.example#x", " target.example"]
    {
        let error = build_with(|_, odoh| odoh.target_host = host.into()).expect_err(host);
        assert_invalid_endpoint_contains(error, "host[:port]");
    }
    build_with(|_, odoh| odoh.target_host = "[2001:db8::1]:8443".into()).expect("bracketed IPv6 target");
}

#[test]
fn odoh_proxy_and_target_must_be_different_hosts() {
    for (proxy, target) in
        [("https://Same.Example/p", "same.example:8443"), ("https://same.example./p", "same.example")]
    {
        let error = build_with(|_, odoh| {
            odoh.proxy_url = proxy.into();
            odoh.target_host = target.into();
        })
        .expect_err(proxy);
        assert_invalid_endpoint_contains(error, "different hosts");
    }
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
