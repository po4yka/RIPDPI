use super::*;

#[test]
fn dot_rejects_wrong_pin_even_when_the_certificate_is_trusted() {
    let query = build_query("fixture.test");
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let port = listener.local_addr().unwrap().port();
    let certificate = generate_simple_self_signed(vec!["fixture.test".into()]).unwrap();
    let cert = certificate.cert.der().clone();
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let config = Arc::new(
        ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(vec![cert.clone()], key)
            .unwrap(),
    );
    let expected_query = query.clone();
    let response = build_response(&query, Ipv4Addr::new(198, 18, 0, 77));
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().unwrap();
        stream.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        let mut tls = StreamOwned::new(ServerConnection::new(config).unwrap(), stream);
        let mut length = [0; 2];
        if tls.read_exact(&mut length).is_err() {
            return false;
        }
        let mut received = vec![0; usize::from(u16::from_be_bytes(length))];
        tls.read_exact(&mut received).unwrap();
        assert_eq!(received, expected_padded_query(&expected_query));
        tls.write_all(&(response.len() as u16).to_be_bytes()).unwrap();
        tls.write_all(&response).unwrap();
        tls.flush().unwrap();
        true
    });
    let hooks = EncryptedDnsConnectHooks::new().with_dot_tls_connector_builder(move || {
        let mut builder = ripdpi_tls_profiles::configure_builder("chrome_stable").map_err(|error| error.to_string())?;
        builder
            .cert_store_mut()
            .add_cert(boring::x509::X509::from_der(cert.as_ref()).map_err(|error| error.to_string())?)
            .map_err(|error| error.to_string())?;
        Ok(builder)
    });
    let resolver = EncryptedDnsResolver::with_pinned_tls_verifier_and_connect_hooks(
        dot_fixture_endpoint(port),
        EncryptedDnsTransport::Direct,
        Arc::new(TlsPinVerifier::from_spki_sha256([[7; 32]])),
        hooks,
    )
    .unwrap();

    let result = resolver.exchange_blocking(&query);
    let received_dns = server.join().unwrap();
    assert!(result.is_err(), "a trusted certificate cannot bypass the configured pin");
    assert!(!received_dns, "a rejected pin must not send DNS application bytes");
}
