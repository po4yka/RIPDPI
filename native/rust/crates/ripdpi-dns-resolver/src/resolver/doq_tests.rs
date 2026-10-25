use std::net::Ipv4Addr;
use std::sync::Arc;
use std::time::Duration;

use rustls::pki_types::PrivatePkcs8KeyDer;

use super::EncryptedDnsResolver;
use crate::types::{EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsProtocol, EncryptedDnsTransport};

/// # Cancel safety
///
/// cancel-safe: both futures are joined in the test; dropping them releases their endpoints and streams.
async fn exchange(response: Vec<u8>, trailing: &[u8]) -> (Vec<u8>, Result<Vec<u8>, EncryptedDnsError>) {
    let certificate = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let cert = certificate.cert.der().clone();
    let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(vec![cert.clone()], PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()).into())
        .unwrap();
    tls.alpn_protocols = vec![b"doq".to_vec()];
    let server = quinn::Endpoint::server(
        quinn::ServerConfig::with_crypto(Arc::new(quinn::crypto::rustls::QuicServerConfig::try_from(tls).unwrap())),
        (Ipv4Addr::LOCALHOST, 0).into(),
    )
    .unwrap();
    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doq,
            resolver_id: None,
            host: "localhost".into(),
            port: server.local_addr().unwrap().port(),
            tls_server_name: None,
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
    .unwrap();
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
    let mut response = vec![0; 12];
    response[2] = 0x80;
    let (query, result) = exchange(response.clone(), &[]).await;
    assert_eq!(&query[..2], &[0, 0]);
    response[..2].copy_from_slice(&[0x12, 0x34]);
    assert_eq!(result.unwrap(), response);
}

#[tokio::test(flavor = "current_thread")]
async fn doq_rejects_short_headers_and_nonzero_response_ids() {
    for response in [vec![0; 11], vec![1; 12]] {
        let (_, result) = exchange(response, &[]).await;
        assert!(result.is_err());
    }
}

#[tokio::test(flavor = "current_thread")]
async fn doq_rejects_trailing_data_after_the_response() {
    let (_, result) = exchange(vec![0; 12], &[1]).await;
    assert!(result.is_err());
}

#[test]
fn doq_blocking_exchange_works_without_an_ambient_runtime() {
    let certificate = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let cert = certificate.cert.der().clone();
    let key = PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der());
    let server_runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(1).enable_all().build().unwrap();
    let server = server_runtime.block_on(async {
        let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(vec![cert.clone()], key.into())
            .unwrap();
        tls.alpn_protocols = vec![b"doq".to_vec()];
        quinn::Endpoint::server(
            quinn::ServerConfig::with_crypto(Arc::new(quinn::crypto::rustls::QuicServerConfig::try_from(tls).unwrap())),
            (Ipv4Addr::LOCALHOST, 0).into(),
        )
        .unwrap()
    });
    let port = server.local_addr().unwrap().port();
    let accepted = server.clone();
    server_runtime.spawn(async move {
        while let Some(incoming) = accepted.accept().await {
            let Ok(connection) = incoming.await else { continue };
            while let Ok((mut send, mut recv)) = connection.accept_bi().await {
                let mut length = [0; 2];
                recv.read_exact(&mut length).await.unwrap();
                let mut received = vec![0; usize::from(u16::from_be_bytes(length))];
                recv.read_exact(&mut received).await.unwrap();
                let mut response = vec![0; 12];
                response[2] = 0x80;
                send.write_all(&12u16.to_be_bytes()).await.unwrap();
                send.write_all(&response).await.unwrap();
                send.finish().unwrap();
            }
        }
    });

    // Built and used on a plain thread, as ws-bootstrap does for proxy-side resolution.
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
        Duration::from_secs(2),
        vec![cert],
    )
    .expect("a DoQ resolver must build without an ambient Tokio runtime");
    let mut query = crate::build_dns_query("localhost", hickory_proto::rr::RecordType::A).unwrap();
    query[..2].copy_from_slice(&[0x12, 0x34]);
    // Two calls: the second must reuse a live endpoint, not one whose driver died with a per-call runtime.
    for _ in 0..2 {
        let response = resolver.exchange_blocking(&query).expect("blocking DoQ exchange must succeed");
        assert_eq!(&response[..3], &[0x12, 0x34, 0x80]);
    }
    server.close(0u32.into(), b"done");
    server_runtime.shutdown_background();
}
