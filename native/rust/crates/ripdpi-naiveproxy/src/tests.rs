use std::net::Ipv4Addr;
use std::sync::Arc;

use http_body_util::BodyExt;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

use crate::config::NaiveProxyConfig;
use crate::h2_connect::{
    build_h2_connect_request, negotiate_payload_padding_from_response, PayloadPaddingMode, CONNECT_PADDING_HEADER_MAX,
    CONNECT_PADDING_HEADER_MIN, RESPONSE_PADDING_HEADER_MAX, RESPONSE_PADDING_HEADER_MIN,
};
use crate::padding::{PaddingDecoder, PaddingEncoder, MAX_PADDED_FRAMES};
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
    assert_eq!(observed.padding_type_request.as_deref(), Some("1"));
    assert!(observed.request_padding_len.is_some_and(|len| (16..=32).contains(&len)));
    assert!(observed.decoded_payload_bytes >= "ping-through-h2-naive".len());

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

#[tokio::test]
async fn h2_connect_request_sends_naive_headers() {
    let config = naive_config_with_auth();
    let padding = "~".repeat(CONNECT_PADDING_HEADER_MIN);
    let request = build_h2_connect_request(&config, &SocksTarget::Domain("example.com".to_owned(), 443), padding)
        .expect("build h2 connect request");

    assert_eq!(request.method(), http::Method::CONNECT);
    assert_eq!(request.uri(), "example.com:443");
    assert_eq!(request.headers()["padding"].as_bytes().len(), CONNECT_PADDING_HEADER_MIN);
    assert_eq!(request.headers()["padding-type-request"], "1");
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
    }
}
