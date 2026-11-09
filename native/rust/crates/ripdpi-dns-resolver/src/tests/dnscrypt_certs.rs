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

fn is_cert_query(packet: &[u8]) -> bool {
    Message::from_vec(packet)
        .is_ok_and(|message| message.queries.first().is_some_and(|query| query.query_type == RecordType::TXT))
}

#[test]
fn dnscrypt_refetches_the_certificate_after_a_failed_exchange() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 51);
    let server = DnsCryptTestServer::new("resolver.test");
    // es_version is outside the signed bytes, so an on-path flip still verifies.
    let mut flipped = server.certificate_bytes();
    flipped[4..6].copy_from_slice(&DNSCRYPT_ES_VERSION_XSALSA.to_be_bytes());
    let response_packet = build_response(&query, answer_ip);
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("dnscrypt listener");
    let port = listener.local_addr().expect("dnscrypt local addr").port();
    let fixture = server.clone();
    let handle = thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("first cert accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("first cert read");
        let response = cert_response_with_records(&packet, &fixture.provider_name, &[flipped]);
        write_length_prefixed_frame(&mut stream, &response).expect("first cert write");

        // The query encrypted for the wrong cipher cannot be answered.
        let (mut stream, _) = listener.accept().expect("first query accept");
        read_length_prefixed_frame(&mut stream).expect("first query read");
        drop(stream);

        let (mut stream, _) = listener.accept().expect("second cert accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("second cert read");
        assert!(is_cert_query(&packet), "the client reused the failed certificate");
        let response = cert_response_with_records(&packet, &fixture.provider_name, &[fixture.certificate_bytes()]);
        write_length_prefixed_frame(&mut stream, &response).expect("second cert write");

        let (mut stream, _) = listener.accept().expect("second query accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("second query read");
        let mut client_public = [0u8; 32];
        client_public.copy_from_slice(&packet[8..40]);
        let mut nonce = [0u8; DNSCRYPT_NONCE_SIZE];
        nonce[..DNSCRYPT_QUERY_NONCE_HALF].copy_from_slice(&packet[40..52]);
        let cipher = fixture.server_cipher(&CryptoPublicKey::from(client_public));
        serve_dnscrypt_query(&packet, &fixture, &response_packet, &cipher, &nonce, &mut stream);
    });

    let resolver = EncryptedDnsResolver::new(dnscrypt_endpoint(&server, port), EncryptedDnsTransport::Direct)
        .expect("resolver builds");

    resolver.exchange_blocking(&query).expect_err("the flipped certificate cannot work");
    let response = resolver.exchange_blocking(&query).expect("a fresh certificate is fetched");
    assert_eq!(extract_ip_answers(&response).expect("answers parse"), vec![answer_ip.to_string()]);
    handle.join().expect("server thread completes");
}

#[test]
fn dnscrypt_keeps_the_certificate_after_a_timed_out_exchange() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 52);
    let server = DnsCryptTestServer::new("resolver.test");
    let response_packet = build_response(&query, answer_ip);
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("dnscrypt listener");
    let port = listener.local_addr().expect("dnscrypt local addr").port();
    let fixture = server.clone();
    let handle = thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("cert accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("cert read");
        let response = cert_response_with_records(&packet, &fixture.provider_name, &[fixture.certificate_bytes()]);
        write_length_prefixed_frame(&mut stream, &response).expect("cert write");

        // The first query gets no reply; the stream stays open until the thread ends.
        let (mut silent, _) = listener.accept().expect("first query accept");
        read_length_prefixed_frame(&mut silent).expect("first query read");

        let (mut stream, _) = listener.accept().expect("second query accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("second query read");
        assert!(!is_cert_query(&packet), "the client refetched the certificate after a timeout");
        let mut client_public = [0u8; 32];
        client_public.copy_from_slice(&packet[8..40]);
        let mut nonce = [0u8; DNSCRYPT_NONCE_SIZE];
        nonce[..DNSCRYPT_QUERY_NONCE_HALF].copy_from_slice(&packet[40..52]);
        let cipher = fixture.server_cipher(&CryptoPublicKey::from(client_public));
        serve_dnscrypt_query(&packet, &fixture, &response_packet, &cipher, &nonce, &mut stream);
    });

    let resolver = EncryptedDnsResolver::with_timeout_and_connect_hooks(
        dnscrypt_endpoint(&server, port),
        EncryptedDnsTransport::Direct,
        Duration::from_millis(300),
        EncryptedDnsConnectHooks::new(),
    )
    .expect("resolver builds");

    resolver.exchange_blocking(&query).expect_err("the silent server times out");
    let response = resolver.exchange_blocking(&query).expect("the cached certificate is reused");
    assert_eq!(extract_ip_answers(&response).expect("answers parse"), vec![answer_ip.to_string()]);
    handle.join().expect("server thread completes");
}

fn answer_encrypted_query(server: &DnsCryptTestServer, packet: &[u8], response_packet: &[u8], stream: &mut TcpStream) {
    let mut client_public = [0u8; 32];
    client_public.copy_from_slice(&packet[8..40]);
    let mut nonce = [0u8; DNSCRYPT_NONCE_SIZE];
    nonce[..DNSCRYPT_QUERY_NONCE_HALF].copy_from_slice(&packet[40..52]);
    let cipher = server.server_cipher(&CryptoPublicKey::from(client_public));
    serve_dnscrypt_query(packet, server, response_packet, &cipher, &nonce, stream);
}

fn bind_local() -> (TcpListener, u16) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("dnscrypt listener");
    let port = listener.local_addr().expect("dnscrypt local addr").port();
    (listener, port)
}

fn assert_no_pending_connection(listener: &TcpListener) {
    listener.set_nonblocking(true).expect("nonblocking listener");
    let accepted = listener.accept();
    assert!(
        matches!(&accepted, Err(error) if error.kind() == io::ErrorKind::WouldBlock),
        "the client connected: {accepted:?}"
    );
}

fn resolver_with_timeout(server: &DnsCryptTestServer, port: u16, timeout: Duration) -> EncryptedDnsResolver {
    EncryptedDnsResolver::with_timeout_and_connect_hooks(
        dnscrypt_endpoint(server, port),
        EncryptedDnsTransport::Direct,
        timeout,
        EncryptedDnsConnectHooks::new(),
    )
    .expect("resolver builds")
}

#[test]
fn dnscrypt_rejects_invalid_queries_before_any_io() {
    let server = DnsCryptTestServer::new("resolver.test");
    let (listener, port) = bind_local();
    let resolver = EncryptedDnsResolver::new(dnscrypt_endpoint(&server, port), EncryptedDnsTransport::Direct)
        .expect("resolver builds");

    let err = resolver.exchange_blocking(&vec![0u8; 4000]).unwrap_err();
    assert!(matches!(err, EncryptedDnsError::DnsMessageTooLarge { .. }), "{err:?}");

    let mut response_flag = build_query("fixture.test");
    response_flag[2] |= 0x80;
    let err = resolver.exchange_blocking(&response_flag).unwrap_err();
    assert!(matches!(err, EncryptedDnsError::DnsParse(_)), "{err:?}");

    let mut two_questions = build_query("fixture.test");
    two_questions[4..6].copy_from_slice(&[0x00, 0x02]);
    let err = resolver.exchange_blocking(&two_questions).unwrap_err();
    assert!(matches!(err, EncryptedDnsError::DnsParse(_)), "{err:?}");

    assert_no_pending_connection(&listener);
}

/// Serves one certificate query with `respond(query)` and returns the listener.
fn serve_one_cert_response(
    listener: TcpListener,
    respond: impl FnOnce(&[u8]) -> Vec<u8> + Send + 'static,
) -> thread::JoinHandle<TcpListener> {
    thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("cert accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("cert read");
        assert!(is_cert_query(&packet));
        write_length_prefixed_frame(&mut stream, &respond(&packet)).expect("cert write");
        listener
    })
}

fn assert_cert_fetch_fails(
    respond: impl FnOnce(&DnsCryptTestServer, &[u8]) -> Vec<u8> + Send + 'static,
) -> EncryptedDnsError {
    let query = build_query("fixture.test");
    let server = DnsCryptTestServer::new("resolver.test");
    let (listener, port) = bind_local();
    let fixture = server.clone();
    let handle = serve_one_cert_response(listener, move |packet| respond(&fixture, packet));
    let resolver = resolver_with_timeout(&server, port, Duration::from_millis(500));

    let err = resolver.exchange_blocking(&query).expect_err("the certificate response is not acceptable");
    assert_no_pending_connection(&handle.join().expect("server thread completes"));
    err
}

#[test]
fn dnscrypt_rejects_a_certificate_response_with_another_transaction_id() {
    assert_cert_fetch_fails(|server, packet| {
        let mut response = cert_response_with_records(packet, &server.provider_name, &[server.certificate_bytes()]);
        response[0] ^= 0xff;
        response[1] ^= 0xff;
        response
    });
}

#[test]
fn dnscrypt_rejects_a_servfail_certificate_response() {
    let err = assert_cert_fetch_fails(|server, packet| {
        let response = cert_response_with_records(packet, &server.provider_name, &[server.certificate_bytes()]);
        let mut message = Message::from_vec(&response).expect("response parses");
        message.metadata.response_code = ResponseCode::ServFail;
        message.to_vec().expect("response encodes")
    });
    assert!(matches!(err, EncryptedDnsError::DnsCryptCertificate(_)), "{err:?}");
}

#[test]
fn dnscrypt_ignores_a_certificate_under_another_owner_name() {
    let err = assert_cert_fetch_fails(|server, packet| {
        let request = Message::from_vec(packet).expect("cert query parses");
        let mut response = Message::response(request.metadata.id, OpCode::Query);
        response.metadata.response_code = ResponseCode::NoError;
        response.add_query(Query::new(Name::from_ascii(&server.provider_name).expect("name"), RecordType::TXT));
        response.add_answer(Record::from_rdata(
            Name::from_ascii("2.dnscrypt-cert.other.test").expect("name"),
            600,
            RData::TXT(TXT::from_bytes(vec![&server.certificate_bytes()])),
        ));
        response.to_vec().expect("response encodes")
    });
    assert!(matches!(err, EncryptedDnsError::DnsCryptCertificate(_)), "{err:?}");
}

#[test]
fn dnscrypt_does_not_refetch_the_certificate_after_a_forged_reply() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 53);
    let server = DnsCryptTestServer::new("resolver.test");
    let response_packet = build_response(&query, answer_ip);
    let (listener, port) = bind_local();
    let fixture = server.clone();
    let forged = {
        let mut frame = DNSCRYPT_RESPONSE_MAGIC.to_vec();
        frame.extend_from_slice(&[0xaa; DNSCRYPT_NONCE_SIZE]);
        frame.extend_from_slice(&[0x55; 48]);
        frame
    };
    let handle = thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("cert accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("cert read");
        let response = cert_response_with_records(&packet, &fixture.provider_name, &[fixture.certificate_bytes()]);
        write_length_prefixed_frame(&mut stream, &response).expect("cert write");

        // First query: answered; the client pools this stream.
        let (mut pooled, _) = listener.accept().expect("first query accept");
        let packet = read_length_prefixed_frame(&mut pooled).expect("first query read");
        answer_encrypted_query(&fixture, &packet, &response_packet, &mut pooled);

        // Second query: forged reply on the reused stream, then on the retry.
        read_length_prefixed_frame(&mut pooled).expect("second query read");
        write_length_prefixed_frame(&mut pooled, &forged).expect("forged write");
        let (mut retry, _) = listener.accept().expect("retry accept");
        read_length_prefixed_frame(&mut retry).expect("retry read");
        write_length_prefixed_frame(&mut retry, &forged).expect("forged retry write");

        // Third query: no certificate query comes first.
        let (mut stream, _) = listener.accept().expect("third query accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("third query read");
        assert!(!is_cert_query(&packet), "the client refetched the certificate after a forged reply");
        answer_encrypted_query(&fixture, &packet, &response_packet, &mut stream);
    });

    let resolver = EncryptedDnsResolver::new(dnscrypt_endpoint(&server, port), EncryptedDnsTransport::Direct)
        .expect("resolver builds");

    resolver.exchange_blocking(&query).expect("first exchange");
    resolver.exchange_blocking(&query).expect_err("forged replies fail the second exchange");
    let response = resolver.exchange_blocking(&query).expect("third exchange");
    assert_eq!(extract_ip_answers(&response).expect("answers parse"), vec![answer_ip.to_string()]);
    handle.join().expect("server thread completes");
}

#[test]
fn dnscrypt_keeps_a_valid_certificate_when_the_refresh_fails() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 54);
    let server = DnsCryptTestServer::new("resolver.test");
    let response_packet = build_response(&query, answer_ip);
    let (listener, port) = bind_local();
    let fixture = server.clone();
    let handle = thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("cert accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("cert read");
        let response = cert_response_with_records(&packet, &fixture.provider_name, &[fixture.certificate_bytes()]);
        write_length_prefixed_frame(&mut stream, &response).expect("cert write");

        // Closed without a reply: the client marks the certificate for refresh.
        let (mut stream, _) = listener.accept().expect("first query accept");
        read_length_prefixed_frame(&mut stream).expect("first query read");
        drop(stream);

        // The refresh gets no answer either.
        let (mut stream, _) = listener.accept().expect("refresh accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("refresh read");
        assert!(is_cert_query(&packet), "the client did not try to refresh");
        drop(stream);

        let (mut stream, _) = listener.accept().expect("second query accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("second query read");
        assert!(!is_cert_query(&packet), "the client did not use the cached certificate");
        answer_encrypted_query(&fixture, &packet, &response_packet, &mut stream);
    });

    let resolver = resolver_with_timeout(&server, port, Duration::from_millis(500));

    resolver.exchange_blocking(&query).expect_err("the closed connection fails the first exchange");
    let response = resolver.exchange_blocking(&query).expect("the cached certificate is used");
    assert_eq!(extract_ip_answers(&response).expect("answers parse"), vec![answer_ip.to_string()]);
    handle.join().expect("server thread completes");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn dnscrypt_fetches_the_certificate_once_for_concurrent_queries() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 55);
    let server = DnsCryptTestServer::new("resolver.test");
    let response_packet = build_response(&query, answer_ip);
    let (listener, port) = bind_local();
    let cert_queries = Arc::new(AtomicUsize::new(0));
    let counter = Arc::clone(&cert_queries);
    let fixture = server.clone();
    thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut stream) = stream else { return };
            let (fixture, response_packet, counter) = (fixture.clone(), response_packet.clone(), Arc::clone(&counter));
            thread::spawn(move || {
                while let Ok(packet) = read_length_prefixed_frame(&mut stream) {
                    if is_cert_query(&packet) {
                        counter.fetch_add(1, Ordering::SeqCst);
                        let response =
                            cert_response_with_records(&packet, &fixture.provider_name, &[fixture.certificate_bytes()]);
                        if write_length_prefixed_frame(&mut stream, &response).is_err() {
                            return;
                        }
                    } else {
                        answer_encrypted_query(&fixture, &packet, &response_packet, &mut stream);
                    }
                }
            });
        }
    });

    let resolver = EncryptedDnsResolver::new(dnscrypt_endpoint(&server, port), EncryptedDnsTransport::Direct)
        .expect("resolver builds");
    let mut tasks = tokio::task::JoinSet::new();
    for _ in 0..8 {
        let (resolver, query) = (resolver.clone(), query.clone());
        tasks.spawn(async move { resolver.exchange(&query).await });
    }
    while let Some(joined) = tasks.join_next().await {
        let response = joined.expect("task joins").expect("exchange succeeds");
        assert_eq!(extract_ip_answers(&response).expect("answers parse"), vec![answer_ip.to_string()]);
    }

    assert_eq!(cert_queries.load(Ordering::SeqCst), 1);
}

#[test]
fn dnscrypt_retries_on_a_new_connection_when_the_reused_one_is_silent() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 56);
    let server = DnsCryptTestServer::new("resolver.test");
    let response_packet = build_response(&query, answer_ip);
    let (listener, port) = bind_local();
    let fixture = server.clone();
    let handle = thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("cert accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("cert read");
        let response = cert_response_with_records(&packet, &fixture.provider_name, &[fixture.certificate_bytes()]);
        write_length_prefixed_frame(&mut stream, &response).expect("cert write");

        let (mut pooled, _) = listener.accept().expect("first query accept");
        let packet = read_length_prefixed_frame(&mut pooled).expect("first query read");
        answer_encrypted_query(&fixture, &packet, &response_packet, &mut pooled);

        // The pooled connection stays open but the next frame is ignored.
        read_length_prefixed_frame(&mut pooled).expect("ignored frame read");
        let (mut stream, _) = listener.accept().expect("retry accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("retry read");
        answer_encrypted_query(&fixture, &packet, &response_packet, &mut stream);
        drop(pooled);
    });

    let resolver = resolver_with_timeout(&server, port, Duration::from_millis(2000));

    resolver.exchange_blocking(&query).expect("first exchange");
    let started = std::time::Instant::now();
    let response = resolver.exchange_blocking(&query).expect("the new connection answers");
    let elapsed = started.elapsed();
    assert_eq!(extract_ip_answers(&response).expect("answers parse"), vec![answer_ip.to_string()]);
    assert!(elapsed < Duration::from_millis(1500), "silent reused connection cost {elapsed:?}");
    handle.join().expect("server thread completes");
}

#[test]
fn dnscrypt_does_not_retry_a_failed_refresh_at_once() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 57);
    let server = DnsCryptTestServer::new("resolver.test");
    let response_packet = build_response(&query, answer_ip);
    let (listener, port) = bind_local();
    let fixture = server.clone();
    let handle = thread::spawn(move || {
        let mut cert_queries = 0usize;
        let (mut stream, _) = listener.accept().expect("cert accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("cert read");
        assert!(is_cert_query(&packet));
        cert_queries += 1;
        let response = cert_response_with_records(&packet, &fixture.provider_name, &[fixture.certificate_bytes()]);
        write_length_prefixed_frame(&mut stream, &response).expect("cert write");

        // Closed without a reply: the certificate is marked stale.
        let (mut stream, _) = listener.accept().expect("first query accept");
        read_length_prefixed_frame(&mut stream).expect("first query read");
        drop(stream);

        // The refresh is closed without a reply.
        let (mut stream, _) = listener.accept().expect("refresh accept");
        let packet = read_length_prefixed_frame(&mut stream).expect("refresh read");
        assert!(is_cert_query(&packet));
        cert_queries += 1;
        drop(stream);

        // Second query: cached certificate. Third query: no new certificate query.
        for _ in 0..2 {
            let (mut stream, _) = listener.accept().expect("query accept");
            let packet = read_length_prefixed_frame(&mut stream).expect("query read");
            assert!(!is_cert_query(&packet), "the client refetched the certificate inside the backoff");
            answer_encrypted_query(&fixture, &packet, &response_packet, &mut stream);
            // The client pools this stream; the next query reuses it or reconnects.
        }
        cert_queries
    });

    let resolver = resolver_with_timeout(&server, port, Duration::from_millis(500));

    resolver.exchange_blocking(&query).expect_err("the closed connection fails the first exchange");
    resolver.exchange_blocking(&query).expect("the cached certificate answers the second query");
    resolver.exchange_blocking(&query).expect("the third query does not refetch");
    assert_eq!(handle.join().expect("server thread completes"), 2);
}
