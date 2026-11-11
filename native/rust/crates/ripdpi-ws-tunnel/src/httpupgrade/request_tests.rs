use super::*;

#[test]
fn rejects_invalid_request_target_fields() {
    for (host, path) in [
        ("", "/u"),
        ("two hosts", "/u"),
        ("user@edge.example", "/u"),
        ("edge.example:invalid", "/u"),
        ("edge.example:70000", "/u"),
        ("[not-ip]", "/u"),
        ("foo[bar]", "/u"),
        ("edge.example", "/two words"),
        ("edge.example", "/u#fragment"),
        ("edge.example", "/u\\path"),
    ] {
        assert!(
            matches!(
                build_upgrade_request(&HttpUpgradeConfig::new(host, path)),
                Err(HttpUpgradeError::InvalidConfig(_))
            ),
            "must reject host={host:?}, path={path:?}"
        );
    }
}

#[test]
fn rejects_invalid_upgrade_protocols() {
    for protocol in ["bad protocol", "websocket,h2c", "websocket;parameter", "/v1", "ripdpi/", "ripdpi/v1/v2"] {
        let config = HttpUpgradeConfig::new("edge.example", "/u").with_upgrade_protocol(protocol);
        assert!(
            matches!(build_upgrade_request(&config), Err(HttpUpgradeError::InvalidConfig(_))),
            "must reject {protocol:?}"
        );
    }
}

#[test]
fn rejects_invalid_header_names() {
    for name in ["", "Bad Header", "Bad:Header", ":authority"] {
        let config = HttpUpgradeConfig::new("edge.example", "/u").with_header(name, "value");
        assert!(
            matches!(build_upgrade_request(&config), Err(HttpUpgradeError::InvalidConfig(_))),
            "must reject {name:?}"
        );
    }
}

#[test]
fn rejects_transport_and_body_framing_overrides() {
    for (name, value) in [
        ("Host", "another.example"),
        ("Connection", "close"),
        ("Upgrade", "h2c"),
        ("Content-Length", "12"),
        ("content-length", "0"),
        ("Transfer-Encoding", "chunked"),
    ] {
        let config = HttpUpgradeConfig::new("edge.example", "/u").with_header(name, value);
        assert!(
            matches!(build_upgrade_request(&config), Err(HttpUpgradeError::InvalidConfig(_))),
            "must reject reserved field {name}"
        );
    }
}

#[test]
fn preserves_valid_ipv6_authority_path_and_provider_headers() {
    let config = HttpUpgradeConfig::new("[::1]:8443", "/path%20name?x=%23")
        .with_upgrade_protocol("RIPDPI/v1")
        .with_header("Authorization", "Bearer fixture-token")
        .with_header("X-Route", "first")
        .with_header("X-Route", "second");
    assert_eq!(
        build_upgrade_request(&config).expect("valid head-only request"),
        b"GET /path%20name?x=%23 HTTP/1.1\r\nHost: [::1]:8443\r\nConnection: Upgrade\r\nUpgrade: RIPDPI/v1\r\nAuthorization: Bearer fixture-token\r\nX-Route: first\r\nX-Route: second\r\n\r\n"
    );
}

/// # Cancel safety:
/// cancel-safe: each iteration owns both duplex ends and waits for both futures.
#[tokio::test]
async fn invalid_config_does_not_write_a_partial_request() {
    for config in [
        HttpUpgradeConfig::new("", "/u"),
        HttpUpgradeConfig::new("edge.example", "/two words"),
        HttpUpgradeConfig::new("edge.example", "/u").with_header("Transfer-Encoding", "chunked"),
    ] {
        let (client, mut server) = tokio::io::duplex(1024);
        let peer = async {
            let mut request = [0; 1024];
            let read = server.read(&mut request).await.expect("request bytes or EOF");
            if read > 0 {
                server
                    .write_all(b"HTTP/1.1 101 ok\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n")
                    .await
                    .expect("response");
            }
            read
        };
        let (read, result) = tokio::join!(peer, HttpUpgradeTransport::connect(client, &config));
        assert!(matches!(result, Err(HttpUpgradeError::InvalidConfig(_))));
        assert_eq!(read, 0, "invalid config must not send any request bytes");
    }
}
