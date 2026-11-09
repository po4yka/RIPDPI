use super::*;

/// # Cancel safety:
/// NOT cancel-safe: echo validation and joined fixture shutdown are one test
/// transaction; runtime teardown closes peers if the test is interrupted.
#[tokio::test]
async fn http3_method_refusal_still_falls_back_to_working_http2() {
    let h2 = MasqueH2ConnectUdpFixture::start().await.expect("H2 fixture");
    let address: SocketAddr =
        h2.masque_url().trim_start_matches("https://").split('/').next().expect("authority").parse().expect("address");
    let certificate = rcgen::generate_simple_self_signed(vec!["localhost".into()]).expect("H3 certificate");
    let pem = certificate.cert.pem();
    let key = rustls::pki_types::PrivateKeyDer::try_from(certificate.signing_key.serialize_der()).expect("H3 key");
    let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .expect("TLS13")
        .with_no_client_auth()
        .with_single_cert(vec![certificate.cert.der().clone()], key)
        .expect("H3 TLS");
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let crypto = quinn::crypto::rustls::QuicServerConfig::try_from(tls).expect("H3 crypto");
    let endpoint = quinn::Endpoint::server(quinn::ServerConfig::with_crypto(Arc::new(crypto)), address)
        .expect("UDP uses the TCP fixture port");
    let server = tokio::spawn(async move {
        let connection = endpoint.accept().await.expect("incoming").await.expect("QUIC handshake");
        let mut builder = ::h3::server::builder();
        builder.enable_extended_connect(true);
        builder.enable_datagram(true);
        let mut h3: ::h3::server::Connection<h3_quinn::Connection, Bytes> =
            builder.build(h3_quinn::Connection::new(connection)).await.expect("H3 server");
        let resolver = h3.accept().await.expect("accept").expect("request");
        let (_request, mut stream) = resolver.resolve_request().await.expect("request");
        stream
            .send_response(http::Response::builder().status(StatusCode::METHOD_NOT_ALLOWED).body(()).expect("405"))
            .await
            .expect("response");
        stream.finish().await.expect("finish");
        (endpoint, h3)
    });
    let mut config = h3_connect_udp_proxy_config(address, format!("{}\n{pem}", h2.certificate_pem()));
    config.url = format!("https://localhost:{}/", address.port());
    config.use_http2_fallback = true;
    let client = MasqueClient::new(config).expect("client");
    let mut udp = client.udp_session();
    udp.send_to(&h2.udp_echo_target(), b"fallback-after-405").await.expect("H2 fallback");
    let (_, payload) = tokio::time::timeout(std::time::Duration::from_secs(5), udp.recv_from())
        .await
        .expect("echo deadline")
        .expect("echo");
    assert_eq!(payload, b"fallback-after-405");
    assert_eq!(h2.observed_requests().len(), 1);
    let _h3 = server.await.expect("H3 task");
}
