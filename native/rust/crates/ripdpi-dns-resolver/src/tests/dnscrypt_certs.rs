use super::*;

fn dnscrypt_endpoint(server: &DnsCryptTestServer, port: u16) -> EncryptedDnsEndpoint {
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::DnsCrypt,
        resolver_id: Some("fixture".to_string()),
        host: "resolver.test".to_string(),
        port,
        tls_server_name: None,
        bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
        doh_url: None,
        dnscrypt_provider_name: Some(server.provider_name.clone()),
        dnscrypt_public_key: Some(server.provider_public_key_hex.clone()),
        odoh: None,
    }
}

fn cert_response_with_records(query: &[u8], provider_name: &str, records: &[Vec<u8>]) -> Vec<u8> {
    let request = Message::from_vec(query).expect("cert query parses");
    let name = Name::from_ascii(provider_name).expect("provider name");
    let mut response = Message::response(request.metadata.id, OpCode::Query);
    response.metadata.response_code = ResponseCode::NoError;
    response.add_query(Query::new(name.clone(), RecordType::TXT));
    for record in records {
        response.add_answer(Record::from_rdata(name.clone(), 600, RData::TXT(TXT::from_bytes(vec![record]))));
    }
    response.to_vec().expect("cert response encodes")
}

/// Answers the certificate fetch with `records` on the first connection and one
/// encrypted query on the second.
fn start_server_with_cert_records(
    server: DnsCryptTestServer,
    records: Vec<Vec<u8>>,
    response_packet: Vec<u8>,
) -> (u16, thread::JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("dnscrypt listener");
    let port = listener.local_addr().expect("dnscrypt local addr").port();
    let handle = thread::spawn(move || {
        let (mut cert_stream, _) = listener.accept().expect("dnscrypt cert accept");
        let cert_query = read_length_prefixed_frame(&mut cert_stream).expect("dnscrypt cert read");
        let cert_response = cert_response_with_records(&cert_query, &server.provider_name, &records);
        write_length_prefixed_frame(&mut cert_stream, &cert_response).expect("dnscrypt cert write");

        let (mut query_stream, _) = listener.accept().expect("dnscrypt query accept");
        let packet = read_length_prefixed_frame(&mut query_stream).expect("dnscrypt query read");
        let mut client_public = [0u8; 32];
        client_public.copy_from_slice(&packet[8..40]);
        let mut nonce = [0u8; DNSCRYPT_NONCE_SIZE];
        nonce[..DNSCRYPT_QUERY_NONCE_HALF].copy_from_slice(&packet[40..52]);
        let cipher = server.server_cipher(&CryptoPublicKey::from(client_public));
        serve_dnscrypt_query(&packet, &server, &response_packet, &cipher, &nonce, &mut query_stream);
    });
    (port, handle)
}

#[test]
fn dnscrypt_uses_a_valid_certificate_after_an_unusable_record() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 50);
    let server = DnsCryptTestServer::new("resolver.test");
    let mut unsupported = server.certificate_bytes();
    unsupported[4..6].copy_from_slice(&3u16.to_be_bytes());
    let records = vec![unsupported, server.certificate_bytes()];
    let (port, handle) = start_server_with_cert_records(server.clone(), records, build_response(&query, answer_ip));

    let resolver = EncryptedDnsResolver::new(dnscrypt_endpoint(&server, port), EncryptedDnsTransport::Direct)
        .expect("resolver builds");

    let response = resolver.exchange_blocking(&query).expect("the valid certificate is used");
    assert_eq!(extract_ip_answers(&response).expect("answers parse"), vec![answer_ip.to_string()]);
    handle.join().expect("server thread completes");
}
