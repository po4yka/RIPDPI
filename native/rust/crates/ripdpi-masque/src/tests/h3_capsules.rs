//! H3 CONNECT-UDP delivery of HTTP Datagrams as DATAGRAM capsules (RFC 9297 section 3.5).

use super::*;

type H3ServerRequestStream = ::h3::server::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>;
type H3ServerConnection = ::h3::server::Connection<h3_quinn::Connection, Bytes>;

/// A loopback H3 proxy endpoint and a client config that trusts it.
fn h3_connect_udp_test_proxy() -> (quinn::Endpoint, MasqueConfig) {
    let (certificate_der, key_der, certificate_pem) = localhost_certificate();
    let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("TLS versions")
        .with_no_client_auth()
        .with_single_cert(
            vec![rustls::pki_types::CertificateDer::from(certificate_der)],
            rustls::pki_types::PrivatePkcs8KeyDer::from(key_der).into(),
        )
        .expect("server certificate");
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let quic = quinn::crypto::rustls::QuicServerConfig::try_from(tls).expect("QUIC server config");
    let endpoint = quinn::Endpoint::server(
        quinn::ServerConfig::with_crypto(Arc::new(quic)),
        "127.0.0.1:0".parse().expect("server address"),
    )
    .expect("server endpoint");
    let proxy_address = endpoint.local_addr().expect("proxy address");
    let mut config = h3_connect_udp_proxy_config(proxy_address, certificate_pem);
    config.url = format!("https://localhost:{}/", proxy_address.port());
    (endpoint, config)
}

/// Accepts one CONNECT-UDP request, answers 200, and waits for the client's
/// first QUIC datagram so its reader task is known to be running.
async fn accept_h3_connect_udp(endpoint: &quinn::Endpoint) -> (H3ServerConnection, H3ServerRequestStream) {
    use h3_datagram::datagram_handler::HandleDatagramsExt;

    let connection = endpoint.accept().await.expect("incoming").await.expect("QUIC handshake");
    let mut builder = ::h3::server::builder();
    builder.enable_extended_connect(true);
    builder.enable_datagram(true);
    let mut h3_connection: H3ServerConnection =
        builder.build(h3_quinn::Connection::new(connection)).await.expect("H3 server");
    let resolver = h3_connection.accept().await.expect("accept").expect("request");
    let (_request, mut stream) = resolver.resolve_request().await.expect("resolve request");
    let response =
        http::Response::builder().status(StatusCode::OK).header("capsule-protocol", "?1").body(()).expect("response");
    stream.send_response(response).await.expect("send response");
    h3_connection.get_datagram_reader().read_datagram().await.expect("the client's first datagram");
    (h3_connection, stream)
}

fn context_zero(payload: &[u8]) -> Vec<u8> {
    crate::capsule::encode_connect_udp_payload(0, payload).expect("CONNECT-UDP payload")
}

fn datagram_capsule(value: &[u8]) -> Vec<u8> {
    crate::capsule::encode_datagram_capsule(value).expect("DATAGRAM capsule")
}

/// RFC 9297 3.5 / RFC 9298 5: a proxy may send HTTP Datagrams as DATAGRAM
/// capsules on the request stream instead of QUIC DATAGRAM frames. Both paths
/// deliver alike; unknown capsule types and context IDs are skipped.
#[tokio::test]
async fn h3_connect_udp_delivers_datagram_capsules_alongside_quic_datagrams() {
    use h3_datagram::datagram_handler::HandleDatagramsExt;

    let (endpoint, config) = h3_connect_udp_test_proxy();
    let (step_tx, mut step_rx) = tokio::sync::mpsc::unbounded_channel::<()>();
    let server = tokio::spawn(async move {
        let (connection, mut stream) = accept_h3_connect_udp(&endpoint).await;
        let mut quic = connection.get_datagram_sender(stream.id());

        quic.send_datagram(Bytes::from(context_zero(b"quic-1"))).expect("send QUIC datagram");
        step_rx.recv().await.expect("client step");

        let mut capsules = vec![0x21, 0x02, 0xaa, 0xbb];
        capsules.extend(datagram_capsule(
            &crate::capsule::encode_connect_udp_payload(5, b"other-context").expect("payload"),
        ));
        capsules.extend(datagram_capsule(&context_zero(b"capsule-1")));
        stream.send_data(Bytes::from(capsules)).await.expect("send capsules");
        step_rx.recv().await.expect("client step");

        quic.send_datagram(Bytes::from(context_zero(b"quic-2"))).expect("send QUIC datagram");
        step_rx.recv().await.expect("client step");

        let split = datagram_capsule(&context_zero(b"capsule-2"));
        stream.send_data(Bytes::copy_from_slice(&split[..3])).await.expect("send capsule head");
        stream.send_data(Bytes::copy_from_slice(&split[3..])).await.expect("send capsule tail");
        step_rx.recv().await.expect("client step");
        (connection, stream)
    });

    let client = MasqueClient::new(config).expect("client");
    let mut udp = client.udp_session();
    udp.send_to("127.0.0.1:9", b"open").await.expect("open H3 CONNECT-UDP flow");
    for expected in ["quic-1", "capsule-1", "quic-2", "capsule-2"] {
        let (_, payload) = tokio::time::timeout(std::time::Duration::from_secs(5), udp.recv_from())
            .await
            .unwrap_or_else(|_| panic!("{expected} was not delivered"))
            .expect("receive");
        assert_eq!(payload, expected.as_bytes());
        step_tx.send(()).expect("server is running");
    }
    let _server_state = server.await.expect("server task");
    drop(udp);
}

/// RFC 9297 3.3: a malformed DATAGRAM capsule (here, a value too short for its
/// context ID) is a stream error, so the client aborts the request stream.
#[tokio::test]
async fn h3_connect_udp_aborts_the_stream_on_a_malformed_datagram_capsule() {
    let (endpoint, config) = h3_connect_udp_test_proxy();
    let server = tokio::spawn(async move {
        let (connection, mut stream) = accept_h3_connect_udp(&endpoint).await;
        stream.send_data(Bytes::from_static(&[0x00, 0x01, 0x40])).await.expect("send malformed capsule");
        let result = tokio::time::timeout(std::time::Duration::from_secs(5), stream.recv_data())
            .await
            .expect("the client aborts the request stream");
        match result {
            Err(::h3::error::StreamError::RemoteTerminate { code, .. }) => {
                assert_eq!(code.value(), crate::capsule::H3_DATAGRAM_ERROR);
            }
            other => panic!("expected a reset with H3_DATAGRAM_ERROR, got {:?}", other.map(|data| data.is_some())),
        }
        connection
    });

    let client = MasqueClient::new(config).expect("client");
    let mut udp = client.udp_session();
    udp.send_to("127.0.0.1:9", b"open").await.expect("open H3 CONNECT-UDP flow");
    let _connection = server.await.expect("server task");
    drop(udp);
}
