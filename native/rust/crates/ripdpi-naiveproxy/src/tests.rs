use std::ffi::OsString;
use std::net::Ipv4Addr;
use std::sync::Arc;

use crate::config::parse_config_from_reader;
use http_body_util::BodyExt;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

use crate::config::NaiveProxyConfig;
use crate::h2_connect::{
    CONNECT_PADDING_HEADER_MAX, CONNECT_PADDING_HEADER_MIN, PayloadPaddingMode, build_h2_connect_request,
    generate_connect_padding_header, negotiate_payload_padding_from_response,
};
use crate::padding::{MAX_PADDED_FRAMES, PaddingDecoder, PaddingEncoder};
use crate::relay::serve_listener;
use crate::socks5::SocksTarget;
use crate::tls::default_tls_config;

/// # Cancel safety:
/// cancel-safe: this test owns the local client and aborts the listener after the exchange.
#[tokio::test]
async fn local_socks_rejects_no_auth_before_connecting_upstream() {
    let upstream = TcpListener::bind("127.0.0.1:0").await.expect("bind upstream sentinel");
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let address = listener.local_addr().expect("local address");
    let config = NaiveProxyConfig {
        server: "127.0.0.1".to_owned(),
        server_port: upstream.local_addr().expect("upstream address").port(),
        ..naive_config_with_auth()
    };
    let server = tokio::spawn(serve_listener(listener, Arc::new(config)));
    let mut client = TcpStream::connect(address).await.expect("connect local listener");
    client.write_all(&[5, 1, 0]).await.expect("offer no auth");
    let mut reply = [0; 2];
    client.read_exact(&mut reply).await.expect("read auth rejection");
    server.abort();
    assert_eq!(reply, [5, 255]);
    assert!(tokio::time::timeout(std::time::Duration::from_millis(50), upstream.accept()).await.is_err());
}

/// # Cancel safety:
/// cancel-safe: the test runtime owns and drops every socket and spawned task.
#[tokio::test]
async fn invalid_local_credentials_never_open_upstream() {
    let upstream = TcpListener::bind("127.0.0.1:0").await.expect("bind upstream sentinel");
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let address = listener.local_addr().expect("local address");
    let config = NaiveProxyConfig {
        server: "127.0.0.1".to_owned(),
        server_port: upstream.local_addr().expect("upstream address").port(),
        ..naive_config_with_auth()
    };
    let server = tokio::spawn(serve_listener(listener, Arc::new(config)));
    for pair in [b"\x01\x0awrong-user\x0alocal-pass".as_slice(), b"\x01\x0alocal-user\x0awrong-pass"] {
        let mut client = TcpStream::connect(address).await.expect("connect local listener");
        client.write_all(&[5, 1, 2]).await.expect("offer auth");
        let mut reply = [0; 2];
        client.read_exact(&mut reply).await.expect("auth method");
        assert_eq!(reply, [5, 2]);
        client.write_all(pair).await.expect("bad credentials");
        client.read_exact(&mut reply).await.expect("auth rejection");
        assert_eq!(reply, [1, 1]);
    }
    for headers in [
        "",
        "Proxy-Authorization: Basic d3JvbmctdXNlcjpsb2NhbC1wYXNz\r\n",
        "Proxy-Authorization: Basic bG9jYWwtdXNlcjp3cm9uZy1wYXNz\r\n",
        "Proxy-Authorization: Basic not-base64\r\n",
        "Proxy-Authorization: Basic bG9jYWwtdXNlcjpsb2NhbC1wYXNz\r\nProxy-Authorization: Basic bG9jYWwtdXNlcjpsb2NhbC1wYXNz\r\n",
    ] {
        let mut client = TcpStream::connect(address).await.expect("connect local HTTP listener");
        client
            .write_all(format!("CONNECT example.com:443 HTTP/1.1\r\n{headers}\r\n").as_bytes())
            .await
            .expect("send request");
        let reply = read_http_headers(&mut client).await.expect("HTTP auth rejection");
        assert!(reply.starts_with("HTTP/1.1 407"), "{reply}");
    }
    server.abort();
    assert!(tokio::time::timeout(std::time::Duration::from_millis(50), upstream.accept()).await.is_err());
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test runtime drops its owned sockets and tasks when the test ends.
async fn socks5_tunnel_round_trip_reaches_target_via_https_proxy() {
    let fixture = local_network_fixture::NaiveH2PaddingFixture::start("naive-user", "naive-pass")
        .await
        .expect("start naive h2 fixture");
    let local_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let local_addr = local_listener.local_addr().expect("local addr");
    let config = NaiveProxyConfig {
        listen: local_addr.to_string(),
        server: "127.0.0.1".to_owned(),
        server_port: fixture.port(),
        server_name: fixture.server_name().to_owned(),
        username: Some("naive-user".to_owned()),
        password: Some("naive-pass".to_owned()),
        path: None,
        tls_config: fixture.client_tls_config(),
        protect_socket_path: None,
        sessions: Arc::default(),
        local_username: "local-user".to_owned(),
        local_password: "local-pass".to_owned(),
        extra_headers: Vec::new(),
    };

    let server = tokio::spawn(async move {
        serve_listener(local_listener, Arc::new(config)).await.expect("serve listener");
    });

    let mut client = TcpStream::connect(local_addr).await.expect("connect local socks");
    authenticate_local(&mut client).await;

    let connect_request = build_socks_connect_request("127.0.0.1", 443);
    client.write_all(&connect_request).await.expect("write connect request");
    let mut connect_reply = [0u8; 10];
    client.read_exact(&mut connect_reply).await.expect("read connect reply");
    assert_eq!(connect_reply[1], 0x00);

    client.write_all(b"ping-through-naive").await.expect("write tunneled payload");
    let mut echoed = vec![0u8; "ping-through-naive".len()];
    client.read_exact(&mut echoed).await.expect("read echo");
    assert_eq!(&echoed, b"ping-through-naive");

    let observed = fixture.observed();
    assert_eq!(observed.proxy_authorization.as_deref(), Some("Basic bmFpdmUtdXNlcjpuYWl2ZS1wYXNz"));

    server.abort();
}

/// Upstream NaiveProxy multiplexes tunnels on one HTTP/2 connection: so does
/// the helper, for sequential and concurrent tunnels, and each tunnel still
/// carries only its own bytes.
///
/// # Cancel safety:
/// cancel-safe: the test runtime drops its owned sockets and tasks when the test ends.
#[tokio::test]
async fn tunnels_share_one_upstream_connection() {
    let fixture = local_network_fixture::NaiveH2PaddingFixture::start("naive-user", "naive-pass")
        .await
        .expect("start naive h2 fixture");
    let local_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let local_addr = local_listener.local_addr().expect("local addr");
    let config = NaiveProxyConfig {
        server: "127.0.0.1".to_owned(),
        server_port: fixture.port(),
        server_name: fixture.server_name().to_owned(),
        username: Some("naive-user".to_owned()),
        password: Some("naive-pass".to_owned()),
        tls_config: fixture.client_tls_config(),
        ..naive_config_with_auth()
    };
    let server = tokio::spawn(serve_listener(local_listener, Arc::new(config)));

    for round in 0..3 {
        let mut client = open_socks_tunnel(local_addr).await;
        let payload = format!("sequential-{round}");
        client.write_all(payload.as_bytes()).await.expect("write payload");
        let mut echoed = vec![0u8; payload.len()];
        client.read_exact(&mut echoed).await.expect("read echo");
        assert_eq!(echoed, payload.as_bytes());
    }
    let mut first = open_socks_tunnel(local_addr).await;
    let mut second = open_socks_tunnel(local_addr).await;
    first.write_all(b"concurrent-first").await.expect("write first");
    second.write_all(b"concurrent-second-tunnel").await.expect("write second");
    let mut first_echo = [0u8; 16];
    let mut second_echo = [0u8; 24];
    second.read_exact(&mut second_echo).await.expect("read second");
    first.read_exact(&mut first_echo).await.expect("read first");
    assert_eq!(&first_echo, b"concurrent-first");
    assert_eq!(&second_echo, b"concurrent-second-tunnel");

    assert_eq!(fixture.observed().tls_connections, 1, "five tunnels must share one upstream connection");
    server.abort();
}

/// A config for opening tunnels straight to `fixture`.
fn fixture_config(fixture: &local_network_fixture::NaiveH2PaddingFixture) -> NaiveProxyConfig {
    NaiveProxyConfig {
        server: "127.0.0.1".to_owned(),
        server_port: fixture.port(),
        server_name: fixture.server_name().to_owned(),
        username: Some("naive-user".to_owned()),
        password: Some("naive-pass".to_owned()),
        tls_config: fixture.client_tls_config(),
        ..naive_config_with_auth()
    }
}

/// A refused CONNECT (here 407) is the server answering one stream: the
/// shared connection stays for the next tunnel.
///
/// # Cancel safety:
/// cancel-safe: the test owns every tunnel and the fixture.
#[tokio::test]
async fn rejected_connect_keeps_the_shared_connection() {
    let fixture = local_network_fixture::NaiveH2PaddingFixture::start("naive-user", "naive-pass")
        .await
        .expect("start naive h2 fixture");
    let config = fixture_config(&fixture);
    let wrong = NaiveProxyConfig {
        password: Some("wrong-pass".to_owned()),
        sessions: Arc::clone(&config.sessions),
        ..fixture_config(&fixture)
    };
    let target = SocksTarget::Ip("127.0.0.1:443".parse().expect("target"));

    let Err(error) = crate::connect_tunnel::open_https_connect_tunnel(&wrong, &target).await else {
        panic!("a wrong password must be refused");
    };
    assert!(error.to_string().contains("status 407"), "{error}");
    drop(crate::connect_tunnel::open_https_connect_tunnel(&config, &target).await.expect("tunnel after 407"));

    assert_eq!(fixture.observed().tls_connections, 1, "a refused CONNECT must not retire the connection");
}

/// A server that dials before it answers can leave one CONNECT pending: an
/// abandoned open must not retire a connection that already answered.
///
/// # Cancel safety:
/// cancel-safe: the test owns every tunnel and aborts the server task.
#[tokio::test]
async fn abandoned_slow_connect_keeps_a_verified_connection() {
    let (server_config, client_config) = self_signed_upstream_tls();
    let upstream = TcpListener::bind("127.0.0.1:0").await.expect("bind upstream");
    let upstream_port = upstream.local_addr().expect("upstream addr").port();
    let accepts = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let server_accepts = Arc::clone(&accepts);
    let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(server_config));
    let server = tokio::spawn(async move {
        loop {
            let (socket, _) = upstream.accept().await.expect("accept upstream");
            let Ok(tls) = acceptor.accept(socket).await else { continue };
            server_accepts.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            tokio::spawn(async move {
                let service = hyper::service::service_fn(|request: http::Request<hyper::body::Incoming>| async move {
                    if request.uri().host() == Some("slow.test") {
                        std::future::pending::<()>().await;
                    }
                    Ok::<_, std::convert::Infallible>(http::Response::new(http_body_util::Empty::<bytes::Bytes>::new()))
                });
                let _ = hyper::server::conn::http2::Builder::new(hyper_util::rt::TokioExecutor::new())
                    .serve_connection(hyper_util::rt::TokioIo::new(tls), service)
                    .await;
            });
        }
    });
    let config = NaiveProxyConfig {
        server: "127.0.0.1".to_owned(),
        server_port: upstream_port,
        server_name: "proxy.example".to_owned(),
        tls_config: Arc::new(client_config),
        ..naive_config_with_auth()
    };
    let fast = SocksTarget::Domain("fast.test".to_owned(), 443);
    let slow = SocksTarget::Domain("slow.test".to_owned(), 443);

    drop(crate::connect_tunnel::open_https_connect_tunnel(&config, &fast).await.expect("fast tunnel"));
    let abandoned = tokio::time::timeout(
        std::time::Duration::from_millis(300),
        crate::connect_tunnel::open_https_connect_tunnel(&config, &slow),
    )
    .await;
    assert!(abandoned.is_err(), "the slow CONNECT must still be pending");
    drop(crate::connect_tunnel::open_https_connect_tunnel(&config, &fast).await.expect("fast tunnel after"));

    assert_eq!(accepts.load(std::sync::atomic::Ordering::Relaxed), 1, "the verified connection must stay");
    server.abort();
}

/// A target the CONNECT request cannot carry fails only its own stream.
///
/// # Cancel safety:
/// cancel-safe: the test owns every tunnel and the fixture.
#[tokio::test]
async fn invalid_target_keeps_the_shared_connection() {
    let fixture = local_network_fixture::NaiveH2PaddingFixture::start("naive-user", "naive-pass")
        .await
        .expect("start naive h2 fixture");
    let config = fixture_config(&fixture);
    let target = SocksTarget::Ip("127.0.0.1:443".parse().expect("target"));
    drop(crate::connect_tunnel::open_https_connect_tunnel(&config, &target).await.expect("first tunnel"));

    let invalid = SocksTarget::Domain("bad host".to_owned(), 443);
    assert!(crate::connect_tunnel::open_https_connect_tunnel(&config, &invalid).await.is_err());
    drop(crate::connect_tunnel::open_https_connect_tunnel(&config, &target).await.expect("tunnel after invalid"));

    assert_eq!(fixture.observed().tls_connections, 1, "an invalid target must not retire the connection");
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test runtime drops its owned sockets and tasks when the test ends.
async fn socks5_client_round_trip_over_h2_naive_padding_fixture() {
    let fixture = local_network_fixture::NaiveH2PaddingFixture::start("naive-user", "naive-pass")
        .await
        .expect("start naive h2 fixture");
    let local_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let local_addr = local_listener.local_addr().expect("local addr");
    let config = NaiveProxyConfig {
        listen: local_addr.to_string(),
        server: "127.0.0.1".to_owned(),
        server_port: fixture.port(),
        server_name: fixture.server_name().to_owned(),
        username: Some("naive-user".to_owned()),
        password: Some("naive-pass".to_owned()),
        path: None,
        tls_config: fixture.client_tls_config(),
        protect_socket_path: None,
        sessions: Arc::default(),
        local_username: "local-user".to_owned(),
        local_password: "local-pass".to_owned(),
        extra_headers: Vec::new(),
    };

    let server = tokio::spawn(async move {
        serve_listener(local_listener, Arc::new(config)).await.expect("serve listener");
    });

    let mut client = TcpStream::connect(local_addr).await.expect("connect local socks");
    authenticate_local(&mut client).await;

    let connect_request = build_socks_connect_request("127.0.0.1", 443);
    client.write_all(&connect_request).await.expect("write connect request");
    let mut connect_reply = [0u8; 10];
    client.read_exact(&mut connect_reply).await.expect("read connect reply");
    assert_eq!(connect_reply[1], 0x00);

    client.write_all(b"ping-through-h2-naive").await.expect("write tunneled payload");
    let mut echoed = vec![0u8; "ping-through-h2-naive".len()];
    client.read_exact(&mut echoed).await.expect("read echo");
    assert_eq!(&echoed, b"ping-through-h2-naive");

    let observed = fixture.observed();
    assert_eq!(observed.proxy_authorization.as_deref(), Some("Basic bmFpdmUtdXNlcjpuYWl2ZS1wYXNz"));
    assert_eq!(observed.padding_type_request.as_deref(), Some("1, 0"));
    assert!(observed.request_padding_len.is_some_and(|len| (16..=32).contains(&len)));
    assert!(observed.decoded_payload_bytes >= "ping-through-h2-naive".len());

    server.abort();
}

/// A plain absolute-form request through the HTTP front reaches the target
/// in origin form, without the local credentials. The fixture echoes, so the
/// client reads back exactly what the origin received.
///
/// # Cancel safety:
/// cancel-safe: the test owns its sockets and aborts the listener.
#[tokio::test]
async fn http_front_forwards_plain_requests_in_origin_form() {
    let fixture = local_network_fixture::NaiveH2PaddingFixture::start("naive-user", "naive-pass")
        .await
        .expect("start naive h2 fixture");
    let local_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let local_addr = local_listener.local_addr().expect("local addr");
    let server = tokio::spawn(serve_listener(local_listener, Arc::new(fixture_config(&fixture))));

    let mut client = TcpStream::connect(local_addr).await.expect("connect local http front");
    client
        .write_all(b"GET http://example.com/index.html HTTP/1.1\r\nHost: example.com\r\nProxy-Authorization: Basic bG9jYWwtdXNlcjpsb2NhbC1wYXNz\r\n\r\n")
        .await
        .expect("write plain request");
    let expected = b"GET /index.html HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n";
    let mut received = vec![0_u8; expected.len()];
    client.read_exact(&mut received).await.expect("read what the origin received");

    assert_eq!(String::from_utf8_lossy(&received), String::from_utf8_lossy(expected));
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
    let config = NaiveProxyConfig {
        listen: local_addr.to_string(),
        server: "127.0.0.1".to_owned(),
        server_port: fixture.port(),
        server_name: fixture.server_name().to_owned(),
        username: Some("naive-user".to_owned()),
        password: Some("naive-pass".to_owned()),
        path: None,
        tls_config: fixture.client_tls_config(),
        protect_socket_path: None,
        sessions: Arc::default(),
        local_username: "local-user".to_owned(),
        local_password: "local-pass".to_owned(),
        extra_headers: Vec::new(),
    };

    let server = tokio::spawn(async move {
        serve_listener(local_listener, Arc::new(config)).await.expect("serve listener");
    });

    let mut client = TcpStream::connect(local_addr).await.expect("connect local http front");
    client
        .write_all(b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\nProxy-Authorization: Basic bG9jYWwtdXNlcjpsb2NhbC1wYXNz\r\n\r\n")
        .await
        .expect("write connect request");
    let response = read_http_headers(&mut client).await.expect("read connect response");
    assert!(response.starts_with("HTTP/1.1 200"), "unexpected CONNECT response: {response:?}");

    client.write_all(b"ping-through-http-front").await.expect("write tunneled payload");
    let mut echoed = vec![0u8; "ping-through-http-front".len()];
    client.read_exact(&mut echoed).await.expect("read echo");
    assert_eq!(&echoed, b"ping-through-http-front");

    let observed = fixture.observed();
    assert_eq!(observed.proxy_authorization.as_deref(), Some("Basic bmFpdmUtdXNlcjpuYWl2ZS1wYXNz"));
    assert_eq!(observed.padding_type_request.as_deref(), Some("1, 0"));
    assert!(observed.request_padding_len.is_some_and(|len| (16..=32).contains(&len)));

    server.abort();
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test runtime drops its owned sockets and tasks when the test ends.
async fn helper_reconnects_after_upstream_h2_stream_failure() {
    let fixture = local_network_fixture::NaiveH2PaddingFixture::start_closing_first_tunnel_after_payload(
        "naive-user",
        "naive-pass",
    )
    .await
    .expect("start naive h2 fixture");
    let local_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let local_addr = local_listener.local_addr().expect("local addr");
    let config = NaiveProxyConfig {
        listen: local_addr.to_string(),
        server: "127.0.0.1".to_owned(),
        server_port: fixture.port(),
        server_name: fixture.server_name().to_owned(),
        username: Some("naive-user".to_owned()),
        password: Some("naive-pass".to_owned()),
        path: None,
        tls_config: fixture.client_tls_config(),
        protect_socket_path: None,
        sessions: Arc::default(),
        local_username: "local-user".to_owned(),
        local_password: "local-pass".to_owned(),
        extra_headers: Vec::new(),
    };

    let server = tokio::spawn(async move {
        serve_listener(local_listener, Arc::new(config)).await.expect("serve listener");
    });

    let mut first_client = open_socks_tunnel(local_addr).await;
    first_client.write_all(b"first-tunnel").await.expect("write first tunneled payload");
    let mut dropped_echo = vec![0u8; "first-tunnel".len()];
    assert!(
        first_client.read_exact(&mut dropped_echo).await.is_err(),
        "fixture should close the first upstream stream before echoing",
    );

    let mut second_client = open_socks_tunnel(local_addr).await;
    second_client.write_all(b"second-tunnel").await.expect("write second tunneled payload");
    let mut echoed = vec![0u8; "second-tunnel".len()];
    second_client.read_exact(&mut echoed).await.expect("read second echo");
    assert_eq!(&echoed, b"second-tunnel");

    let observed = fixture.observed();
    assert!(observed.decoded_payload_bytes >= "first-tunnel".len() + "second-tunnel".len());

    server.abort();
}

/// A local app that connects and sends nothing must not pin a helper task
/// forever: the helper closes it after the client setup deadline.
#[tokio::test(start_paused = true)]
async fn silent_client_is_closed_after_setup_deadline() {
    let local_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let local_addr = local_listener.local_addr().expect("local addr");
    let server = tokio::spawn(serve_listener(local_listener, Arc::new(naive_config_with_auth())));

    let client = TcpStream::connect(local_addr).await.expect("connect local listener");
    let mut byte = [0u8; 1];
    let closed = read_within_virtual_secs(&client, &mut byte, 120).await;

    assert_eq!(closed, Some(0), "helper must close a client that never sends a request");
    server.abort();
}

/// Reads once `client` has data, advancing paused time one second per step
/// so socket events are seen before a later deadline. Returns `None` if
/// nothing arrived within `secs`, and `Some(0)` on EOF or reset.
async fn read_within_virtual_secs(client: &TcpStream, buf: &mut [u8], secs: u64) -> Option<usize> {
    for _ in 0..secs {
        tokio::time::sleep(std::time::Duration::from_secs(1)).await;
        match client.try_read(buf) {
            Ok(read) => return Some(read),
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {}
            Err(_) => return Some(0),
        }
    }
    None
}

/// An upstream that accepts TCP and never answers the TLS ClientHello (the
/// censored-path case) must fail the CONNECT instead of holding the client.
#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test runtime drops its owned sockets and tasks when the test ends.
async fn stalled_upstream_tls_fails_socks_connect_after_deadline() {
    let upstream = TcpListener::bind("127.0.0.1:0").await.expect("bind blackhole upstream");
    let upstream_port = upstream.local_addr().expect("upstream addr").port();
    let blackhole = tokio::spawn(async move {
        let mut held = Vec::new();
        loop {
            let (socket, _) = upstream.accept().await.expect("accept upstream");
            held.push(socket);
        }
    });
    let local_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let local_addr = local_listener.local_addr().expect("local addr");
    let config = NaiveProxyConfig {
        server: "127.0.0.1".to_owned(),
        server_port: upstream_port,
        server_name: "proxy.example".to_owned(),
        ..naive_config_with_auth()
    };
    let server = tokio::spawn(serve_listener(local_listener, Arc::new(config)));

    let mut client = TcpStream::connect(local_addr).await.expect("connect local socks");
    authenticate_local(&mut client).await;
    // Keep real socket authentication ahead of the virtual setup deadline.
    tokio::time::pause();
    client.write_all(&build_socks_connect_request("127.0.0.1", 443)).await.expect("write connect request");
    let mut connect_reply = [0u8; 10];
    let replied = read_within_virtual_secs(&client, &mut connect_reply, 120).await;

    assert_eq!(replied, Some(10), "helper must answer CONNECT when the upstream TLS handshake stalls");
    assert_eq!(connect_reply[1], 0x01, "stalled upstream must yield a SOCKS general failure");
    server.abort();
    blackhole.abort();
}

/// When the upstream open fails or times out after the H2 handshake, the
/// spawned H2 driver must not keep the upstream TLS socket open.
#[tokio::test]
async fn abandoned_upstream_open_closes_the_upstream_socket() {
    let (server_config, client_config) = self_signed_upstream_tls();

    // An upstream that completes TLS and then never answers the H2 CONNECT.
    let upstream = TcpListener::bind("127.0.0.1:0").await.expect("bind upstream");
    let upstream_port = upstream.local_addr().expect("upstream addr").port();
    let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(server_config));
    let (closed_tx, closed_rx) = tokio::sync::oneshot::channel();
    let silent = tokio::spawn(async move {
        let (socket, _) = upstream.accept().await.expect("accept upstream");
        let mut tls = acceptor.accept(socket).await.expect("upstream TLS accept");
        let mut sink = [0u8; 1024];
        while matches!(tls.read(&mut sink).await, Ok(read) if read > 0) {}
        let _ = closed_tx.send(());
    });
    let config = NaiveProxyConfig {
        server: "127.0.0.1".to_owned(),
        server_port: upstream_port,
        server_name: "proxy.example".to_owned(),
        tls_config: Arc::new(client_config),
        ..naive_config_with_auth()
    };

    let target = SocksTarget::Domain("example.com".to_owned(), 443);
    let opened = tokio::time::timeout(
        std::time::Duration::from_millis(500),
        crate::connect_tunnel::open_https_connect_tunnel(&config, &target),
    )
    .await;
    assert!(opened.is_err(), "the silent upstream must not complete the CONNECT");

    let closed = tokio::time::timeout(std::time::Duration::from_secs(3), closed_rx).await;
    assert!(matches!(closed, Ok(Ok(()))), "the abandoned upstream socket must be closed");
    silent.abort();
}

#[test]
fn padding_frame_encodes_big_endian_length_and_zero_padding() {
    let mut encoder = PaddingEncoder::default();
    let mut encoded = Vec::new();
    let consumed = encoder.encode_with_padding_size(b"abc", 2, &mut encoded);

    assert_eq!(consumed, 3);
    assert_eq!(encoded, vec![0x00, 0x03, 0x02, b'a', b'b', b'c', 0x00, 0x00]);
}

#[test]
fn padding_frame_splits_payload_larger_than_u16_max() {
    let mut encoder = PaddingEncoder::default();
    let mut encoded = Vec::new();
    let payload = vec![b'x'; 65_536];
    let consumed = encoder.encode_with_padding_size(&payload, 0, &mut encoded);

    assert_eq!(consumed, 65_535);
    assert_eq!(&encoded[..3], &[0xff, 0xff, 0x00]);
    assert_eq!(encoded.len(), 3 + 65_535);
}

#[test]
fn padding_decoder_handles_fragmented_header_payload_and_padding() {
    let mut decoder = PaddingDecoder::default();
    let frame = [0x00, 0x03, 0x02, b'a', b'b', b'c', 0x00, 0x00];
    let mut decoded = Vec::new();

    for byte in frame {
        decoder.decode(&[byte], &mut decoded);
    }

    assert_eq!(decoded, b"abc");
}

#[test]
fn padding_decoder_switches_to_plain_after_eight_frames() {
    let mut decoder = PaddingDecoder::default();
    let mut wire = Vec::new();
    for index in 0..MAX_PADDED_FRAMES {
        wire.extend_from_slice(&[0x00, 0x01, 0x00, b'a' + index as u8]);
    }
    wire.extend_from_slice(b"plain");

    let mut decoded = Vec::new();
    decoder.decode(&wire, &mut decoded);

    assert_eq!(decoded, b"abcdefghplain");
}

#[test]
fn padding_encoder_switches_to_plain_after_eight_frames() {
    let mut encoder = PaddingEncoder::default();
    let mut encoded = Vec::new();
    for index in 0..MAX_PADDED_FRAMES {
        let payload = [b'a' + index as u8];
        assert_eq!(encoder.encode_with_padding_size(&payload, 0, &mut encoded), 1);
    }
    assert_eq!(encoder.encode_with_padding_size(b"plain", 255, &mut encoded), 5);

    assert_eq!(&encoded[..4], &[0x00, 0x01, 0x00, b'a']);
    assert_eq!(&encoded[32..], b"plain");
}

#[test]
fn padding_vectors_match_spec_golden() {
    let mut encoder = PaddingEncoder::default();
    let mut encoded = Vec::new();
    encoder.encode_with_padding_size(b"abc", 2, &mut encoded);
    encoder.encode_with_padding_size(b"", 3, &mut encoded);
    encoder.encode_with_padding_size(&[0xaa, 0xbb, 0xcc, 0xdd], 0, &mut encoded);

    let actual = encoded.iter().map(|byte| format!("{byte:02x}")).collect::<Vec<_>>().join(" ");
    golden_test_support::assert_text_golden(env!("CARGO_MANIFEST_DIR"), "tests/golden/padding_vectors.txt", &actual);
}

/// Upstream `FillNonindexHeaderValue`: the first up to 16 characters are
/// random picks from the first 16 printable HPACK symbols whose Huffman code
/// is at least 8 bits; the rest repeat the 17th, `X`.
#[test]
fn connect_padding_header_uses_the_upstream_nonindex_alphabet() {
    const ALPHABET: &str = "!\"#$&'()*+,;<>?@";
    let values: Vec<String> = (0..64).map(|_| generate_connect_padding_header()).collect();
    for value in &values {
        assert!((CONNECT_PADDING_HEADER_MIN..=CONNECT_PADDING_HEADER_MAX).contains(&value.len()));
        let (head, tail) = value.split_at(16);
        assert!(head.chars().all(|c| ALPHABET.contains(c)), "random head {value}");
        assert!(tail.chars().all(|c| c == 'X'), "constant tail {value}");
    }
    assert!(values.iter().any(|value| value[..16] != values[0][..16]), "the head must be random");
}

#[tokio::test]
async fn h2_connect_request_sends_naive_headers() {
    let config = naive_config_with_auth();
    let padding = "~".repeat(CONNECT_PADDING_HEADER_MIN);
    let request = build_h2_connect_request(&config, &SocksTarget::Domain("example.com".to_owned(), 443), padding)
        .expect("build h2 connect request");

    assert_eq!(request.method(), http::Method::CONNECT);
    assert_eq!(request.uri(), "example.com:443");
    assert_eq!(request.headers()["padding"].as_bytes().len(), CONNECT_PADDING_HEADER_MIN);
    assert_eq!(request.headers()["padding-type-request"], "1, 0");
    assert_eq!(request.headers()["proxy-authorization"], "Basic dXNlcjpwYXNz");

    let collected = request.into_body().collect().await.expect("collect body").to_bytes();
    assert!(collected.is_empty(), "first CONNECT must not send DATA before response");
}

#[test]
fn h2_connect_rejects_request_padding_outside_spec_range() {
    let config = naive_config_with_auth();
    let too_short = "~".repeat(CONNECT_PADDING_HEADER_MIN - 1);
    let too_long = "~".repeat(CONNECT_PADDING_HEADER_MAX + 1);

    assert!(build_h2_connect_request(&config, &SocksTarget::Domain("example.com".to_owned(), 443), too_short).is_err());
    assert!(build_h2_connect_request(&config, &SocksTarget::Domain("example.com".to_owned(), 443), too_long).is_err());
}

/// Upstream decision table: `padding-type-reply` first, then legacy `padding`.
#[test]
fn h2_connect_response_padding_negotiation_follows_upstream_order() {
    use PayloadPaddingMode::{Plain, Variant1};
    let legacy_padding = "~".repeat(45);
    let cases: [(&[(&str, &str)], Option<PayloadPaddingMode>); 9] = [
        (&[], Some(Plain)),
        // Legacy Go forwardproxy: `padding` only, any length, even empty.
        (&[("padding", legacy_padding.as_str())], Some(Variant1)),
        (&[("padding", "")], Some(Variant1)),
        (&[("padding", &"~".repeat(200))], Some(Variant1)),
        // An explicit type wins over the presence or absence of `padding`.
        (&[("padding-type-reply", "1")], Some(Variant1)),
        (&[("padding-type-reply", "0"), ("padding", legacy_padding.as_str())], Some(Plain)),
        (&[("padding-type-reply", "2"), ("padding", legacy_padding.as_str())], None),
        (&[("padding-type-reply", "1, 0")], None),
        (&[("padding-type-reply", "1"), ("padding-type-reply", "1")], None),
    ];
    for (pairs, expected) in cases {
        let mut headers = http::HeaderMap::new();
        for (name, value) in pairs {
            headers.append(*name, value.parse().expect("header value"));
        }
        let negotiated = negotiate_payload_padding_from_response(true, &headers).ok();
        assert_eq!(negotiated, expected, "{pairs:?}");
    }
    let mut padded = http::HeaderMap::new();
    padded.insert("padding-type-reply", "1".parse().expect("header value"));
    assert_eq!(negotiate_payload_padding_from_response(false, &padded).ok(), Some(Plain));
}

/// An HTTP/1.1 or no-ALPN server must fail the open before any H2 bytes reach it.
#[tokio::test]
async fn upstream_without_h2_alpn_is_rejected_before_the_h2_preface() {
    let (mut server_config, client_config) = self_signed_upstream_tls();
    for server_alpn in [vec![], vec![b"http/1.1".to_vec()]] {
        server_config.alpn_protocols = server_alpn.clone();
        let mut client_config = client_config.clone();
        // Offer http/1.1 too, so an H1-only server completes the handshake instead of alerting.
        client_config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
        let upstream = TcpListener::bind("127.0.0.1:0").await.expect("bind upstream");
        let upstream_port = upstream.local_addr().expect("upstream addr").port();
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(server_config.clone()));
        let server = tokio::spawn(async move {
            let (socket, _) = upstream.accept().await.expect("accept upstream");
            let mut tls = acceptor.accept(socket).await.expect("upstream TLS accept");
            let mut received = Vec::new();
            let _ = tls.read_to_end(&mut received).await;
            received
        });
        let config = NaiveProxyConfig {
            server: "127.0.0.1".to_owned(),
            server_port: upstream_port,
            server_name: "proxy.example".to_owned(),
            tls_config: Arc::new(client_config),
            ..naive_config_with_auth()
        };

        let target = SocksTarget::Domain("example.com".to_owned(), 443);
        let Err(error) = crate::connect_tunnel::open_https_connect_tunnel(&config, &target).await else {
            panic!("server ALPN {server_alpn:?} must be rejected");
        };

        assert!(error.to_string().contains("h2 ALPN"), "{error}");
        let received =
            tokio::time::timeout(std::time::Duration::from_secs(3), server).await.expect("closed").expect("join");
        assert!(received.is_empty(), "no H2 preface may follow server ALPN {server_alpn:?}: {received:?}");
    }
}

#[test]
fn config_keeps_upstream_credential_whitespace() {
    let args = ["--server", "proxy.example:443", "--server-name", "proxy.example", "--credentials-stdin"];
    // base64(" alice "), base64(" s3cret\t"), base64("local-user"), base64("local-pass")
    let stdin = b"IGFsaWNlIA==\nIHMzY3JldAk=\nbG9jYWwtdXNlcg==\nbG9jYWwtcGFzcw==\n";
    let config = parse_config_args_with_stdin(&args, stdin).expect("credentials with spaces");
    assert_eq!(config.username.as_deref(), Some(" alice "));
    assert_eq!(config.password.as_deref(), Some(" s3cret\t"));
}

/// While the VPN protects sockets, the proxy hostname must resolve through
/// protected DoH: a protect failure stops it before any system DNS lookup.
#[tokio::test]
async fn protected_upstream_hostname_never_uses_system_dns() {
    let missing_protect_socket = std::env::temp_dir().join(format!("ripdpi-naive-no-protect-{}", std::process::id()));
    let config = NaiveProxyConfig {
        server: "proxy.invalid".to_owned(),
        protect_socket_path: Some(missing_protect_socket.to_string_lossy().into_owned()),
        ..naive_config_with_auth()
    };

    let target = SocksTarget::Domain("example.com".to_owned(), 443);
    let Err(error) = crate::connect_tunnel::open_https_connect_tunnel(&config, &target).await else {
        panic!("an unprotectable resolver socket must fail the open");
    };

    assert!(error.to_string().contains("protected DNS"), "{error}");
}

/// A self-signed `proxy.example` server config and a client config that trusts it, both H2-only.
fn self_signed_upstream_tls() -> (rustls::ServerConfig, rustls::ClientConfig) {
    use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};

    crate::tls::ensure_rustls_provider();
    let certified = rcgen::generate_simple_self_signed(vec!["proxy.example".to_owned()]).expect("self-signed cert");
    let cert_der = certified.cert.der().clone();
    let key_der = PrivateKeyDer::from(PrivatePkcs8KeyDer::from(certified.signing_key.serialize_der()));
    let mut server_config = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![cert_der.clone()], key_der)
        .expect("server config");
    server_config.alpn_protocols = vec![b"h2".to_vec()];
    let mut roots = rustls::RootCertStore::empty();
    roots.add(cert_der).expect("trust fixture cert");
    let mut client_config = rustls::ClientConfig::builder().with_root_certificates(roots).with_no_client_auth();
    client_config.alpn_protocols = vec![b"h2".to_vec()];
    (server_config, client_config)
}

fn build_socks_connect_request(host: &str, port: u16) -> Vec<u8> {
    let mut request = vec![0x05, 0x01, 0x00, 0x01];
    let address = host.parse::<Ipv4Addr>().expect("test helper only supports ipv4 literals");
    request.extend_from_slice(&address.octets());
    request.extend_from_slice(&port.to_be_bytes());
    request
}

/// # Cancel safety:
/// cancel-safe: the test runtime drops its owned sockets and tasks when the test ends.
async fn open_socks_tunnel(local_addr: std::net::SocketAddr) -> TcpStream {
    let mut client = TcpStream::connect(local_addr).await.expect("connect local socks");
    authenticate_local(&mut client).await;

    let connect_request = build_socks_connect_request("127.0.0.1", 443);
    client.write_all(&connect_request).await.expect("write connect request");
    let mut connect_reply = [0u8; 10];
    client.read_exact(&mut connect_reply).await.expect("read connect reply");
    assert_eq!(connect_reply[1], 0x00);
    client
}

async fn read_http_headers(client: &mut TcpStream) -> std::io::Result<String> {
    let mut response = Vec::new();
    let mut byte = [0u8; 1];
    while !response.ends_with(b"\r\n\r\n") {
        client.read_exact(&mut byte).await?;
        response.push(byte[0]);
    }
    String::from_utf8(response)
        .map_err(|error| std::io::Error::new(std::io::ErrorKind::InvalidData, format!("invalid HTTP headers: {error}")))
}

fn naive_config_with_auth() -> NaiveProxyConfig {
    NaiveProxyConfig {
        listen: "127.0.0.1:11980".to_owned(),
        server: "proxy.example".to_owned(),
        server_port: 443,
        server_name: "proxy.example".to_owned(),
        username: Some("user".to_owned()),
        password: Some("pass".to_owned()),
        path: Some("/proxy".to_owned()),
        tls_config: default_tls_config(),
        protect_socket_path: None,
        sessions: Arc::default(),
        local_username: "local-user".to_owned(),
        local_password: "local-pass".to_owned(),
        extra_headers: Vec::new(),
    }
}

#[test]
fn config_parses_final_cli_contract() {
    let config = parse_config_args_with_stdin(
        &[
            "--listen",
            "127.0.0.1:1081",
            "--server",
            "proxy.example",
            "--server-port",
            "8443",
            "--server-name",
            "sni.example",
            "--credentials-stdin",
            "--path",
            "/proxy",
        ],
        b"YWxpY2U=\nc2VjcmV0\nbG9jYWwtdXNlcg==\nbG9jYWwtcGFzcw==\n",
    )
    .expect("parse final naiveproxy cli");

    assert_eq!(config.listen, "127.0.0.1:1081");
    assert_eq!(config.server, "proxy.example");
    assert_eq!(config.server_port, 8443);
    assert_eq!(config.server_name, "sni.example");
    assert_eq!(config.username.as_deref(), Some("alice"));
    assert_eq!(config.password.as_deref(), Some("secret"));
    assert_eq!(config.local_username, "local-user");
    assert_eq!(config.local_password, "local-pass");
    assert_eq!(config.path.as_deref(), Some("/proxy"));
}

#[test]
fn config_rejects_partial_auth() {
    let password_only = parse_config_args_with_stdin(
        &["--server", "proxy.example", "--server-port", "443", "--server-name", "proxy.example", "--credentials-stdin"],
        b"\nc2VjcmV0\nbG9jYWwtdXNlcg==\nbG9jYWwtcGFzcw==\n",
    );

    assert!(password_only.is_err());
}

#[test]
fn config_treats_username_with_empty_password_as_authenticated() {
    let args = ["--server", "proxy.example", "--server-port", "443", "--server-name", "proxy.example"];
    let args: Vec<&str> = args.iter().copied().chain(["--credentials-stdin"]).collect();
    let config = parse_config_args_with_stdin(&args, b"YWxpY2U=\n\nbG9jYWwtdXNlcg==\nbG9jYWwtcGFzcw==\n")
        .expect("username with empty password");
    assert_eq!(config.username.as_deref(), Some("alice"));
    assert_eq!(config.password.as_deref(), Some(""));

    let request = build_h2_connect_request(
        &config,
        &SocksTarget::Domain("example.com".to_owned(), 443),
        "~".repeat(CONNECT_PADDING_HEADER_MIN),
    )
    .expect("build request");
    // base64("alice:")
    assert_eq!(request.headers()["proxy-authorization"], "Basic YWxpY2U6");
}

#[test]
fn config_reads_extra_headers_from_the_fifth_stdin_line() {
    let args = ["--server", "proxy.example:443", "--server-name", "proxy.example", "--credentials-stdin"];
    // base64("X-Token: s3cret\r\nX-Other: v")
    let stdin = b"\n\nbG9jYWwtdXNlcg==\nbG9jYWwtcGFzcw==\nWC1Ub2tlbjogczNjcmV0DQpYLU90aGVyOiB2\n";
    let config = parse_config_args_with_stdin(&args, stdin).expect("extra headers");
    assert_eq!(config.extra_headers.len(), 2);
    let debug = format!("{config:?}");
    assert!(!debug.contains("s3cret"), "Debug leaked a header value: {debug}");

    let request = build_h2_connect_request(
        &config,
        &SocksTarget::Domain("example.com".to_owned(), 443),
        "~".repeat(CONNECT_PADDING_HEADER_MIN),
    )
    .expect("build request");
    assert_eq!(request.headers()["x-token"], "s3cret");
    assert_eq!(request.headers()["x-other"], "v");

    let empty_fifth =
        parse_config_args_with_stdin(&args, b"\n\nbG9jYWwtdXNlcg==\nbG9jYWwtcGFzcw==\n\n").expect("empty fifth line");
    assert!(empty_fifth.extra_headers.is_empty());
    // base64("Host: evil")
    let reserved = parse_config_args_with_stdin(&args, b"\n\nbG9jYWwtdXNlcg==\nbG9jYWwtcGFzcw==\nSG9zdDogZXZpbA==\n");
    assert!(reserved.is_err());
    let sixth = parse_config_args_with_stdin(&args, b"\n\nbG9jYWwtdXNlcg==\nbG9jYWwtcGFzcw==\n\nYQ==\n");
    assert!(sixth.is_err());
}

#[test]
fn config_requires_bounded_local_credentials_even_without_upstream_auth() {
    let args = ["--server", "proxy.example:443", "--server-name", "proxy.example", "--credentials-stdin"];
    let local_pair = b"\n\nbG9jYWwtdXNlcg==\nbG9jYWwtcGFzcw==\n";
    let config = parse_config_args_with_stdin(&args, local_pair).expect("local-only credentials");
    assert!(config.username.is_none());
    assert!(config.password.is_none());
    assert_eq!(config.local_username, "local-user");
    for invalid in [b"\n\n".as_slice(), b"\n\nbG9jYWwtdXNlcg==\n\n", b"\n\n\nbG9jYWwtcGFzcw==\n"] {
        assert!(parse_config_args_with_stdin(&args, invalid).is_err());
    }
    assert!(parse_config_args_with_stdin(&args, &vec![b'A'; 16 * 1024 + 1]).is_err());
    assert!(parse_config_args_with_stdin(&args[..4], local_pair).is_err());
}

fn parse_config_args_with_stdin(args: &[&str], stdin: &[u8]) -> std::io::Result<NaiveProxyConfig> {
    parse_config_from_reader(pico_args::Arguments::from_vec(args.iter().map(OsString::from).collect()), stdin)
}

/// # Cancel safety:
/// NOT cancel-safe: partial authentication consumes bytes; a failing test drops the client.
async fn authenticate_local(client: &mut TcpStream) {
    client.write_all(&[5, 1, 2]).await.expect("offer local auth");
    let mut reply = [0; 2];
    client.read_exact(&mut reply).await.expect("read auth method");
    assert_eq!(reply, [5, 2]);
    client.write_all(b"\x01\x0alocal-user\x0alocal-pass").await.expect("send local auth");
    client.read_exact(&mut reply).await.expect("read auth status");
    assert_eq!(reply, [1, 0]);
}
