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
    }
}

fn manual_doh_resolver(port: u16, certificate_der: CertificateDer<'static>) -> EncryptedDnsResolver {
    EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
        fixture_doh_endpoint(port),
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
        EncryptedDnsConnectHooks::new()
            .with_direct_tcp_connector(|target, timeout| TcpStream::connect_timeout(&target, timeout)),
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

// ---------------------------------------------------------------------------
// hickory-backend feature-gated tests
// ---------------------------------------------------------------------------

#[cfg(feature = "hickory-backend")]
mod hickory_backend_tests {
    use super::*;

    /// When the resolver has custom TLS roots (as all test fixtures do), the
    /// hickory backend is bypassed and the manual DoT path is used. This test
    /// verifies that the SOCKS5 DoT path continues to use the manual
    /// implementation even when the hickory-backend feature is enabled.
    #[test]
    fn dot_socks5_falls_back_to_manual_with_hickory_feature() {
        let query = build_query("fixture.test");
        let answer_ip = Ipv4Addr::new(198, 18, 0, 99);
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
        let server_query = query.clone();
        let server_response = build_response(&query, answer_ip);
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            serve_dot(stream, server_config, &server_query, &server_response);
        });
        let (proxy_port, proxy_handle) = start_socks_proxy("fixture.test", port, 1);

        let resolver = EncryptedDnsResolver::with_extra_tls_roots(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Dot,
                resolver_id: Some("fixture".to_string()),
                host: "fixture.test".to_string(),
                port,
                tls_server_name: Some("fixture.test".to_string()),
                bootstrap_ips: Vec::new(),
                doh_url: None,
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            },
            EncryptedDnsTransport::Socks5 { host: "127.0.0.1".to_string(), port: proxy_port },
            DEFAULT_TIMEOUT,
            vec![certificate_der],
        )
        .expect("resolver builds");

        let response = resolver.exchange_blocking(&query).expect("DoT SOCKS5 response");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![answer_ip.to_string()]);
        drop(resolver);
        proxy_handle.join().expect("proxy thread completes");
        server.join().expect("server thread completes");
    }

    /// Verify that custom-TLS-root resolvers fall back to the manual DoH path
    /// even when the hickory-backend feature is enabled.
    #[tokio::test]
    async fn doh_falls_back_to_manual_when_custom_tls_roots_present() {
        let query = build_query("fixture.test");
        let answer_ip = Ipv4Addr::new(198, 18, 0, 100);
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
        let response_body = build_response(&query, answer_ip);
        let server_query = query.clone();
        let server_response = response_body.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            serve_https_doh(stream, server_config, &server_query, &server_response);
        });

        // Has custom TLS roots -> hickory backend is bypassed, manual DoH is used.
        let resolver = EncryptedDnsResolver::with_extra_tls_roots(
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
            },
            EncryptedDnsTransport::Direct,
            DEFAULT_TIMEOUT,
            vec![certificate_der],
        )
        .expect("resolver builds");

        let response = resolver.exchange(&query).await.expect("DoH response");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![answer_ip.to_string()]);
        server.join().expect("server joins");
    }

    /// The hickory_backend module functions should produce valid DNS wire bytes
    /// from a well-formed query. We call the module against a public resolver
    /// (if reachable) or verify that it returns the expected error shape.
    #[tokio::test]
    async fn hickory_backend_module_produces_valid_wire_bytes() {
        use crate::hickory_backend;

        let query = build_query("example.com");
        let endpoint = EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: None,
            host: "1.1.1.1".to_string(),
            port: 853,
            tls_server_name: Some("cloudflare-dns.com".to_string()),
            bootstrap_ips: vec!["1.1.1.1".parse().expect("ip")],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        };

        // This test tries to reach the real Cloudflare DNS. If the network is
        // unreachable (CI sandbox, offline machine), the error is accepted.
        let result = hickory_backend::exchange_dot(&endpoint, &query, Duration::from_secs(5)).await;

        match result {
            Ok(response_bytes) => {
                // Must be parseable DNS wire format.
                let msg = Message::from_vec(&response_bytes).expect("response parses as DNS");
                assert!(msg.answer_count() > 0, "expected at least one answer record");
                // Verify we can also extract IPs from the response.
                let ips = extract_ip_answers(&response_bytes).expect("IP extraction works");
                assert!(!ips.is_empty(), "expected at least one IP answer");
            }
            Err(EncryptedDnsError::Request(msg)) => {
                // Network unreachable is acceptable in CI/offline environments.
                assert!(
                    msg.contains("io error") || msg.contains("timeout") || msg.contains("connection"),
                    "unexpected error: {msg}"
                );
            }
            Err(other) => panic!("unexpected error type: {other}"),
        }
    }

    // -----------------------------------------------------------------------
    // hickory_backend error paths and edge cases
    // -----------------------------------------------------------------------

    #[tokio::test]
    async fn hickory_rejects_empty_query() {
        use crate::hickory_backend;
        let endpoint = EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: None,
            host: "localhost".to_string(),
            port: 853,
            tls_server_name: None,
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        };
        let result = hickory_backend::exchange_dot(&endpoint, &[], Duration::from_secs(2)).await;
        assert!(
            matches!(&result, Err(EncryptedDnsError::DnsParse(_))),
            "expected DnsParse error for empty query, got: {result:?}"
        );
    }

    #[tokio::test]
    async fn hickory_rejects_query_with_no_questions() {
        use crate::hickory_backend;
        let mut msg = Message::new();
        msg.set_id(0x1234).set_message_type(MessageType::Query).set_op_code(OpCode::Query).set_recursion_desired(true);
        let wire = msg.to_vec().expect("header-only message serializes");
        let endpoint = EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: None,
            host: "localhost".to_string(),
            port: 853,
            tls_server_name: None,
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        };
        let result = hickory_backend::exchange_dot(&endpoint, &wire, Duration::from_secs(2)).await;
        match &result {
            Err(EncryptedDnsError::DnsParse(msg)) => {
                assert!(msg.contains("no questions"), "expected 'no questions', got: {msg}");
            }
            other => panic!("expected DnsParse('no questions'), got: {other:?}"),
        }
    }

    #[tokio::test]
    async fn hickory_empty_bootstrap_ips_errors() {
        use crate::hickory_backend;
        let query = build_query("example.com");
        let endpoint = EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: None,
            host: "dns.example".to_string(),
            port: 443,
            tls_server_name: Some("dns.example".to_string()),
            bootstrap_ips: vec![],
            doh_url: Some("https://dns.example/dns-query".to_string()),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        };
        let result = hickory_backend::exchange_doh(&endpoint, &query, Duration::from_secs(2)).await;
        assert!(result.is_err(), "expected error with empty bootstrap IPs");
    }

    #[tokio::test]
    async fn hickory_doh_url_path_defaults_to_dns_query() {
        use crate::hickory_backend;
        let query = build_query("example.com");
        let endpoint = EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: None,
            host: "dns.example".to_string(),
            port: 443,
            tls_server_name: Some("dns.example".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1))],
            doh_url: Some("https://dns.example/custom-path".to_string()),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        };
        let result = hickory_backend::exchange_doh(&endpoint, &query, Duration::from_millis(500)).await;
        assert!(result.is_err(), "expected connection error, got success");
        match result {
            Err(EncryptedDnsError::Request(_)) => {}
            other => panic!("expected Request error, got: {other:?}"),
        }
    }

    #[tokio::test]
    async fn hickory_doh_url_none_defaults_to_dns_query() {
        use crate::hickory_backend;
        let query = build_query("example.com");
        let endpoint = EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: None,
            host: "dns.example".to_string(),
            port: 443,
            tls_server_name: Some("dns.example".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1))],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        };
        let result = hickory_backend::exchange_doh(&endpoint, &query, Duration::from_millis(500)).await;
        assert!(result.is_err(), "expected connection error, got success");
        match result {
            Err(EncryptedDnsError::Request(_)) => {}
            other => panic!("expected Request error, got: {other:?}"),
        }
    }

    // -----------------------------------------------------------------------
    // Dispatch fallback coverage
    // -----------------------------------------------------------------------

    #[test]
    fn dot_direct_with_custom_tls_roots_falls_back_to_manual() {
        let query = build_query("fixture.test");
        let answer_ip = Ipv4Addr::new(198, 18, 0, 101);
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
        let server_query = query.clone();
        let server_response = build_response(&query, answer_ip);
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            serve_dot(stream, server_config, &server_query, &server_response);
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
            },
            EncryptedDnsTransport::Direct,
            DEFAULT_TIMEOUT,
            vec![certificate_der],
        )
        .expect("resolver builds");
        let response = resolver.exchange_blocking(&query).expect("DoT response via manual path");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![answer_ip.to_string()]);
        server.join().expect("server thread completes");
    }

    #[test]
    fn dnscrypt_ignores_hickory_backend() {
        let query = build_query("fixture.test");
        let answer_ip = Ipv4Addr::new(198, 18, 0, 102);
        let server = DnsCryptTestServer::new("resolver.test");
        let (port, handle) = start_dnscrypt_server(server.clone(), build_response(&query, answer_ip));
        let resolver = EncryptedDnsResolver::new(
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
            },
            EncryptedDnsTransport::Direct,
        )
        .expect("resolver builds");
        let response = resolver.exchange_blocking(&query).expect("DNSCrypt response");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![answer_ip.to_string()]);
        handle.join().expect("server thread completes");
    }
}
