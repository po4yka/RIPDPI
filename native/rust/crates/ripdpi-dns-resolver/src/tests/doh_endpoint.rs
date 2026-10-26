use super::*;

/// Runs one DoH exchange against a server whose certificate names `other.test`
/// while the endpoint expects `fixture.test`; the certificate is otherwise trusted.
async fn exchange_with_foreign_certificate(connect_hooks: EncryptedDnsConnectHooks) -> EncryptedDnsError {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = generate_simple_self_signed(vec!["other.test".to_string()]).expect("certificate");
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
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        tolerate_dot_handshake_failure(stream, server_config);
    });
    let resolver = EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("fixture".to_string()),
            host: "fixture.test".to_string(),
            port,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: Some(format!("https://fixture.test:{port}/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        },
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
        connect_hooks,
    )
    .expect("resolver builds");

    let error = resolver.exchange(&build_query("fixture.test")).await.expect_err("foreign certificate must fail");
    server.join().expect("server joins");
    error
}

#[tokio::test]
async fn doh_rejects_a_certificate_for_another_name() {
    let error = exchange_with_foreign_certificate(EncryptedDnsConnectHooks::new()).await;

    assert!(error.to_string().contains("not valid for name"), "error: {error}");
}

#[tokio::test]
async fn hooked_doh_rejects_a_certificate_for_another_name() {
    let hooks = EncryptedDnsConnectHooks::new()
        .with_direct_tcp_connector(|target, timeout| async move { TcpStream::connect_timeout(&target, timeout) });

    let error = exchange_with_foreign_certificate(hooks).await;

    assert!(error.to_string().contains("not valid for name"), "error: {error}");
}

#[tokio::test]
async fn doh_uses_bootstrap_ips_when_the_url_host_differs_from_the_endpoint_host() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 60);
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
    let server_query = query.clone();
    let server_response = build_response(&query, answer_ip);
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_https_doh(stream, server_config, &server_query, &server_response);
    });
    // Like the dnssb catalog entry: the endpoint host is not the host in the URL.
    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("fixture".to_string()),
            host: "alias.fixture.test".to_string(),
            port,
            tls_server_name: Some("alias.fixture.test".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: Some(format!("https://fixture.test:{port}/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        },
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
    )
    .expect("resolver builds");

    let response = resolver.exchange(&query).await.expect("DoH reaches the bootstrap IP");
    assert_eq!(extract_ip_answers(&response).expect("answers parse"), vec![answer_ip.to_string()]);
    server.join().expect("server joins");
}

#[tokio::test]
async fn hooked_doh_client_hello_offers_http1_and_grease_ech() {
    let (client, mut server) = tokio::io::duplex(16 * 1024);
    let client = std::sync::Mutex::new(Some(client));
    let hooks = EncryptedDnsConnectHooks::new().with_direct_tcp_connector(move |_, _| {
        let stream = client.lock().expect("stream lock").take().map(|stream| Box::new(stream) as BoxedDnsTcpStream);
        async move { stream.ok_or_else(|| std::io::Error::other("one connection only")) }
    });
    let resolver = EncryptedDnsResolver::with_connect_hooks(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("fixture".to_string()),
            host: "fixture.test".to_string(),
            port: 443,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: Some("https://fixture.test/dns-query".to_string()),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        },
        EncryptedDnsTransport::Direct,
        hooks,
    )
    .expect("resolver builds");
    let exchange = tokio::spawn(async move {
        let _ = resolver.exchange(&build_query("fixture.test")).await;
    });

    let mut header = [0u8; 5];
    tokio::io::AsyncReadExt::read_exact(&mut server, &mut header).await.expect("TLS record header");
    let mut record = header.to_vec();
    record.resize(5 + usize::from(u16::from_be_bytes([header[3], header[4]])), 0);
    tokio::io::AsyncReadExt::read_exact(&mut server, &mut record[5..]).await.expect("ClientHello");
    exchange.abort();

    let layout = ripdpi_packets::parse_tls_client_hello_layout(&record).expect("parse ClientHello");
    assert!(layout.extensions.iter().any(|extension| extension.ext_type == 0xfe0d), "no GREASE ECH");
    let alpn = layout.extensions.iter().find(|extension| extension.ext_type == 0x0010).expect("ALPN extension");
    // ALPN list length, then one length-prefixed protocol name.
    assert_eq!(&record[alpn.data_offset..alpn.data_offset + alpn.data_len], b"\x00\x09\x08http/1.1");
}
