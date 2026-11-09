use super::*;

use hickory_proto::op::Edns;
use hickory_proto::rr::rdata::opt::{EdnsCode, EdnsOption};

#[test]
fn dot_query_is_padded_to_128_and_reply_opt_is_removed() {
    let query = build_query("fixture.test");
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let server_config = Arc::new(
        ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_no_client_auth()
            .with_single_cert(vec![certificate_der.clone()], key_der)
            .expect("server config"),
    );
    let server = thread::spawn(move || -> Vec<u8> {
        let (stream, _) = listener.accept().expect("accept");
        let connection = ServerConnection::new(server_config).expect("server connection");
        let mut tls_stream = StreamOwned::new(connection, stream);
        while tls_stream.conn.is_handshaking() {
            tls_stream.conn.complete_io(&mut tls_stream.sock).expect("TLS handshake completes");
        }
        let received = read_length_prefixed_frame(&mut tls_stream).expect("read DoT query");
        // The reply carries an OPT with padding, as a RFC 8467 server does.
        let mut reply = Message::from_vec(&build_response(&received, Ipv4Addr::new(198, 18, 0, 12))).expect("reply");
        let mut edns = Edns::new();
        edns.options_mut().options.push((EdnsCode::Padding, EdnsOption::Unknown(12, vec![0; 8])));
        reply.edns = Some(edns);
        write_length_prefixed_frame(&mut tls_stream, &reply.to_vec().expect("reply encodes")).expect("write reply");
        received
    });

    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: Some("fixture".to_string()),
            host: "fixture.test".to_string(),
            port,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        },
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
    )
    .expect("resolver builds");

    let response = resolver.exchange_blocking(&query).expect("DoT response");
    let received = server.join().expect("server thread completes");

    assert_eq!(received.len() % 128, 0, "RFC 8467 4.1 block-128 padding");
    let sent = Message::from_vec(&received).expect("server query parses");
    let edns = sent.edns.expect("OPT added");
    assert_eq!(u16::from(edns.options().options.last().expect("an option").0), 12, "padding option is last");
    let reply = Message::from_vec(&response).expect("reply parses");
    assert!(reply.edns.is_none(), "the client sent no OPT, so it gets none");
    assert_eq!(extract_ip_answers(&response).expect("answers"), vec!["198.18.0.12".to_string()]);
}
