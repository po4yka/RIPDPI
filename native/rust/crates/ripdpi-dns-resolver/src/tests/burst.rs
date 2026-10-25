//! A burst of concurrent lookups on one resolver must not open one upstream connection per lookup.

use std::net::{IpAddr, Ipv4Addr, TcpStream};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer, pem::PemObject};
use tokio::task::JoinSet;

use super::{FixtureStack, build_query, dynamic_fixture_config};
use crate::{
    EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport,
};

const BURST: usize = 16;

/// # Cancel safety
///
/// cancel-safe: every lookup is a task in the set; dropping the set aborts them.
async fn run_burst(resolver: &EncryptedDnsResolver, query: &[u8]) {
    let mut lookups = JoinSet::new();
    for _ in 0..BURST {
        let resolver = resolver.clone();
        let query = query.to_vec();
        lookups.spawn(async move { resolver.exchange(&query).await });
    }
    while let Some(result) = lookups.join_next().await {
        result.expect("lookup task").expect("lookup succeeds");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn doq_burst_shares_one_connection() {
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
    let accepts = Arc::new(AtomicUsize::new(0));
    let server_accepts = accepts.clone();
    let server_task = tokio::spawn(async move {
        while let Some(incoming) = server.accept().await {
            server_accepts.fetch_add(1, Ordering::SeqCst);
            tokio::spawn(async move {
                let Ok(connection) = incoming.await else { return };
                while let Ok((mut send, mut recv)) = connection.accept_bi().await {
                    tokio::spawn(async move {
                        let mut length = [0; 2];
                        recv.read_exact(&mut length).await.unwrap();
                        let mut query = vec![0; usize::from(u16::from_be_bytes(length))];
                        recv.read_exact(&mut query).await.unwrap();
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

    run_burst(&resolver, &build_query("burst.test")).await;

    assert_eq!(accepts.load(Ordering::SeqCst), 1, "a burst must share one QUIC connection");
    server_task.abort();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn dot_burst_reuses_a_bounded_set_of_connections() {
    let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture stack");
    let manifest = stack.manifest();
    let certificate = CertificateDer::from_pem_slice(manifest.tls_certificate_pem.as_bytes()).expect("parse pem");
    let connects = Arc::new(AtomicUsize::new(0));
    let connect_calls = connects.clone();
    let resolver = EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: Some("fixture-dot".to_string()),
            host: manifest.fixture_domain.clone(),
            port: manifest.dns_dot_port,
            tls_server_name: Some(manifest.fixture_domain.clone()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        },
        EncryptedDnsTransport::Direct,
        Duration::from_secs(5),
        vec![certificate],
        EncryptedDnsConnectHooks::new().with_direct_tcp_connector(move |target, timeout| {
            connect_calls.fetch_add(1, Ordering::SeqCst);
            async move { TcpStream::connect_timeout(&target, timeout) }
        }),
    )
    .expect("build DoT resolver");

    run_burst(&resolver, &build_query(&manifest.fixture_domain)).await;

    let connects = connects.load(Ordering::SeqCst);
    assert!((1..=4).contains(&connects), "a burst of {BURST} must reuse at most 4 DoT connections, opened {connects}");
}
