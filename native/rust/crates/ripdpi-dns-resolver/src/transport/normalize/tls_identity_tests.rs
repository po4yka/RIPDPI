use super::*;
use std::net::{IpAddr, Ipv4Addr};

fn endpoint() -> EncryptedDnsEndpoint {
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Dot,
        resolver_id: None,
        host: "fixture.test".into(),
        port: 853,
        tls_server_name: None,
        bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
        doh_url: None,
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
        odoh: None,
    }
}

#[test]
fn dns_tls_identity_rejects_unicode_and_normalizes_root_dot_and_ipv6() {
    let mut input = endpoint();
    input.tls_server_name = Some("fixture.test.".into());
    let normalized = normalize_endpoint(input.clone(), &EncryptedDnsTransport::Direct).expect("absolute DNS name");
    assert_eq!(normalized.tls_server_name.as_deref(), Some("fixture.test"));
    input.tls_server_name = Some("bücher.test".into());
    assert!(normalize_endpoint(input.clone(), &EncryptedDnsTransport::Direct).is_err());
    input.tls_server_name = Some("[::1]".into());
    let normalized = normalize_endpoint(input, &EncryptedDnsTransport::Direct).expect("IPv6 identity");
    assert_eq!(normalized.tls_server_name.as_deref(), Some("::1"));
}

#[test]
fn doh_url_identity_uses_idna_and_bare_ip_without_rewriting_the_url() {
    for (url, host) in
        [("https://[::1]:8443/dns-query", "::1"), ("https://bücher.test/dns-query", "xn--bcher-kva.test")]
    {
        let mut input = endpoint();
        input.protocol = EncryptedDnsProtocol::Doh;
        input.host.clear();
        input.doh_url = Some(url.into());
        let normalized = normalize_endpoint(input, &EncryptedDnsTransport::Direct).expect("DoH endpoint");
        assert_eq!(normalized.host, host);
        assert_eq!(normalized.tls_server_name.as_deref(), Some(host));
        assert_eq!(normalized.doh_url.as_deref(), Some(url));
    }
}

#[test]
fn plain_http_doh_is_allowed_only_when_every_target_is_loopback() {
    let mut input = endpoint();
    input.protocol = EncryptedDnsProtocol::Doh;
    input.port = 0;
    input.host.clear();
    input.bootstrap_ips = vec!["127.0.0.1".parse().expect("ip"), "::1".parse().expect("ip")];
    for url in ["http://127.0.0.1:8053/dns-query", "http://[::1]:8053/dns-query", "http://localhost/dns-query"] {
        input.doh_url = Some(url.into());
        assert!(normalize_endpoint(input.clone(), &EncryptedDnsTransport::Direct).is_ok(), "{url}");
    }
    // A loopback URL must not send plaintext to a remote bootstrap IP or connect host.
    let mut remote_bootstrap = input.clone();
    remote_bootstrap.bootstrap_ips.push("9.9.9.9".parse().expect("ip"));
    let mut remote_host = input.clone();
    remote_host.host = "dns.example".into();
    for case in [remote_bootstrap, remote_host] {
        assert!(matches!(
            normalize_endpoint(case, &EncryptedDnsTransport::Direct),
            Err(crate::types::EncryptedDnsError::InvalidUrl(message)) if message.contains("must use https")
        ));
    }
}

#[test]
fn http_reg_names_are_rejected_for_doh_and_https_url_identity_is_required() {
    for protocol in [EncryptedDnsProtocol::Doh, EncryptedDnsProtocol::Odoh] {
        let mut input = endpoint();
        input.protocol = protocol;
        input.host.clear();
        input.tls_server_name = Some("unused TLS override".into());
        let url = "http://_status.example./dns-query";
        if protocol == EncryptedDnsProtocol::Odoh {
            input.odoh = Some(crate::types::OdohEndpointConfig {
                proxy_url: url.into(),
                proxy_operator_id: "proxy".into(),
                target_host: "target.example".into(),
                target_path: "/dns-query".into(),
                target_operator_id: "target".into(),
                config_source: crate::odoh::OdohConfigSource::CustomBytes {
                    configs_wire: Vec::new(),
                    retrieved_at_secs: 0,
                    ttl_secs: 1,
                },
            });
        } else {
            // RFC 8484 section 5: DoH over plain HTTP is rejected unless the host is loopback.
            input.doh_url = Some(url.into());
            assert!(matches!(
                normalize_endpoint(input.clone(), &EncryptedDnsTransport::Direct),
                Err(crate::types::EncryptedDnsError::InvalidUrl(message)) if message.contains("must use https")
            ));
        }
        if protocol == EncryptedDnsProtocol::Odoh {
            let normalized = normalize_endpoint(input.clone(), &EncryptedDnsTransport::Direct).expect("HTTP reg-name");
            assert_eq!(normalized.host, "_status.example.");
            assert_eq!(normalized.tls_server_name.as_deref(), Some("unused TLS override"));
        }
        input.tls_server_name = Some("fixture.test".into());
        if let Some(odoh) = &mut input.odoh {
            odoh.proxy_url = "https://_status.example./dns-query".into();
        } else {
            input.doh_url = Some("https://_status.example./dns-query".into());
        }
        assert!(normalize_endpoint(input, &EncryptedDnsTransport::Direct).is_err());
    }
}

#[test]
fn tls_identity_validation_does_not_restrict_a_separate_connect_host() {
    let mut input = endpoint();
    input.host = "resolver_alias".into();
    input.tls_server_name = Some("fixture.test.".into());
    let normalized = normalize_endpoint(input, &EncryptedDnsTransport::Direct).expect("separate TLS identity");
    assert_eq!(normalized.host, "resolver_alias");
    assert_eq!(normalized.tls_server_name.as_deref(), Some("fixture.test"));
}
