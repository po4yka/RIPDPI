use super::*;

fn dot_server_config() -> (Arc<ServerConfig>, CertificateDer<'static>) {
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let config = ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("ring provider supports default TLS versions")
        .with_no_client_auth()
        .with_single_cert(vec![certificate_der.clone()], key_der)
        .expect("server config");
    (Arc::new(config), certificate_der)
}

/// Serves one DoT query with `response` and returns what the resolver made of it.
fn dot_exchange_with_response(query: &[u8], response: Vec<u8>) -> Result<Vec<u8>, EncryptedDnsError> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let (server_config, certificate_der) = dot_server_config();
    let server_query = query.to_vec();
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_dot(stream, server_config, &server_query, &response);
    });
    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        dot_fixture_endpoint(port),
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
    )
    .expect("resolver builds");
    let result = resolver.exchange_blocking(query);
    server.join().expect("server thread completes");
    result
}

#[test]
fn dot_rejects_a_frame_with_another_message_id() {
    let query = build_query("fixture.test");
    let mut response = build_response(&query, Ipv4Addr::new(198, 18, 0, 40));
    response[..2].copy_from_slice(&(u16::from_be_bytes([query[0], query[1]]) ^ 0x5a5a).to_be_bytes());

    let error = dot_exchange_with_response(&query, response).expect_err("foreign ID must be rejected");

    assert!(error.to_string().contains("does not match the query"), "error: {error}");
}

#[test]
fn dot_rejects_a_frame_for_another_question() {
    let query = build_query("fixture.test");
    let mut response = build_response(&build_query("other.test"), Ipv4Addr::new(198, 18, 0, 41));
    response[..2].copy_from_slice(&query[..2]);

    let error = dot_exchange_with_response(&query, response).expect_err("foreign question must be rejected");

    assert!(error.to_string().contains("does not match the query"), "error: {error}");
}

#[test]
fn dot_accepts_an_error_reply_without_a_question() {
    let query = build_query("fixture.test");
    let request = Message::from_vec(&query).expect("query parses");
    let mut reply = Message::response(request.metadata.id, OpCode::Query);
    reply.metadata.response_code = ResponseCode::FormErr;

    let response = dot_exchange_with_response(&query, reply.to_vec().expect("reply serializes"))
        .expect("FORMERR without a question is a valid reply");

    assert_eq!(Message::from_vec(&response).expect("reply parses").metadata.response_code, ResponseCode::FormErr);
}
