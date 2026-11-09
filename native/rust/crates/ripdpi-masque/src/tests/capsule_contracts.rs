//! End-to-end Capsule Protocol rejection on the production H3 client.

use super::*;

use std::time::Duration;

const PEER_TIMEOUT: Duration = Duration::from_secs(3);
const NO_DATAGRAM_TIMEOUT: Duration = Duration::from_millis(200);

type PeerConnection = ::h3::server::Connection<h3_quinn::Connection, Bytes>;
type PeerStream = ::h3::server::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>;

fn proxy() -> (quinn::Endpoint, MasqueConfig) {
    let (certificate, key, certificate_pem) = localhost_certificate();
    let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("TLS versions")
        .with_no_client_auth()
        .with_single_cert(
            vec![rustls::pki_types::CertificateDer::from(certificate)],
            rustls::pki_types::PrivatePkcs8KeyDer::from(key).into(),
        )
        .expect("server identity");
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let crypto = quinn::crypto::rustls::QuicServerConfig::try_from(tls).expect("QUIC server TLS");
    let endpoint = quinn::Endpoint::server(
        quinn::ServerConfig::with_crypto(Arc::new(crypto)),
        "127.0.0.1:0".parse().expect("loopback address"),
    )
    .expect("server endpoint");
    let address = endpoint.local_addr().expect("proxy address");
    let mut config = h3_connect_udp_proxy_config(address, certificate_pem);
    config.url = format!("https://localhost:{}/", address.port());
    (endpoint, config)
}

/// # Cancel safety:
/// NOT cancel-safe: the bounded test exchange owns the peer until it is joined.
async fn accept_tunnel(endpoint: &quinn::Endpoint) -> (PeerConnection, PeerStream) {
    use h3_datagram::datagram_handler::HandleDatagramsExt;

    let connection = tokio::time::timeout(PEER_TIMEOUT, endpoint.accept())
        .await
        .expect("client connects")
        .expect("incoming QUIC")
        .await
        .expect("QUIC handshake");
    let mut builder = ::h3::server::builder();
    builder.enable_extended_connect(true).enable_datagram(true);
    let mut peer: PeerConnection = builder.build(h3_quinn::Connection::new(connection)).await.expect("H3 peer");
    let resolver = tokio::time::timeout(PEER_TIMEOUT, peer.accept())
        .await
        .expect("request deadline")
        .expect("accept request")
        .expect("request");
    let (_, mut stream) = resolver.resolve_request().await.expect("request fields");
    stream
        .send_response(http::Response::builder().status(StatusCode::OK).body(()).expect("response"))
        .await
        .expect("send response");
    tokio::time::timeout(PEER_TIMEOUT, peer.get_datagram_reader().read_datagram())
        .await
        .expect("first datagram deadline")
        .expect("first client datagram");
    (peer, stream)
}

/// # Cancel safety:
/// NOT cancel-safe: the peer must observe the protocol reset before the test ends.
async fn assert_message_error(stream: &mut PeerStream) {
    let received = tokio::time::timeout(PEER_TIMEOUT, stream.recv_data()).await.expect("client resets request");
    match received {
        Err(::h3::error::StreamError::RemoteTerminate { code, .. }) => {
            assert_eq!(code.value(), 0x010e);
        }
        other => panic!("expected H3_MESSAGE_ERROR, got {:?}", other.map(|data| data.is_some())),
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the bounded test joins its server and keeps the relay alive through the reset assertion.
#[tokio::test]
async fn h3_bad_capsules_reset_request_and_never_deliver_following_payload() {
    let mut cases = vec![vec![0, 1, 0x40]]; // Context ID varint is longer than the capsule value.
    for (prefix, length) in [([0, 0x80, 0, 0xff, 0xf9, 0], 65528), ([0, 0x80, 1, 0, 1, 0], 65536)] {
        // Literal capsule type 0 and 4-byte varint length; Context ID 0 is included in the length.
        let mut capsule = prefix.to_vec();
        capsule.extend(std::iter::repeat_n(0x61, length));
        cases.push(capsule);
    }
    for mut invalid in cases {
        invalid.extend_from_slice(&[0, 3, 0, b'o', b'k']);
        let (endpoint, config) = proxy();
        let server = tokio::spawn(async move {
            let (peer, mut stream) = accept_tunnel(&endpoint).await;
            stream.send_data(Bytes::from(invalid)).await.expect("send invalid capsule and later datagram");
            assert_message_error(&mut stream).await;
            (endpoint, peer, stream)
        });
        let client = MasqueClient::new(config).expect("client");
        let mut relay = client.udp_session();
        relay.send_to("127.0.0.1:9", b"open").await.expect("open tunnel");
        let _peer_state = server.await.expect("server completes");
        assert!(tokio::time::timeout(NO_DATAGRAM_TIMEOUT, relay.recv_from()).await.is_err());
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the server must observe the reset after its clean FIN before the test ends.
#[tokio::test]
async fn h3_fin_in_a_capsule_value_resets_request() {
    let (endpoint, config) = proxy();
    let server = tokio::spawn(async move {
        let (peer, mut stream) = accept_tunnel(&endpoint).await;
        stream.send_data(Bytes::from_static(&[0, 3, 0, b'a'])).await.expect("send partial capsule");
        stream.finish().await.expect("send clean FIN");
        assert_message_error(&mut stream).await;
        (endpoint, peer, stream)
    });
    let client = MasqueClient::new(config).expect("client");
    let mut relay = client.udp_session();
    relay.send_to("127.0.0.1:9", b"open").await.expect("open tunnel");
    let _peer_state = server.await.expect("server completes");
    assert!(tokio::time::timeout(NO_DATAGRAM_TIMEOUT, relay.recv_from()).await.is_err());
}

/// # Cancel safety:
/// NOT cancel-safe: the server remains alive until the client checks both payloads.
#[tokio::test]
async fn h3_unknown_capsules_and_contexts_preserve_valid_payload() {
    let (endpoint, config) = proxy();
    let (done_tx, done_rx) = tokio::sync::oneshot::channel();
    let server = tokio::spawn(async move {
        let (peer, mut stream) = accept_tunnel(&endpoint).await;
        stream
            .send_data(Bytes::from_static(&[
                0x21, 2, 0xaa, 0xbb, // Unknown capsule type.
                0, 3, 1, 0xcc, 0xdd, // Unknown Context ID 1.
                0, 3, 0, b'o', b'k', // Context ID 0 payload.
            ]))
            .await
            .expect("send mixed capsules");
        done_rx.await.expect("client checked payload");
        (endpoint, peer, stream)
    });
    let client = MasqueClient::new(config).expect("client");
    let mut relay = client.udp_session();
    relay.send_to("127.0.0.1:9", b"open").await.expect("open tunnel");
    let (_, payload) =
        tokio::time::timeout(PEER_TIMEOUT, relay.recv_from()).await.expect("payload deadline").expect("payload");
    assert_eq!(payload, b"ok");
    done_tx.send(()).expect("server alive");
    let _peer_state = server.await.expect("server completes");
}
