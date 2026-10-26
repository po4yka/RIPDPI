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
