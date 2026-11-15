use std::net::Ipv4Addr;
use std::sync::Arc;
use std::time::Duration;

use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer};

use super::EncryptedDnsResolver;
use crate::types::{
    DoqErrorKind, EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsProtocol, EncryptedDnsTransport,
};

/// The query with a QR bit: a valid response that repeats the question.
fn echo_response(query: &[u8]) -> Vec<u8> {
    let mut response = query.to_vec();
    response[2] |= 0x80;
    response
}

fn loopback_server(alpn: &[u8]) -> (quinn::Endpoint, CertificateDer<'static>) {
    let certificate = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let cert = certificate.cert.der().clone();
    let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(vec![cert.clone()], PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()).into())
        .unwrap();
    tls.alpn_protocols = vec![alpn.to_vec()];
    let server = quinn::Endpoint::server(
        quinn::ServerConfig::with_crypto(Arc::new(quinn::crypto::rustls::QuicServerConfig::try_from(tls).unwrap())),
        (Ipv4Addr::LOCALHOST, 0).into(),
    )
    .unwrap();
    (server, cert)
}

fn loopback_resolver(
    server: &quinn::Endpoint,
    cert: CertificateDer<'static>,
    tls_name: Option<&str>,
) -> EncryptedDnsResolver {
    EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doq,
            resolver_id: None,
            host: "localhost".into(),
            port: server.local_addr().unwrap().port(),
            tls_server_name: tls_name.map(str::to_owned),
            bootstrap_ips: vec![Ipv4Addr::LOCALHOST.into()],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        },
        EncryptedDnsTransport::Direct,
        Duration::from_secs(2),
        vec![cert],
    )
    .unwrap()
}

/// # Cancel safety
///
/// cancel-safe: both futures are joined in the test; dropping them releases their endpoints and streams.
async fn exchange(
    response: impl FnOnce(&[u8]) -> Vec<u8>,
    trailing: &[u8],
) -> (Vec<u8>, Result<Vec<u8>, EncryptedDnsError>) {
    let (server, cert) = loopback_server(b"doq");
    let resolver = loopback_resolver(&server, cert, None);
    let query = crate::build_dns_query("localhost", hickory_proto::rr::RecordType::A).unwrap();
    let mut query = query;
    query[..2].copy_from_slice(&[0x12, 0x34]);
    let serve = async {
        let connection = server.accept().await.unwrap().await.unwrap();
        let (mut send, mut recv) = connection.accept_bi().await.unwrap();
        let mut length = [0; 2];
        recv.read_exact(&mut length).await.unwrap();
        let mut received = vec![0; usize::from(u16::from_be_bytes(length))];
        recv.read_exact(&mut received).await.unwrap();
        let response = response(&received);
        send.write_all(&(response.len() as u16).to_be_bytes()).await.unwrap();
        send.write_all(&response).await.unwrap();
        send.write_all(trailing).await.unwrap();
        send.finish().unwrap();
        let _ = send.stopped().await;
        received
    };
    tokio::time::timeout(Duration::from_secs(5), async { tokio::join!(serve, resolver.exchange(&query)) })
        .await
        .unwrap()
}

#[tokio::test(flavor = "current_thread")]
async fn doq_uses_zero_wire_id_and_restores_caller_id() {
    let (query, result) = exchange(echo_response, &[]).await;
    assert_eq!(&query[..2], &[0, 0]);
    let mut expected = echo_response(&query);
    expected[..2].copy_from_slice(&[0x12, 0x34]);
    assert_eq!(result.unwrap(), expected);
}

#[tokio::test(flavor = "current_thread")]
async fn doq_rejects_short_headers_and_nonzero_response_ids() {
    for response in [vec![0; 11], vec![1; 12]] {
        let (_, result) = exchange(|_| response, &[]).await;
        assert!(result.is_err());
    }
}

#[tokio::test(flavor = "current_thread")]
async fn doq_rejects_trailing_data_after_the_response() {
    let (_, result) = exchange(echo_response, &[1]).await;
    assert!(result.is_err());
}

/// # Cancel safety:
/// cancel-safe: each exchange owns its peers and releases them when dropped.
#[tokio::test(flavor = "current_thread")]
async fn doq_keeps_questionless_dns_errors_with_header_or_extended_rcode() {
    let servfail = b"\0\0\x81\x82\0\0\0\0\0\0\0\0";
    // OPT (TYPE 41) has extended RCODE=1, EDNS version=0 and no options: BADVERS (16).
    let badvers = b"\0\0\x81\x80\0\0\0\0\0\0\0\x01\0\0\x29\x04\xd0\x01\0\0\0\0\0";
    for wire in [servfail.as_slice(), badvers.as_slice()] {
        let (_, result) = exchange(|_| wire.to_vec(), &[]).await;
        let expected = [&[0x12, 0x34], &wire[2..]].concat();
        assert_eq!(result.unwrap(), expected);
    }

    for rcode in [0, 3] {
        let mut wire = *servfail;
        wire[3] = 0x80 | rcode;
        let (_, result) = exchange(|_| wire.to_vec(), &[]).await;
        assert!(matches!(result, Err(EncryptedDnsError::DnsParse(_))));
    }
}

/// # Cancel safety:
/// cancel-safe: the peer task is cancelled and joined after the failed handshake.
#[tokio::test(flavor = "current_thread")]
async fn doq_handshake_distinguishes_certificate_name_and_alpn_failures() {
    for (alpn, tls_name, expected) in
        [(b"doq".as_slice(), Some("wrong.test"), DoqErrorKind::Tls), (b"h3".as_slice(), None, DoqErrorKind::Alpn)]
    {
        let (server, cert) = loopback_server(alpn);
        let resolver = loopback_resolver(&server, cert, tls_name);
        let peer = tokio::spawn(async move {
            if let Some(incoming) = server.accept().await {
                let _ = incoming.await;
            }
        });
        let result = resolver.exchange(&blocking_query()).await;
        peer.abort();
        let _ = peer.await;
        assert!(matches!(result, Err(EncryptedDnsError::Doq { kind, .. }) if kind == expected), "{result:?}");
    }
}

/// # Cancel safety:
/// cancel-safe: the joined operations own their streams and abandon them on deadline.
#[tokio::test(flavor = "current_thread")]
async fn doq_peer_stream_reset_leaves_connection_usable() {
    let (server, cert) = loopback_server(b"doq");
    let resolver = loopback_resolver(&server, cert, None);
    let peer = async {
        let conn = server.accept().await.unwrap().await.unwrap();
        for exchange in 0..2 {
            let (mut send, mut recv) = conn.accept_bi().await.unwrap();
            let request = recv.read_to_end(65_537).await.unwrap();
            let mut response = request;
            response[4] |= 0x80;
            send.write_all(&response).await.unwrap();
            if exchange == 0 {
                tokio::time::sleep(Duration::from_millis(75)).await;
                // Unknown codes stop this stream with the same semantics as DOQ_UNSPECIFIED_ERROR.
                send.reset(99u32.into()).unwrap();
            } else {
                send.finish().unwrap();
                let _ = send.stopped().await;
            }
        }
    };
    let client = async {
        let query = blocking_query();
        let first = resolver.exchange(&query).await;
        assert!(matches!(first, Err(EncryptedDnsError::Doq { kind: DoqErrorKind::Exchange, .. })), "{first:?}");
        let answer = resolver.exchange(&query).await.unwrap();
        assert_eq!(answer, echo_response(&query));
    };
    tokio::time::timeout(Duration::from_secs(5), async { tokio::join!(peer, client) }).await.unwrap();
}

/// A DoQ server on its own runtime that answers every stream with an empty response. With
/// `close_after_answer` it closes each connection right after its first answer.
struct ThreadedDoqServer {
    runtime: tokio::runtime::Runtime,
    endpoint: quinn::Endpoint,
    cert: rustls::pki_types::CertificateDer<'static>,
}

impl ThreadedDoqServer {
    fn start(close_after_answer: bool) -> Self {
        let certificate = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let cert = certificate.cert.der().clone();
        let key = PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der());
        let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(1).enable_all().build().unwrap();
        let endpoint = runtime.block_on(async {
            let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .unwrap()
                .with_no_client_auth()
                .with_single_cert(vec![cert.clone()], key.into())
                .unwrap();
            tls.alpn_protocols = vec![b"doq".to_vec()];
            quinn::Endpoint::server(
                quinn::ServerConfig::with_crypto(Arc::new(
                    quinn::crypto::rustls::QuicServerConfig::try_from(tls).unwrap(),
                )),
                (Ipv4Addr::LOCALHOST, 0).into(),
            )
            .unwrap()
        });
        let accepted = endpoint.clone();
        runtime.spawn(async move {
            while let Some(incoming) = accepted.accept().await {
                tokio::spawn(async move {
                    let Ok(connection) = incoming.await else { return };
                    while let Ok((mut send, mut recv)) = connection.accept_bi().await {
                        let mut length = [0; 2];
                        recv.read_exact(&mut length).await.unwrap();
                        let mut received = vec![0; usize::from(u16::from_be_bytes(length))];
                        recv.read_exact(&mut received).await.unwrap();
                        let response = echo_response(&received);
                        send.write_all(&(response.len() as u16).to_be_bytes()).await.unwrap();
                        send.write_all(&response).await.unwrap();
                        send.finish().unwrap();
                        if close_after_answer {
                            let _ = send.stopped().await;
                            connection.close(0u32.into(), b"bye");
                            return;
                        }
                    }
                });
            }
        });
        Self { runtime, endpoint, cert }
    }

    fn resolver(&self) -> EncryptedDnsResolver {
        EncryptedDnsResolver::with_extra_tls_roots(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Doq,
                resolver_id: None,
                host: "localhost".into(),
                port: self.endpoint.local_addr().unwrap().port(),
                tls_server_name: None,
                bootstrap_ips: vec![Ipv4Addr::LOCALHOST.into()],
                doh_url: None,
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
                odoh: None,
            },
            EncryptedDnsTransport::Direct,
            Duration::from_secs(2),
            vec![self.cert.clone()],
        )
        .expect("a DoQ resolver must build without an ambient Tokio runtime")
    }

    fn stop(self) {
        self.endpoint.close(0u32.into(), b"done");
        self.runtime.shutdown_background();
    }
}

fn blocking_query() -> Vec<u8> {
    let mut query = crate::build_dns_query("localhost", hickory_proto::rr::RecordType::A).unwrap();
    query[..2].copy_from_slice(&[0x12, 0x34]);
    query
}

#[test]
fn doq_blocking_exchange_works_without_an_ambient_runtime() {
    let server = ThreadedDoqServer::start(false);
    // Built and used on a plain thread, as ws-bootstrap does for proxy-side resolution.
    let resolver = server.resolver();
    // Two calls: the second must reuse a live endpoint, not one whose driver died with a per-call runtime.
    for _ in 0..2 {
        let response = resolver.exchange_blocking(&blocking_query()).expect("blocking DoQ exchange must succeed");
        assert_eq!(&response[..3], &[0x12, 0x34, 0x81]);
    }
    server.stop();
}

#[test]
fn doq_recovers_when_the_connection_closed_between_blocking_calls() {
    let server = ThreadedDoqServer::start(true);
    let resolver = server.resolver();
    for _ in 0..2 {
        // Nothing drives the cached connection between the calls, so the server's close is seen only
        // once the next query is under way; that query must retry on a fresh connection.
        let response = resolver.exchange_blocking(&blocking_query()).expect("blocking DoQ exchange must succeed");
        assert_eq!(&response[..3], &[0x12, 0x34, 0x81]);
    }
    server.stop();
}

#[test]
fn doq_rebuilds_its_endpoint_after_the_driving_runtime_is_gone() {
    let server = ThreadedDoqServer::start(false);
    let resolver = server.resolver();
    for _ in 0..2 {
        // Each caller runtime is dropped after one query, taking the endpoint driver with it.
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let response = runtime.block_on(resolver.exchange(&blocking_query())).expect("DoQ exchange must succeed");
        assert_eq!(&response[..3], &[0x12, 0x34, 0x81]);
    }
    server.stop();
}
