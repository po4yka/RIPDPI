use super::*;

#[test]
fn rejects_unconfirmed_protocol_switches() {
    for response in [
        b"HTTP/1.1 101 Switching Protocols\r\n\r\n".as_slice(),
        b"HTTP/1.1 101 Switching Protocols\r\nConnection: close\r\nUpgrade: websocket\r\n\r\n".as_slice(),
        b"HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: h2c\r\n\r\n".as_slice(),
    ] {
        assert!(parse_upgrade_response(response).is_err(), "must reject {response:?}");
    }
}

#[test]
fn rejects_invalid_http_response_syntax() {
    for response in [
        b"HTTP/1garbage 101 ok\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n".as_slice(),
        b"HTTP/1.1 0101 ok\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n".as_slice(),
        b"HTTP/1.1 101 ok\r\nBad Header: value\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n".as_slice(),
        b"HTTP/1.1 101 ok\r\nConnection: Upgrade, bad option\r\nUpgrade: websocket\r\n\r\n".as_slice(),
        b"\r\n\r\n".as_slice(),
    ] {
        assert!(parse_upgrade_response(response).is_err(), "must reject {response:?}");
    }
}

#[test]
fn accepts_repeated_connection_fields_and_keeps_payload() {
    let response =
        b"HTTP/1.1 101 ok\r\nConnection: keep-alive\r\nConnection: close, uPgRaDe\r\nUpgrade: WebSocket\r\n\r\npayload";
    let parsed = parse_upgrade_response(response).expect("valid response").expect("complete");
    assert_eq!(parsed.leftover, b"payload");
}

#[test]
fn waits_for_the_final_response_after_interim_heads() {
    let response = b"HTTP/1.1 103 Early Hints\r\nLink: </style>\r\n\r\nHTTP/1.1 101 ok\r\nConnection: Up";
    assert!(parse_upgrade_response(response).expect("incomplete final head").is_none());
}

#[test]
fn bounds_the_interim_response_count() {
    let final_head = b"HTTP/1.1 101 ok\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n";
    let mut allowed = b"HTTP/1.1 103 Early Hints\r\n\r\n".repeat(8);
    allowed.extend_from_slice(final_head);
    assert!(parse_upgrade_response(&allowed).expect("eight interim heads").is_some());

    let mut excessive = b"HTTP/1.1 103 Early Hints\r\n\r\n".repeat(9);
    excessive.extend_from_slice(final_head);
    assert!(matches!(parse_upgrade_response(&excessive), Err(HttpUpgradeError::MalformedResponse(_))));
}

#[test]
fn applies_one_budget_to_interim_and_final_heads() {
    let mut response = b"HTTP/1.1 103 Early Hints\r\nX-Fill: ".to_vec();
    response.extend(vec![b'x'; 8100]);
    response.extend_from_slice(b"\r\n\r\nHTTP/1.1 101 ok\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n");
    assert!(matches!(parse_upgrade_response(&response), Err(HttpUpgradeError::MalformedResponse(_))));
}

#[test]
fn accepts_the_exact_head_limit_without_counting_payload() {
    let mut response = b"HTTP/1.1 101 ok\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nX-Fill: ".to_vec();
    response.resize(8192 - 4, b'x');
    response.extend_from_slice(b"\r\n\r\n");
    response.extend(vec![b'p'; 16384]);
    assert_eq!(parse_upgrade_response(&response).expect("8 KiB head").expect("complete").leftover, vec![b'p'; 16384]);

    response.insert(8192 - 4, b'x');
    assert!(matches!(parse_upgrade_response(&response), Err(HttpUpgradeError::MalformedResponse(_))));
}

/// # Cancel safety:
/// cancel-safe: the test owns both duplex ends and does not spawn tasks.
#[tokio::test]
async fn connects_after_interim_heads_with_the_configured_protocol() {
    let response = b"HTTP/1.1 103 Early Hints\r\n\r\nHTTP/1.1 101 ok\r\nConnection: keep-alive, Upgrade\r\nUpgrade: ripdpi/Alpha1\r\n\r\nready";
    let (client, server) = tokio::io::duplex(1024);
    let config = HttpUpgradeConfig::new("edge.example", "/upgrade").with_upgrade_protocol("RIPDPI/Alpha1");
    let peer = respond(server, response);
    let client = async {
        let mut transport = HttpUpgradeTransport::connect(client, &config).await.expect("confirmed custom protocol");
        let mut ready = [0; 5];
        transport.read_exact(&mut ready).await.expect("post-upgrade bytes");
        assert_eq!(&ready, b"ready");
    };
    tokio::join!(peer, client);
}

/// # Cancel safety:
/// cancel-safe: each iteration owns and drops both duplex ends.
#[tokio::test]
async fn rejects_unoffered_protocols_and_version_changes() {
    for response in [
        b"HTTP/1.1 101 ok\r\nConnection: Upgrade\r\nUpgrade: ripdpi/alpha1\r\n\r\n".as_slice(),
        b"HTTP/1.1 101 ok\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n".as_slice(),
        b"HTTP/1.1 101 ok\r\nConnection: Upgrade\r\nUpgrade: ripdpi/Alpha1, h2c\r\n\r\n".as_slice(),
    ] {
        let (client, server) = tokio::io::duplex(1024);
        let config = HttpUpgradeConfig::new("edge.example", "/upgrade").with_upgrade_protocol("ripdpi/Alpha1");
        let ((), result) = tokio::join!(respond(server, response), HttpUpgradeTransport::connect(client, &config));
        assert!(matches!(result, Err(HttpUpgradeError::MalformedResponse(_))));
    }
}

/// # Cancel safety:
/// cancel-safe: the helper owns its duplex stream, which is dropped on cancellation.
async fn respond(mut server: tokio::io::DuplexStream, response: &[u8]) {
    let mut request = Vec::new();
    while !request.ends_with(b"\r\n\r\n") {
        request.push(server.read_u8().await.expect("request byte"));
        assert!(request.len() <= 8192);
    }
    server.write_all(response).await.expect("response bytes");
}
