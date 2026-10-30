//! Exact tests against the pinned Caddy + klzgrad/forwardproxy server that
//! `test-lab/acceptance/peers/run.py` starts. They trust only the peer's
//! private test CA through test configuration; production TLS is unchanged.
//! Each negative case first opens a working tunnel, so a missing server cannot
//! pass as an expected rejection.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use rustls::pki_types::CertificateDer;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::time::timeout;

use crate::config::NaiveProxyConfig;
use crate::connect_tunnel::{AsyncTunnel, open_https_connect_tunnel};
use crate::socks5::SocksTarget;

const DEADLINE: Duration = Duration::from_secs(20);

fn required(name: &str) -> String {
    std::env::var(name).expect("run through test-lab/acceptance/peers/run.py")
}

fn loopback(name: &str) -> SocketAddr {
    let address: SocketAddr = required(name).parse().expect("numeric socket address");
    assert!(address.ip().is_loopback() && address.port() != 0, "peer must bind loopback");
    address
}

fn trusted_tls() -> Arc<rustls::ClientConfig> {
    crate::tls::ensure_rustls_provider();
    let mut roots = rustls::RootCertStore::empty();
    let ca = std::fs::read(required("RIPDPI_NAIVE_CA_DER")).expect("peer test CA");
    roots.add(CertificateDer::from(ca)).expect("trust the peer test CA");
    let mut config = rustls::ClientConfig::builder().with_root_certificates(roots).with_no_client_auth();
    config.alpn_protocols = vec![b"h2".to_vec()];
    Arc::new(config)
}

fn peer_config(password: String, tls_config: Arc<rustls::ClientConfig>) -> NaiveProxyConfig {
    let endpoint = loopback("RIPDPI_NAIVE_ENDPOINT");
    NaiveProxyConfig {
        listen: "127.0.0.1:0".to_owned(),
        server: endpoint.ip().to_string(),
        server_port: endpoint.port(),
        server_name: required("RIPDPI_NAIVE_SERVER_NAME"),
        username: Some(required("RIPDPI_NAIVE_USER")),
        password: Some(password),
        local_username: "local-user".to_owned(),
        local_password: "local-pass".to_owned(),
        path: None,
        tls_config,
        protect_socket_path: None,
        sessions: Arc::default(),
    }
}

fn trusted_config() -> NaiveProxyConfig {
    peer_config(required("RIPDPI_NAIVE_PASSWORD"), trusted_tls())
}

/// # Cancel safety:
/// NOT cancel-safe: a partial exchange leaves bytes in flight; a failing test drops the tunnel.
async fn exchange(tunnel: &mut Box<dyn AsyncTunnel>) -> Vec<u8> {
    let payload = required("RIPDPI_NAIVE_PAYLOAD").into_bytes();
    tunnel.write_all(&payload).await.expect("write payload");
    tunnel.flush().await.expect("flush payload");
    let mut echoed = vec![0; payload.len()];
    tunnel.read_exact(&mut echoed).await.expect("read destination echo");
    assert_eq!(echoed, payload, "payload must come back from the destination");
    echoed
}

/// # Cancel safety:
/// cancel-safe: dropping it drops the tunnel it opened.
async fn exchange_with_destination(config: &NaiveProxyConfig, target: SocksTarget) {
    let mut tunnel = open_https_connect_tunnel(config, &target).await.expect("independent NaiveProxy tunnel");
    exchange(&mut tunnel).await;
}

/// # Cancel safety:
/// cancel-safe: dropping the test future drops every tunnel it owns.
#[tokio::test]
#[ignore = "requires the pinned local independent NaiveProxy server"]
async fn independent_peer_exchanges_payload_by_domain_and_ip() {
    timeout(DEADLINE, async {
        let destination = loopback("RIPDPI_NAIVE_TCP");
        let config = trusted_config();
        exchange_with_destination(&config, SocksTarget::Domain("localhost".to_owned(), destination.port())).await;
        exchange_with_destination(&config, SocksTarget::Ip(destination)).await;
    })
    .await
    .expect("independent NaiveProxy payload deadline");
}

/// # Cancel safety:
/// cancel-safe: dropping the test future drops every tunnel it owns.
#[tokio::test]
#[ignore = "requires the pinned local independent NaiveProxy server"]
async fn independent_peer_rejects_wrong_auth() {
    timeout(DEADLINE, async {
        let destination = SocksTarget::Ip(loopback("RIPDPI_NAIVE_TCP"));
        exchange_with_destination(&trusted_config(), destination.clone()).await;

        let wrong = peer_config(format!("{}-wrong", required("RIPDPI_NAIVE_PASSWORD")), trusted_tls());
        let Err(error) = open_https_connect_tunnel(&wrong, &destination).await else {
            panic!("the peer must reject a wrong password");
        };
        assert!(error.to_string().contains("status 407"), "{error}");
    })
    .await
    .expect("independent NaiveProxy auth deadline");
}

/// # Cancel safety:
/// cancel-safe: dropping the test future drops every tunnel it owns.
#[tokio::test]
#[ignore = "requires the pinned local independent NaiveProxy server"]
async fn independent_peer_rejects_untrusted_certificate() {
    timeout(DEADLINE, async {
        let destination = SocksTarget::Ip(loopback("RIPDPI_NAIVE_TCP"));
        exchange_with_destination(&trusted_config(), destination.clone()).await;

        let production_trust = peer_config(required("RIPDPI_NAIVE_PASSWORD"), crate::tls::default_tls_config());
        let Err(error) = open_https_connect_tunnel(&production_trust, &destination).await else {
            panic!("public roots must not trust the private test CA");
        };
        assert!(error.to_string().contains("certificate"), "{error}");
    })
    .await
    .expect("independent NaiveProxy TLS deadline");
}

/// The Go server answers CONNECT 200 before it dials the target, so a refused
/// target is an accepted tunnel that ends without destination bytes.
///
/// # Cancel safety:
/// cancel-safe: dropping the test future drops every tunnel it owns.
#[tokio::test]
#[ignore = "requires the pinned local independent NaiveProxy server"]
async fn independent_peer_ends_tunnel_to_refused_target() {
    timeout(DEADLINE, async {
        let config = trusted_config();
        exchange_with_destination(&config, SocksTarget::Ip(loopback("RIPDPI_NAIVE_TCP"))).await;

        let refused = SocksTarget::Ip(loopback("RIPDPI_NAIVE_CLOSED"));
        if let Ok(mut tunnel) = open_https_connect_tunnel(&config, &refused).await {
            let _ = tunnel.write_all(b"refused-target-probe").await;
            let mut buffer = [0_u8; 64];
            let read = tunnel.read(&mut buffer).await;
            assert!(matches!(read, Ok(0) | Err(_)), "a refused target must send no bytes: {read:?}");
        }
    })
    .await
    .expect("independent NaiveProxy refusal deadline");
}
