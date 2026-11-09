use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::Duration;

use local_network_fixture::Hysteria2Loopback;

use super::sample_config;
use crate::backend::RelayBackend;
use crate::backend::builder::build_backend;
use crate::config::RelayBackendConfig;
use crate::socks::RelayTargetAddr;

async fn backend_for(fixture: &Hysteria2Loopback) -> RelayBackend {
    let mut config = sample_config("hysteria2");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = "localhost".to_string();
    let RelayBackendConfig::Hysteria2(hysteria) = &mut config.backend else { unreachable!() };
    hysteria.insecure = true;
    build_backend(&config).await.expect("build Hysteria2 backend")
}

fn target(port: u16) -> RelayTargetAddr {
    RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port))
}

fn evictions(backend: &RelayBackend) -> u64 {
    backend.pool_health().expect("pooled backend").evictions
}

/// # Cancel safety:
/// NOT cancel-safe: a timeout fails the test and drops the backend and fixture.
#[tokio::test]
async fn refused_target_keeps_the_shared_carrier_and_is_sent_once() {
    let fixture = Hysteria2Loopback::start_refusing_target_suffix(":81").await.expect("start fixture");
    let backend = backend_for(&fixture).await;

    tokio::time::timeout(Duration::from_secs(10), backend.connect_tcp(&target(80)))
        .await
        .expect("open stream")
        .expect("allowed target connects");
    let before = evictions(&backend);

    let Err(error) =
        tokio::time::timeout(Duration::from_secs(10), backend.connect_tcp(&target(81))).await.expect("refused connect")
    else {
        panic!("refused target must fail");
    };
    assert_eq!(error.kind(), std::io::ErrorKind::ConnectionRefused);
    assert_eq!(evictions(&backend), before, "a refused target must not evict the carrier");
    assert_eq!(fixture.refused_requests(), 1, "a refusal must not be retried on a fresh carrier");
}

/// # Cancel safety:
/// NOT cancel-safe: a timeout fails the test and drops the backend and fixture.
#[tokio::test]
async fn server_without_udp_keeps_the_shared_carrier() {
    let fixture = Hysteria2Loopback::start().await.expect("start fixture");
    let backend = backend_for(&fixture).await;

    tokio::time::timeout(Duration::from_secs(10), backend.connect_tcp(&target(80)))
        .await
        .expect("open stream")
        .expect("connect");
    let before = evictions(&backend);

    for _ in 0..2 {
        let Err(error) =
            tokio::time::timeout(Duration::from_secs(10), backend.open_udp_session()).await.expect("udp open")
        else {
            panic!("UDP-denying server must fail UDP opens");
        };
        assert_eq!(error.kind(), std::io::ErrorKind::Unsupported);
    }
    assert_eq!(evictions(&backend), before, "missing server UDP must not evict the carrier");
}
