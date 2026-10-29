use super::*;

// ---------------------------------------------------------------------------
// Pool cold-start and fallback-cache tests
// ---------------------------------------------------------------------------

fn spawn_doh_fixture(
    query: &[u8],
    answer_ip: Ipv4Addr,
    accept_count: usize,
) -> (u16, CertificateDer<'static>, Vec<thread::JoinHandle<()>>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = rcgen::generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
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
    let response_body = build_response(query, answer_ip);
    let server_query = query.to_vec();
    let config = server_config;
    let handle = thread::spawn(move || {
        for _ in 0..accept_count {
            let (stream, _) = listener.accept().expect("accept");
            serve_https_doh(stream, config.clone(), &server_query, &response_body);
        }
    });
    (port, certificate_der, vec![handle])
}

fn spawn_custom_doh_fixture(
    query: &[u8],
    response_parts: Vec<Vec<u8>>,
) -> (u16, CertificateDer<'static>, thread::JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = rcgen::generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
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
    let server_query = query.to_vec();
    let handle = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_https_doh_raw(stream, server_config, &server_query, &response_parts);
    });
    (port, certificate_der, handle)
}

fn fixture_doh_endpoint(port: u16) -> EncryptedDnsEndpoint {
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
    }
}

fn manual_doh_resolver(port: u16, certificate_der: CertificateDer<'static>) -> EncryptedDnsResolver {
    EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
        fixture_doh_endpoint(port),
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
        EncryptedDnsConnectHooks::new()
            .with_direct_tcp_connector(|target, timeout| async move { TcpStream::connect_timeout(&target, timeout) }),
    )
    .expect("resolver builds")
}

#[tokio::test]
async fn manual_doh_rejects_oversized_content_length_response() {
    let query = build_query("fixture.test");
    let oversized_length = 65_536usize;
    let response_parts = vec![format!(
        "HTTP/1.1 200 OK\r\nContent-Type: {DNS_MESSAGE_MEDIA_TYPE}\r\nContent-Length: {oversized_length}\r\nConnection: close\r\n\r\n"
    )
    .into_bytes()];
    let (port, certificate_der, server) = spawn_custom_doh_fixture(&query, response_parts);
    let resolver = manual_doh_resolver(port, certificate_der);

    let error = resolver.exchange(&query).await.expect_err("oversized Content-Length should be rejected");

    match error {
        EncryptedDnsError::Request(message) => {
            assert!(message.contains("Content-Length exceeds maximum size"));
        }
        other => panic!("expected request error, got {other:?}"),
    }
    server.join().expect("server joins");
}

#[tokio::test]
async fn manual_doh_rejects_oversized_chunked_response() {
    let query = build_query("fixture.test");
    let response_parts = vec![
        format!(
            "HTTP/1.1 200 OK\r\nContent-Type: {DNS_MESSAGE_MEDIA_TYPE}\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
        )
        .into_bytes(),
        b"10000\r\n".to_vec(),
    ];
    let (port, certificate_der, server) = spawn_custom_doh_fixture(&query, response_parts);
    let resolver = manual_doh_resolver(port, certificate_der);

    let error = resolver.exchange(&query).await.expect_err("oversized chunked response should be rejected");

    match error {
        EncryptedDnsError::Request(message) => {
            assert!(message.contains("chunked DoH response exceeds maximum size"));
        }
        other => panic!("expected request error, got {other:?}"),
    }
    server.join().expect("server joins");
}

#[tokio::test]
async fn manual_doh_rejects_oversized_unframed_response() {
    let query = build_query("fixture.test");
    let response_parts = vec![
        format!("HTTP/1.1 200 OK\r\nContent-Type: {DNS_MESSAGE_MEDIA_TYPE}\r\nConnection: close\r\n\r\n").into_bytes(),
        vec![0u8; 65_536],
    ];
    let (port, certificate_der, server) = spawn_custom_doh_fixture(&query, response_parts);
    let resolver = manual_doh_resolver(port, certificate_der);

    let error = resolver.exchange(&query).await.expect_err("oversized unframed response should be rejected");

    match error {
        EncryptedDnsError::Request(message) => {
            assert!(message.contains("DoH response body exceeds maximum size"));
        }
        other => panic!("expected request error, got {other:?}"),
    }
    server.join().expect("server joins");
}

#[test]
fn pool_cold_start_with_empty_cache_uses_rank_zero() {
    let query = build_query("fixture.test");
    let (port, cert_der, handles) = spawn_doh_fixture(&query, Ipv4Addr::new(198, 18, 0, 50), 1);
    let health = HealthRegistry::new(Duration::from_secs(60));
    let pool = ResolverPool::builder()
        .add_endpoint(fixture_doh_endpoint(port), EncryptedDnsTransport::Direct)
        .add_endpoint(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Doh,
                resolver_id: Some("unreachable".to_string()),
                host: "unreachable.test".to_string(),
                port: 1,
                tls_server_name: Some("unreachable.test".to_string()),
                bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1))],
                doh_url: Some("https://unreachable.test:1/dns-query".to_string()),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
                odoh: None,
            },
            EncryptedDnsTransport::Direct,
        )
        .tls_roots(vec![cert_der])
        .health_registry(health)
        .build()
        .unwrap();
    let result = pool.exchange_blocking(&query);
    assert!(result.is_ok(), "cold-start exchange should succeed via rank-0");
    for h in handles {
        h.join().expect("server join");
    }
}

#[test]
fn pool_records_success_in_shared_health_registry() {
    let query = build_query("fixture.test");
    let (port, cert_der, handles) = spawn_doh_fixture(&query, Ipv4Addr::new(198, 18, 0, 51), 2);
    let shared_health = HealthRegistry::new(Duration::from_secs(60));
    let pool1 = ResolverPool::builder()
        .add_endpoint(fixture_doh_endpoint(port), EncryptedDnsTransport::Direct)
        .tls_roots(vec![cert_der.clone()])
        .health_registry(shared_health.clone())
        .build()
        .unwrap();
    let result = pool1.exchange_blocking(&query);
    assert!(result.is_ok(), "first pool exchange should succeed");
    let label = format!("https://fixture.test:{port}/dns-query");
    assert!(shared_health.observation_count(&label) > 0, "health registry should have observations after success");
    let pool2 = ResolverPool::builder()
        .add_endpoint(fixture_doh_endpoint(port), EncryptedDnsTransport::Direct)
        .tls_roots(vec![cert_der])
        .health_registry(shared_health.clone())
        .build()
        .unwrap();
    let result2 = pool2.exchange_blocking(&query);
    assert!(result2.is_ok(), "second pool exchange should succeed using shared health");
    for h in handles {
        h.join().expect("server join");
    }
}
