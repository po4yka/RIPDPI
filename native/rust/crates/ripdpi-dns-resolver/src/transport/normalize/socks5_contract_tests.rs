use super::*;
use crate::types::EncryptedDnsSocks5Credentials;

fn endpoint() -> EncryptedDnsEndpoint {
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Dot,
        resolver_id: None,
        host: "fixture.test".into(),
        port: 853,
        tls_server_name: None,
        bootstrap_ips: vec![],
        doh_url: None,
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
        odoh: None,
    }
}

#[test]
fn socks5_credential_byte_bounds_are_validated_at_the_dns_boundary() {
    for (username, password, valid) in [
        (String::new(), "p".into(), false),
        ("u".into(), String::new(), false),
        ("u".repeat(256), "p".into(), false),
        ("u".into(), "é".repeat(128), false),
        ("u".into(), "p".into(), true),
        ("u".repeat(255), format!("{}a", "é".repeat(127)), true),
    ] {
        let transport = EncryptedDnsTransport::Socks5 {
            host: "127.0.0.1".into(),
            port: 1080,
            credentials: Some(EncryptedDnsSocks5Credentials { username, password }),
        };
        assert_eq!(normalize_endpoint(endpoint(), &transport).is_ok(), valid);
    }
}
