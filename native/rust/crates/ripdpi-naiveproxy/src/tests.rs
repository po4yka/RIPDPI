use std::ffi::OsString;
use std::net::Ipv4Addr;
use std::sync::Arc;

use crate::config::parse_config_from_reader;
use http_body_util::BodyExt;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

use crate::config::NaiveProxyConfig;
use crate::h2_connect::{
    CONNECT_PADDING_HEADER_MAX, CONNECT_PADDING_HEADER_MIN, PayloadPaddingMode, RESPONSE_PADDING_HEADER_MAX,
    RESPONSE_PADDING_HEADER_MIN, build_h2_connect_request, generate_connect_padding_header,
    negotiate_payload_padding_from_response,
};
use crate::padding::{MAX_PADDED_FRAMES, PaddingDecoder, PaddingEncoder};
use crate::relay::serve_listener;
use crate::socks5::SocksTarget;
use crate::tls::default_tls_config;

#[tokio::test]
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
    };

    let server = tokio::spawn(async move {
        serve_listener(local_listener, Arc::new(config)).await.expect("serve listener");
    });

    let mut client = TcpStream::connect(local_addr).await.expect("connect local socks");
    client.write_all(&[0x05, 0x01, 0x00]).await.expect("write greeting");
    let mut auth_reply = [0u8; 2];
    client.read_exact(&mut auth_reply).await.expect("read greeting reply");
    assert_eq!(auth_reply, [0x05, 0x00]);

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

#[tokio::test]
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
    };

    let server = tokio::spawn(async move {
        serve_listener(local_listener, Arc::new(config)).await.expect("serve listener");
    });

    let mut client = TcpStream::connect(local_addr).await.expect("connect local socks");
    client.write_all(&[0x05, 0x01, 0x00]).await.expect("write greeting");
    let mut auth_reply = [0u8; 2];
    client.read_exact(&mut auth_reply).await.expect("read greeting reply");
    assert_eq!(auth_reply, [0x05, 0x00]);

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

#[tokio::test]
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
    };

    let server = tokio::spawn(async move {
        serve_listener(local_listener, Arc::new(config)).await.expect("serve listener");
    });

    let mut client = TcpStream::connect(local_addr).await.expect("connect local http front");
    client
        .write_all(b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n")
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
#[tokio::test(start_paused = true)]
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
    client.write_all(&[0x05, 0x01, 0x00]).await.expect("write greeting");
    let mut auth_reply = [0u8; 2];
    client.read_exact(&mut auth_reply).await.expect("read greeting reply");
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

/// Forwards TCP to `target` until `frozen` is set; then it swallows bytes and
/// sends nothing, like a path that died without FIN or RST.
async fn freezable_forwarder(target: u16, frozen: Arc<std::sync::atomic::AtomicBool>) -> u16 {
    use std::sync::atomic::Ordering;
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind forwarder");
    let port = listener.local_addr().expect("forwarder addr").port();
    tokio::spawn(async move {
        while let Ok((inbound, _)) = listener.accept().await {
            let outbound = TcpStream::connect(("127.0.0.1", target)).await.expect("connect target");
            let (inbound_read, inbound_write) = inbound.into_split();
            let (outbound_read, outbound_write) = outbound.into_split();
            for (mut from, mut to) in [(inbound_read, outbound_write), (outbound_read, inbound_write)] {
                let frozen = Arc::clone(&frozen);
                tokio::spawn(async move {
                    let mut chunk = [0u8; 16 * 1024];
                    while let Ok(read) = from.read(&mut chunk).await {
                        if read == 0 || frozen.load(Ordering::SeqCst) {
                            std::future::pending::<()>().await;
                        }
                        if to.write_all(&chunk[..read]).await.is_err() {
                            return;
                        }
                    }
                });
            }
        }
    });
    port
}

/// A tunnel whose upstream path dies silently is closed by the H2 keep-alive
/// instead of pinning both sockets and its task until the helper restarts.
#[tokio::test]
async fn tunnel_on_a_silently_dead_upstream_path_is_closed() {
    let fixture = local_network_fixture::NaiveH2PaddingFixture::start("naive-user", "naive-pass")
        .await
        .expect("start naive h2 fixture");
    let frozen = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let forward_port = freezable_forwarder(fixture.port(), Arc::clone(&frozen)).await;
    let local_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let local_addr = local_listener.local_addr().expect("local addr");
    let config = NaiveProxyConfig {
        listen: local_addr.to_string(),
        server: "127.0.0.1".to_owned(),
        server_port: forward_port,
        server_name: fixture.server_name().to_owned(),
        username: Some("naive-user".to_owned()),
        password: Some("naive-pass".to_owned()),
        path: None,
        tls_config: fixture.client_tls_config(),
        protect_socket_path: None,
    };
    let server = tokio::spawn(serve_listener(local_listener, Arc::new(config)));

    let mut client = open_socks_tunnel(local_addr).await;
    client.write_all(b"before").await.expect("write");
    let mut echoed = [0u8; 6];
    client.read_exact(&mut echoed).await.expect("echo before the path dies");

    frozen.store(true, std::sync::atomic::Ordering::SeqCst);
    tokio::time::pause();
    let mut byte = [0u8; 1];
    let closed = read_within_virtual_secs(&client, &mut byte, 300).await;
    assert_eq!(closed, Some(0), "the dead tunnel must be closed");
    server.abort();
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

#[test]
fn h2_connect_response_without_padding_disables_payload_padding() {
    let headers = http::HeaderMap::new();
    let negotiated = negotiate_payload_padding_from_response(true, &headers).expect("negotiate no padding");

    assert_eq!(negotiated, PayloadPaddingMode::Plain);
}

#[test]
fn h2_connect_response_with_padding_reply_enables_variant1() {
    let mut headers = http::HeaderMap::new();
    headers.insert("padding", "~".repeat(RESPONSE_PADDING_HEADER_MIN).parse().expect("padding header"));
    headers.insert("padding-type-reply", "1".parse().expect("padding type"));

    let negotiated = negotiate_payload_padding_from_response(true, &headers).expect("negotiate variant1");

    assert_eq!(negotiated, PayloadPaddingMode::Variant1);
}

#[test]
fn h2_connect_rejects_response_padding_outside_spec_range() {
    let mut headers = http::HeaderMap::new();
    headers.insert("padding", "~".repeat(RESPONSE_PADDING_HEADER_MAX + 1).parse().expect("padding header"));
    headers.insert("padding-type-reply", "1".parse().expect("padding type"));

    assert!(negotiate_payload_padding_from_response(true, &headers).is_err());
}

fn build_socks_connect_request(host: &str, port: u16) -> Vec<u8> {
    let mut request = vec![0x05, 0x01, 0x00, 0x01];
    let address = host.parse::<Ipv4Addr>().expect("test helper only supports ipv4 literals");
    request.extend_from_slice(&address.octets());
    request.extend_from_slice(&port.to_be_bytes());
    request
}

async fn open_socks_tunnel(local_addr: std::net::SocketAddr) -> TcpStream {
    let mut client = TcpStream::connect(local_addr).await.expect("connect local socks");
    client.write_all(&[0x05, 0x01, 0x00]).await.expect("write greeting");
    let mut auth_reply = [0u8; 2];
    client.read_exact(&mut auth_reply).await.expect("read greeting reply");
    assert_eq!(auth_reply, [0x05, 0x00]);

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
        b"YWxpY2U=\nc2VjcmV0\n",
    )
    .expect("parse final naiveproxy cli");

    assert_eq!(config.listen, "127.0.0.1:1081");
    assert_eq!(config.server, "proxy.example");
    assert_eq!(config.server_port, 8443);
    assert_eq!(config.server_name, "sni.example");
    assert_eq!(config.username.as_deref(), Some("alice"));
    assert_eq!(config.password.as_deref(), Some("secret"));
    assert_eq!(config.path.as_deref(), Some("/proxy"));
}

/// The listener has no authentication, so it must not be reachable from the
/// network: only loopback listen addresses are accepted.
#[test]
fn config_accepts_only_a_loopback_listen_address() {
    let parse = |listen: &str| {
        parse_config_args_with_stdin(
            &[
                "--listen",
                listen,
                "--server",
                "proxy.example",
                "--server-port",
                "443",
                "--server-name",
                "proxy.example",
            ],
            b"",
        )
    };
    for loopback in ["127.0.0.1:1081", "127.8.0.1:1081", "[::1]:1081", "::1:1081", "localhost:1081"] {
        assert!(parse(loopback).is_ok(), "{loopback}");
    }
    for exposed in ["0.0.0.0:1081", "192.168.1.5:1081", "[::]:1081", "proxy.example:1081", "1081"] {
        let error = parse(exposed).expect_err(exposed);
        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput, "{exposed}");
    }
}

#[test]
fn config_rejects_partial_auth() {
    let username_only = parse_config_args_with_stdin(
        &["--server", "proxy.example", "--server-port", "443", "--server-name", "proxy.example", "--credentials-stdin"],
        b"YWxpY2U=\n\n",
    );
    let password_only = parse_config_args_with_stdin(
        &["--server", "proxy.example", "--server-port", "443", "--server-name", "proxy.example", "--credentials-stdin"],
        b"\nc2VjcmV0\n",
    );

    assert!(username_only.is_err());
    assert!(password_only.is_err());
}

fn parse_config_args_with_stdin(args: &[&str], stdin: &[u8]) -> std::io::Result<NaiveProxyConfig> {
    parse_config_from_reader(pico_args::Arguments::from_vec(args.iter().map(OsString::from).collect()), stdin)
}
