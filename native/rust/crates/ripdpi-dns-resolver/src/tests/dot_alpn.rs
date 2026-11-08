use super::*;

#[derive(Clone, Copy)]
enum DotPath {
    Direct,
    Socks5,
}

fn dot_alpn_exchange(
    path: DotPath,
    custom_builder: bool,
    server_alpn: Option<&[u8]>,
    version: &'static rustls::SupportedProtocolVersion,
) -> (Result<Vec<u8>, EncryptedDnsError>, Result<Option<Vec<u8>>, io::Error>) {
    let query = build_query("fixture.test");
    let client_query = query.clone();
    let response = build_response(&query, Ipv4Addr::new(198, 18, 0, 61));
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let mut server_config = ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_protocol_versions(&[version])
        .expect("server TLS version")
        .with_no_client_auth()
        .with_single_cert(vec![certificate_der.clone()], key_der)
        .expect("server config");
    server_config.alpn_protocols = server_alpn.into_iter().map(<[u8]>::to_vec).collect();
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("listener addr").port();
    let server = thread::spawn(move || -> Result<Option<Vec<u8>>, io::Error> {
        let (stream, _) = listener.accept()?;
        stream.set_read_timeout(Some(Duration::from_secs(3)))?;
        stream.set_write_timeout(Some(Duration::from_secs(3)))?;
        let connection = ServerConnection::new(Arc::new(server_config)).expect("server connection");
        let mut tls_stream = StreamOwned::new(connection, stream);
        while tls_stream.conn.is_handshaking() {
            tls_stream.conn.complete_io(&mut tls_stream.sock)?;
        }
        let selected = tls_stream.conn.alpn_protocol().map(<[u8]>::to_vec);
        assert_eq!(read_length_prefixed_frame(&mut tls_stream)?, query);
        write_length_prefixed_frame(&mut tls_stream, &response)?;
        Ok(selected)
    });
    let mut endpoint = dot_fixture_endpoint(port);
    let (transport, proxy) = match path {
        DotPath::Direct => (EncryptedDnsTransport::Direct, None),
        DotPath::Socks5 => {
            endpoint.bootstrap_ips.clear();
            let (port, handle) = start_socks_proxy("fixture.test", port, 1);
            (EncryptedDnsTransport::Socks5 { host: "127.0.0.1".into(), port, credentials: None }, Some(handle))
        }
    };
    let mut hooks = EncryptedDnsConnectHooks::new();
    if custom_builder {
        hooks = hooks.with_dot_tls_connector_builder(|| {
            ripdpi_tls_profiles::configure_builder("firefox_stable").map_err(|error| error.to_string())
        });
    }
    let resolver = EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
        endpoint,
        transport,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
        hooks,
    )
    .expect("resolver builds");
    let result = resolver.exchange_blocking(&client_query);
    drop(resolver);
    if let Some(proxy) = proxy {
        proxy.join().expect("proxy completes");
    }
    (result, server.join().expect("server completes"))
}

#[test]
fn dot_alpn_negotiates_dot_with_default_and_custom_builders() {
    for version in [&rustls::version::TLS12, &rustls::version::TLS13] {
        for path in [DotPath::Direct, DotPath::Socks5] {
            for custom_builder in [false, true] {
                let (result, selected) = dot_alpn_exchange(path, custom_builder, Some(b"dot"), version);
                let response = result.expect("DoT peer selects dot");
                assert_eq!(extract_ip_answers(&response).expect("answers"), vec!["198.18.0.61"]);
                assert_eq!(selected.expect("server exchanges DNS"), Some(b"dot".to_vec()));
            }
        }
    }
}

#[test]
fn dot_alpn_preserves_legacy_peers_without_alpn() {
    for version in [&rustls::version::TLS12, &rustls::version::TLS13] {
        for path in [DotPath::Direct, DotPath::Socks5] {
            for custom_builder in [false, true] {
                let (result, selected) = dot_alpn_exchange(path, custom_builder, None, version);
                assert_eq!(
                    extract_ip_answers(&result.expect("legacy DNS exchange")).expect("answers"),
                    vec!["198.18.0.61"],
                );
                assert_eq!(selected.expect("server exchanges DNS"), None);
            }
        }
    }
}

#[test]
fn dot_alpn_rejects_http_only_peers_before_dns() {
    for protocol in [b"h2".as_slice(), b"http/1.1".as_slice()] {
        let (result, observed) = dot_alpn_exchange(DotPath::Direct, true, Some(protocol), &rustls::version::TLS13);
        assert!(result.is_err(), "HTTP-only peer must not receive a DNS query");
        assert!(observed.is_err(), "no DNS frame may follow incompatible ALPN");
    }
}
