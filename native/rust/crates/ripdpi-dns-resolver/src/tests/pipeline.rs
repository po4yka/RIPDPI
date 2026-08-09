use super::*;
use crate::{DohBatchRecordType, DohIpFamily, DohResolverPipeline, DohResolverRole, doh_ip_answer_candidates};
use std::sync::atomic::{AtomicUsize, Ordering};

enum DohFixtureResponse {
    Dns(Vec<u8>),
    HttpError,
}

impl From<Vec<u8>> for DohFixtureResponse {
    fn from(value: Vec<u8>) -> Self {
        Self::Dns(value)
    }
}

fn spawn_batched_doh_fixture<T>(
    expected_domain: &str,
    expected_types: &[RecordType],
    responses: Vec<T>,
) -> (u16, CertificateDer<'static>, Arc<AtomicUsize>, thread::JoinHandle<()>)
where
    T: Into<DohFixtureResponse> + Send + 'static,
{
    let expected_types = expected_types.to_vec();
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
    let query_count = Arc::new(AtomicUsize::new(0));
    let observed_count = query_count.clone();
    let expected_domain = expected_domain.to_string();
    let handle = thread::spawn(move || {
        for (expected_type, response) in expected_types.into_iter().zip(responses) {
            let (stream, _) = listener.accept().expect("accept");
            let connection = ServerConnection::new(server_config.clone()).expect("server connection");
            let mut tls_stream = StreamOwned::new(connection, stream);
            while tls_stream.conn.is_handshaking() {
                tls_stream.conn.complete_io(&mut tls_stream.sock).expect("TLS handshake completes");
            }

            let (request_line, body) = read_http_request(&mut tls_stream);
            assert_eq!(request_line, "POST /dns-query HTTP/1.1");
            let message = Message::from_vec(&body).expect("query parses");
            let query = message.queries.first().expect("query section");
            assert_eq!(query.name.to_ascii(), expected_domain);
            assert_eq!(query.query_type, expected_type);
            observed_count.fetch_add(1, Ordering::Relaxed);

            let response = response.into();
            let (response_head, response_body) = match response {
                DohFixtureResponse::Dns(response_body) => (
                    format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: {DNS_MESSAGE_MEDIA_TYPE}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        response_body.len()
                    ),
                    response_body,
                ),
                DohFixtureResponse::HttpError => (
                    "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_string(),
                    Vec::new(),
                ),
            };
            tls_stream.write_all(response_head.as_bytes()).expect("write response head");
            tls_stream.write_all(&response_body).expect("write response body");
            tls_stream.flush().expect("flush response");
        }
    });
    (port, certificate_der, query_count, handle)
}

fn responses_with_only_ipv4_success(domain: &str) -> Vec<DohFixtureResponse> {
    [
        DohBatchRecordType::A,
        DohBatchRecordType::Aaaa,
        DohBatchRecordType::Cname,
        DohBatchRecordType::Https,
        DohBatchRecordType::Svcb,
    ]
    .iter()
    .map(|record_type| match record_type {
        DohBatchRecordType::A => {
            DohFixtureResponse::Dns(build_response_with_record(&build_query_for_type(domain, RecordType::A), 120))
        }
        _ => DohFixtureResponse::HttpError,
    })
    .collect()
}

fn fixture_endpoint(port: u16, resolver_id: &str) -> EncryptedDnsEndpoint {
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Doh,
        resolver_id: Some(resolver_id.to_string()),
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

fn pipeline_with_fixture(
    primary: EncryptedDnsEndpoint,
    secondary: EncryptedDnsEndpoint,
    certificate: CertificateDer<'static>,
) -> DohResolverPipeline {
    DohResolverPipeline::builder()
        .primary_endpoint(primary)
        .secondary_endpoint(secondary)
        .tls_roots(vec![certificate])
        .connect_hooks(
            EncryptedDnsConnectHooks::new().with_direct_tcp_connector(|target, timeout| async move {
                TcpStream::connect_timeout(&target, timeout)
            }),
        )
        .build()
        .expect("pipeline builds")
}

#[test]
fn doh_pipeline_queries_primary_batch_and_returns_record_order() {
    let expected_types = [RecordType::A, RecordType::AAAA, RecordType::CNAME, RecordType::HTTPS, RecordType::SVCB];
    let response_bodies = expected_types
        .iter()
        .map(|record_type| {
            let query = build_query_for_type("fixture.test", *record_type);
            match record_type {
                RecordType::A | RecordType::AAAA | RecordType::CNAME => build_response_with_record(&query, 120),
                _ => build_empty_response(&query),
            }
        })
        .collect::<Vec<_>>();
    let (port, certificate, query_count, server) =
        spawn_batched_doh_fixture("fixture.test.", &expected_types, response_bodies);
    let pipeline =
        pipeline_with_fixture(fixture_endpoint(port, "primary"), fixture_endpoint(port, "secondary"), certificate);

    let lookup = pipeline.resolve_blocking("fixture.test").expect("batch lookup succeeds");

    assert_eq!(lookup.resolver_role, DohResolverRole::Primary);
    assert_eq!(
        lookup.records.iter().map(|record| record.record_type).collect::<Vec<_>>(),
        vec![
            DohBatchRecordType::A,
            DohBatchRecordType::Aaaa,
            DohBatchRecordType::Cname,
            DohBatchRecordType::Https,
            DohBatchRecordType::Svcb,
        ],
    );
    assert_eq!(lookup.cache_ttl_secs, Some(120));
    assert_eq!(query_count.load(Ordering::Relaxed), 5);
    server.join().expect("server joins");
}

#[test]
fn doh_pipeline_blocking_keeps_primary_address_when_optional_rr_requests_fail() {
    let expected_types = [RecordType::A, RecordType::AAAA, RecordType::CNAME, RecordType::HTTPS, RecordType::SVCB];
    let (port, certificate, query_count, server) =
        spawn_batched_doh_fixture("fixture.test.", &expected_types, responses_with_only_ipv4_success("fixture.test"));
    let pipeline =
        pipeline_with_fixture(fixture_endpoint(port, "primary"), fixture_endpoint(port, "secondary"), certificate);

    let lookup = pipeline.resolve_blocking("fixture.test").expect("IPv4 answer is sufficient for bootstrap");

    assert_eq!(lookup.resolver_role, DohResolverRole::Primary);
    assert_eq!(lookup.records.iter().map(|record| record.record_type).collect::<Vec<_>>(), vec![DohBatchRecordType::A],);
    assert_eq!(doh_ip_answer_candidates(&lookup).len(), 1);
    assert_eq!(query_count.load(Ordering::Relaxed), 5);
    server.join().expect("server joins");
}

#[tokio::test]
async fn doh_pipeline_async_keeps_primary_address_when_optional_rr_requests_fail() {
    let expected_types = [RecordType::A, RecordType::AAAA, RecordType::CNAME, RecordType::HTTPS, RecordType::SVCB];
    let (port, certificate, query_count, server) =
        spawn_batched_doh_fixture("fixture.test.", &expected_types, responses_with_only_ipv4_success("fixture.test"));
    let pipeline =
        pipeline_with_fixture(fixture_endpoint(port, "primary"), fixture_endpoint(port, "secondary"), certificate);

    let lookup = pipeline.resolve("fixture.test").await.expect("IPv4 answer is sufficient for bootstrap");

    assert_eq!(lookup.resolver_role, DohResolverRole::Primary);
    assert_eq!(lookup.records.iter().map(|record| record.record_type).collect::<Vec<_>>(), vec![DohBatchRecordType::A],);
    assert_eq!(doh_ip_answer_candidates(&lookup).len(), 1);
    assert_eq!(query_count.load(Ordering::Relaxed), 5);
    server.join().expect("server joins");
}

#[test]
fn doh_ip_answer_candidates_preserve_family_and_ignore_empty_records() {
    let records = [RecordType::A, RecordType::AAAA, RecordType::HTTPS]
        .iter()
        .map(|record_type| {
            let query = build_query_for_type("fixture.test", *record_type);
            let response_bytes = match record_type {
                RecordType::A | RecordType::AAAA => build_response_with_record(&query, 120),
                _ => build_empty_response(&query),
            };
            crate::DohBatchRecordResponse {
                record_type: match record_type {
                    RecordType::A => DohBatchRecordType::A,
                    RecordType::AAAA => DohBatchRecordType::Aaaa,
                    _ => DohBatchRecordType::Https,
                },
                response_bytes,
                min_ttl_secs: Some(120),
            }
        })
        .collect();
    let lookup = crate::DohBatchLookup {
        domain: "fixture.test".to_string(),
        resolver_role: DohResolverRole::Primary,
        endpoint_label: "primary".to_string(),
        records,
        cache_ttl_secs: Some(120),
    };

    let candidates = doh_ip_answer_candidates(&lookup);

    assert_eq!(candidates.len(), 2);
    assert!(candidates.iter().any(|candidate| {
        candidate.ip == "198.18.0.10"
            && candidate.ip_family == DohIpFamily::Ipv4
            && candidate.resolver_role == DohResolverRole::Primary
    }));
    assert!(candidates.iter().any(|candidate| {
        candidate.ip == "2001:db8::10"
            && candidate.ip_family == DohIpFamily::Ipv6
            && candidate.resolver_role == DohResolverRole::Primary
    }));
}

#[test]
fn doh_pipeline_falls_back_to_secondary_when_primary_fails() {
    let expected_types = [RecordType::A, RecordType::AAAA, RecordType::CNAME, RecordType::HTTPS, RecordType::SVCB];
    let response_bodies = expected_types
        .iter()
        .map(|record_type| {
            let query = build_query_for_type("fixture.test", *record_type);
            match record_type {
                RecordType::A | RecordType::AAAA | RecordType::CNAME => build_response_with_record(&query, 60),
                _ => build_empty_response(&query),
            }
        })
        .collect::<Vec<_>>();
    let (secondary_port, certificate, query_count, secondary_server) =
        spawn_batched_doh_fixture("fixture.test.", &expected_types, response_bodies);
    let pipeline = pipeline_with_fixture(
        fixture_endpoint(9, "primary"),
        fixture_endpoint(secondary_port, "secondary"),
        certificate,
    );

    let lookup = pipeline.resolve_blocking("fixture.test").expect("secondary fallback succeeds");

    assert_eq!(lookup.resolver_role, DohResolverRole::Secondary);
    assert_eq!(query_count.load(Ordering::Relaxed), 5);
    secondary_server.join().expect("server joins");
}

#[test]
fn doh_pipeline_falls_back_when_primary_returns_no_usable_addresses() {
    let address_types = [RecordType::A, RecordType::AAAA];
    let primary_responses = address_types
        .iter()
        .map(|record_type| build_empty_response(&build_query_for_type("fixture.test", *record_type)))
        .collect::<Vec<_>>();
    let (primary_port, primary_certificate, primary_query_count, primary_server) =
        spawn_batched_doh_fixture("fixture.test.", &address_types, primary_responses);

    let expected_types = [RecordType::A, RecordType::AAAA, RecordType::CNAME, RecordType::HTTPS, RecordType::SVCB];
    let secondary_responses = expected_types
        .iter()
        .map(|record_type| {
            let query = build_query_for_type("fixture.test", *record_type);
            match record_type {
                RecordType::A => build_response_with_record(&query, 60),
                _ => build_empty_response(&query),
            }
        })
        .collect::<Vec<_>>();
    let (secondary_port, secondary_certificate, secondary_query_count, secondary_server) =
        spawn_batched_doh_fixture("fixture.test.", &expected_types, secondary_responses);
    let pipeline =
        DohResolverPipeline::builder()
            .primary_endpoint(fixture_endpoint(primary_port, "primary"))
            .secondary_endpoint(fixture_endpoint(secondary_port, "secondary"))
            .tls_roots(vec![primary_certificate, secondary_certificate])
            .connect_hooks(EncryptedDnsConnectHooks::new().with_direct_tcp_connector(|target, timeout| async move {
                TcpStream::connect_timeout(&target, timeout)
            }))
            .build()
            .expect("pipeline builds");

    let lookup = pipeline.resolve_blocking("fixture.test").expect("secondary address fallback succeeds");

    assert_eq!(lookup.resolver_role, DohResolverRole::Secondary);
    assert_eq!(doh_ip_answer_candidates(&lookup).len(), 1);
    assert_eq!(primary_query_count.load(Ordering::Relaxed), 2);
    assert_eq!(secondary_query_count.load(Ordering::Relaxed), 5);
    primary_server.join().expect("primary server joins");
    secondary_server.join().expect("secondary server joins");
}

#[test]
fn doh_pipeline_reuses_fresh_cache_until_ttl_expires() {
    let expected_types = [RecordType::A, RecordType::AAAA, RecordType::CNAME, RecordType::HTTPS, RecordType::SVCB];
    let response_bodies = expected_types
        .iter()
        .map(|record_type| {
            let query = build_query_for_type("fixture.test", *record_type);
            match record_type {
                RecordType::A => build_response_with_record(&query, 2),
                _ => build_empty_response(&query),
            }
        })
        .collect::<Vec<_>>();
    let (port, certificate, query_count, server) =
        spawn_batched_doh_fixture("fixture.test.", &expected_types, response_bodies);
    let pipeline =
        pipeline_with_fixture(fixture_endpoint(port, "primary"), fixture_endpoint(port, "secondary"), certificate);

    let first = pipeline.resolve_blocking("fixture.test").expect("first lookup");
    let second = pipeline.resolve_blocking("fixture.test").expect("cached lookup");

    assert_eq!(first, second);
    assert_eq!(query_count.load(Ordering::Relaxed), 5, "second lookup should hit cache");
    server.join().expect("server joins");
}

#[test]
fn doh_pipeline_refreshes_after_ttl_expiry() {
    let expected_types = [RecordType::A, RecordType::AAAA, RecordType::CNAME, RecordType::HTTPS, RecordType::SVCB];
    let response_bodies = expected_types
        .iter()
        .cycle()
        .take(10)
        .map(|record_type| {
            let query = build_query_for_type("fixture.test", *record_type);
            match record_type {
                RecordType::A => build_response_with_record(&query, 1),
                _ => build_empty_response(&query),
            }
        })
        .collect::<Vec<_>>();
    let expected_cycle = expected_types.iter().copied().cycle().take(10).collect::<Vec<_>>();
    let (port, certificate, query_count, server) =
        spawn_batched_doh_fixture("fixture.test.", &expected_cycle, response_bodies);
    let pipeline =
        pipeline_with_fixture(fixture_endpoint(port, "primary"), fixture_endpoint(port, "secondary"), certificate);

    let _ = pipeline.resolve_blocking("fixture.test").expect("first lookup");
    std::thread::sleep(Duration::from_millis(1_100));
    let _ = pipeline.resolve_blocking("fixture.test").expect("refreshed lookup");

    assert_eq!(query_count.load(Ordering::Relaxed), 10, "expired cache should trigger a full refresh");
    server.join().expect("server joins");
}
