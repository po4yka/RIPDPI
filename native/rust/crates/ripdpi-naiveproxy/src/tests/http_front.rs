use super::*;

/// Unsupported or malformed HTTP requests must not open an upstream.
///
/// # Cancel safety:
/// cancel-safe: the test runtime owns its sockets, fixture, and listener task.
#[tokio::test]
async fn http_front_rejects_invalid_requests_without_opening_upstream() {
    let fixture = local_network_fixture::NaiveH2PaddingFixture::start("naive-user", "naive-pass")
        .await
        .expect("start naive h2 fixture");
    let local_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let local_addr = local_listener.local_addr().expect("local addr");
    let server = tokio::spawn(serve_listener(local_listener, Arc::new(fixture_config(&fixture))));
    for (request_line, extra_headers, status) in [
        ("GET http://example.com/index.html HTTP/1.1", "", "405"),
        ("CONNECT example.com:443 HTTP/1.xyz", "", "400"),
        ("CONNECT example.com:443 HTTP/1.1 extra", "", "400"),
        ("CONNECT example.com HTTP/1.1", "", "400"),
        ("CONNECT example.com:invalid HTTP/1.1", "", "400"),
        ("CONNECT example.com:65536 HTTP/1.1", "", "400"),
        ("CONNECT user@example.com:443 HTTP/1.1", "", "400"),
        ("CONNECT :443 HTTP/1.1", "", "400"),
        ("CONNECT [example.com]:443 HTTP/1.1", "", "400"),
        ("CONNECT example.com/path:443 HTTP/1.1", "", "400"),
        ("CONNECT example.com:443 HTTP/1.1", "Broken-Header\r\n", "400"),
        ("CONNECT example.com:443 HTTP/1.1", "Bad Header: x\r\n", "400"),
        ("CONNECT example.com:443 HTTP/1.1", "proxy-authorization: Basic bG9jYWwtdXNlcjpsb2NhbC1wYXNz\r\n", "407"),
    ] {
        let mut client = TcpStream::connect(local_addr).await.expect("connect local http front");
        client
            .write_all(
                format!(
                    "{request_line}\r\nProxy-Authorization: Basic bG9jYWwtdXNlcjpsb2NhbC1wYXNz\r\n{extra_headers}\r\n"
                )
                .as_bytes(),
            )
            .await
            .expect("write request");
        let response = tokio::time::timeout(std::time::Duration::from_secs(5), read_http_headers(&mut client))
            .await
            .expect("bounded refusal")
            .expect("response headers");
        assert_eq!(fixture.observed().tls_connections, 0, "a refused request must not reach the upstream");
        assert!(response.starts_with(&format!("HTTP/1.1 {status} ")), "{response}");
    }
    server.abort();
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test runtime drops its owned sockets and tasks when the test ends.
async fn http_front_connect_round_trip_over_h2_naive_padding_fixture() {
    let fixture = local_network_fixture::NaiveH2PaddingFixture::start("naive-user", "naive-pass")
        .await
        .expect("start naive h2 fixture");
    let local_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let local_addr = local_listener.local_addr().expect("local addr");
    let server = tokio::spawn(serve_listener(local_listener, Arc::new(fixture_config(&fixture))));
    let canonical = "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\nProxy-Authorization: Basic bG9jYWwtdXNlcjpsb2NhbC1wYXNz\r\n\r\n";
    let prefix = canonical.strip_suffix("\r\n").expect("empty header line");
    let many_headers = format!("{prefix}{}End: value\r\n\r\n", "X:\n".repeat(700));
    let padding = 16 * 1024 - prefix.len() - "X-Pad: \r\n\r\n".len();
    let at_limit = format!("{prefix}X-Pad: {}\r\n\r\n", "a".repeat(padding));
    assert_eq!(at_limit.len(), 16 * 1024);
    for (head, fragmented, early_payload) in [
        (canonical.to_owned(), false, false),
        (canonical.to_owned(), true, false),
        (canonical.to_owned(), false, true),
        (many_headers, false, true),
        (at_limit, false, true),
        (canonical.replacen("CONNECT", "cOnNeCt", 1).replacen("HTTP/1.1", "HTTP/1.0", 1), false, true),
    ] {
        let mut client = TcpStream::connect(local_addr).await.expect("connect local http front");
        let mut wire = head.into_bytes();
        if early_payload {
            wire.extend_from_slice(b"ping-through-http-front");
        }
        if fragmented {
            for chunk in wire.chunks(7) {
                client.write_all(chunk).await.expect("partial head");
                tokio::task::yield_now().await;
            }
        } else {
            client.write_all(&wire).await.expect("request bytes");
        }
        let response = tokio::time::timeout(std::time::Duration::from_secs(5), read_http_headers(&mut client))
            .await
            .expect("bounded CONNECT response")
            .expect("response headers");
        assert!(response.starts_with("HTTP/1.1 200"), "unexpected CONNECT response: {response:?}");
        if !early_payload {
            client.write_all(b"ping-through-http-front").await.expect("write tunneled payload");
        }
        let mut echoed = vec![0u8; "ping-through-http-front".len()];
        tokio::time::timeout(std::time::Duration::from_secs(5), client.read_exact(&mut echoed))
            .await
            .expect("bounded echo")
            .expect("read echo");
        assert_eq!(&echoed, b"ping-through-http-front");
        let observed = fixture.observed();
        assert_eq!(observed.proxy_authorization.as_deref(), Some("Basic bmFpdmUtdXNlcjpuYWl2ZS1wYXNz"));
        assert_eq!(observed.padding_type_request.as_deref(), Some("1, 0"));
        assert!(observed.request_padding_len.is_some_and(|len| (16..=32).contains(&len)));
    }
    server.abort();
}

/// # Cancel safety:
/// cancel-safe: the test runtime owns the fixture, listener, and both socket ends.
#[tokio::test]
async fn incomplete_and_oversized_http_heads_close_without_upstream() {
    let fixture =
        local_network_fixture::NaiveH2PaddingFixture::start("naive-user", "naive-pass").await.expect("fixture");
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("listener");
    let address = listener.local_addr().expect("address");
    let server = tokio::spawn(serve_listener(listener, Arc::new(fixture_config(&fixture))));
    for request in [
        b"CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Basic bG9jYWwtdXNlcjpsb2NhbC1wYXNz\r\n".to_vec(),
        format!("CONNECT example.com:443 HTTP/1.1\r\nX-Pad: {}\r\n\r\n", "a".repeat(16 * 1024)).into_bytes(),
    ] {
        let mut client = TcpStream::connect(address).await.expect("client");
        client.write_all(&request).await.expect("head");
        if let Err(error) = client.shutdown().await {
            assert!(matches!(
                error.kind(),
                std::io::ErrorKind::ConnectionReset | std::io::ErrorKind::BrokenPipe | std::io::ErrorKind::NotConnected
            ));
        }
        let mut response = Vec::new();
        let result = tokio::time::timeout(std::time::Duration::from_secs(5), client.read_to_end(&mut response))
            .await
            .expect("bounded close");
        if let Err(error) = result {
            assert_eq!(error.kind(), std::io::ErrorKind::ConnectionReset);
        }
        assert!(response.is_empty(), "the existing read-error path closes without a response");
        assert_eq!(fixture.observed().tls_connections, 0);
    }
    server.abort();
}
