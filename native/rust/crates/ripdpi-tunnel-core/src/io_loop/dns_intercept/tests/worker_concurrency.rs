use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport};
use rustls::pki_types::PrivatePkcs8KeyDer;
use tokio_util::sync::CancellationToken;

use super::super::{DnsRequest, spawn_dns_worker};
use super::support::build_query;

/// A DoQ server that answers every query at once, except queries for `slow.test`, which it never answers.
fn spawn_doq_server() -> (u16, rustls::pki_types::CertificateDer<'static>) {
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
    let port = server.local_addr().unwrap().port();
    tokio::spawn(async move {
        while let Some(incoming) = server.accept().await {
            tokio::spawn(async move {
                let Ok(connection) = incoming.await else { return };
                while let Ok((mut send, mut recv)) = connection.accept_bi().await {
                    tokio::spawn(async move {
                        let mut length = [0; 2];
                        recv.read_exact(&mut length).await.unwrap();
                        let mut query = vec![0; usize::from(u16::from_be_bytes(length))];
                        recv.read_exact(&mut query).await.unwrap();
                        if query.windows(4).any(|window| window == b"slow") {
                            std::future::pending::<()>().await;
                        }
                        query[2] |= 0x80;
                        send.write_all(&length).await.unwrap();
                        send.write_all(&query).await.unwrap();
                        send.finish().unwrap();
                        let _ = send.stopped().await;
                    });
                }
            });
        }
    });
    (port, cert)
}

fn request(port: u16, name: &str) -> DnsRequest {
    DnsRequest {
        src: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), port),
        query: build_query(name),
        host: Some(name.to_string()),
        direct: None,
        tcp_reply: None,
    }
}

#[tokio::test]
async fn slow_upstream_lookup_does_not_block_later_queries() {
    let (port, cert) = spawn_doq_server();
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
        Duration::from_secs(5),
        vec![cert],
    )
    .unwrap();
    let cancel = CancellationToken::new();
    let (req_tx, mut resp_rx) = spawn_dns_worker(resolver, cancel.clone(), Duration::from_secs(5));

    req_tx.send(request(1001, "slow.test")).await.unwrap();
    req_tx.send(request(1002, "fast.test")).await.unwrap();

    let first = tokio::time::timeout(Duration::from_secs(2), resp_rx.recv())
        .await
        .expect("the fast lookup must not wait for the slow one")
        .expect("worker stays alive");
    assert_eq!(first.src.port(), 1002);
    assert!(first.upstream.is_ok(), "fast lookup must succeed: {:?}", first.upstream);
    cancel.cancel();
}
