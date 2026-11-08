use super::*;

/// # Cancel safety:
/// NOT cancel-safe: a partial frame is discarded; the test must drop the owned stream after cancellation.
async fn read_query(recv: &mut quinn::RecvStream) -> Vec<u8> {
    let mut length = [0; 2];
    recv.read_exact(&mut length).await.unwrap();
    let mut query = vec![0; usize::from(u16::from_be_bytes(length))];
    recv.read_exact(&mut query).await.unwrap();
    assert!(recv.read_to_end(0).await.unwrap().is_empty());
    query
}

/// # Cancel safety:
/// NOT cancel-safe: a partial answer cannot be retried; the test drops the carrier on cancellation.
async fn answer(send: &mut quinn::SendStream, query: &[u8]) {
    let response = build_response(query, Ipv4Addr::new(198, 18, 0, 79));
    send.write_all(&(response.len() as u16).to_be_bytes()).await.unwrap();
    send.write_all(&response).await.unwrap();
    send.finish().unwrap();
}

/// # Cancel safety:
/// cancel-safe: the test owns every task and endpoint; runtime teardown releases incomplete exchanges.
async fn check_request_isolation(abort_first: bool) {
    let certificate = generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let cert = certificate.cert.der().clone();
    let key = PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der());
    let mut tls = ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(vec![cert.clone()], key.into())
        .unwrap();
    tls.alpn_protocols = vec![b"doq".to_vec()];
    let server = quinn::Endpoint::server(
        quinn::ServerConfig::with_crypto(Arc::new(quinn::crypto::rustls::QuicServerConfig::try_from(tls).unwrap())),
        (Ipv4Addr::LOCALHOST, 0).into(),
    )
    .unwrap();
    let port = server.local_addr().unwrap().port();
    let (first_ready_tx, first_ready_rx) = tokio::sync::oneshot::channel();
    let (second_ready_tx, second_ready_rx) = tokio::sync::oneshot::channel();
    let serve = tokio::spawn(async move {
        let connection = server.accept().await.unwrap().await.unwrap();
        let (first_send, mut first_recv) = connection.accept_bi().await.unwrap();
        let _ = read_query(&mut first_recv).await;
        first_ready_tx.send(()).unwrap();
        let (mut second_send, mut second_recv) = connection.accept_bi().await.unwrap();
        let second = read_query(&mut second_recv).await;
        second_ready_tx.send(()).unwrap();
        assert_eq!(first_send.stopped().await.unwrap(), Some(3u32.into()), "DOQ_REQUEST_CANCELLED");
        answer(&mut second_send, &second).await;
        let (mut third_send, mut third_recv) = connection.accept_bi().await.unwrap();
        let third = read_query(&mut third_recv).await;
        answer(&mut third_send, &third).await;
        let _ = third_send.stopped().await;
        assert!(connection.close_reason().is_none(), "request cancellation must preserve the carrier");
    });
    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doq,
            resolver_id: None,
            host: "localhost".into(),
            port,
            tls_server_name: None,
            bootstrap_ips: vec![Ipv4Addr::LOCALHOST.into()],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        },
        EncryptedDnsTransport::Direct,
        Duration::from_millis(500),
        vec![cert],
    )
    .unwrap();
    let first_resolver = resolver.clone();
    let first = tokio::spawn(async move { first_resolver.exchange(&build_query("first.test")).await });
    first_ready_rx.await.unwrap();
    tokio::time::sleep(Duration::from_millis(250)).await;
    let second_resolver = resolver.clone();
    let second = tokio::spawn(async move { second_resolver.exchange(&build_query("second.test")).await });
    second_ready_rx.await.unwrap();
    if abort_first {
        first.abort();
        assert!(first.await.unwrap_err().is_cancelled());
    } else {
        let error = first.await.unwrap().unwrap_err();
        assert!(error.to_string().contains("DoQ query timeout"), "unexpected error: {error}");
    }
    assert_eq!(extract_ip_answers(&second.await.unwrap().unwrap()).unwrap(), ["198.18.0.79"]);
    let third = resolver.exchange(&build_query("third.test")).await.unwrap();
    assert_eq!(extract_ip_answers(&third).unwrap(), ["198.18.0.79"]);
    serve.await.unwrap();
}

/// # Cancel safety:
/// cancel-safe: the test runtime tears down every owned peer and task on timeout.
#[tokio::test(flavor = "current_thread")]
async fn doq_timeout_cancels_only_its_stream_and_reuses_the_connection() {
    tokio::time::timeout(Duration::from_secs(5), check_request_isolation(false)).await.unwrap();
}

/// # Cancel safety:
/// cancel-safe: the first query task is aborted and joined; the remaining owned peers are driven to completion.
#[tokio::test(flavor = "current_thread")]
async fn doq_dropped_query_sends_request_cancelled_and_preserves_other_streams() {
    tokio::time::timeout(Duration::from_secs(5), check_request_isolation(true)).await.unwrap();
}
