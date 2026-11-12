use super::tests::{build_test_init_packet, connected_pair};
use super::*;
use crate::runtime::config::RuntimeConfig;
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
use std::net::{Ipv4Addr, TcpListener};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

struct UnusedCarrier(TcpStream);

impl ripdpi_ws_transport_port::PreparedWsTunnel for UnusedCarrier {
    fn relay(self: Box<Self>, _client: TcpStream, _seed: Vec<u8>) -> io::Result<()> {
        let _carrier = self.0;
        panic!("stopped or wrong-DC carrier must be discarded");
    }
}

#[test]
fn stopped_runtime_cancels_preserved_seed_and_changed_dc_reopen() {
    let mut config = RuntimeConfig::default();
    config.timeouts.connect_timeout_ms = 0;
    let control = Arc::new(EmbeddedProxyControl::new(None));
    let state = RuntimeState::test_with_control(config, control.clone());
    control.request_shutdown();
    let seed = build_test_init_packet(2);
    let target = SocketAddr::from(([149, 154, 165, 10], 443));
    let (_peer, client) = connected_pair();
    let result = run_ws_tunnel_with_seed(client, target, seed.clone(), &state);
    assert!(
        matches!(result, WsTunnelResult::BootstrapFailed { error, .. } if error.kind() == io::ErrorKind::Interrupted)
    );

    let (mut origin, carrier) = connected_pair();
    origin.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
    let (_peer, client) = connected_pair();
    let result = run_ws_tunnel_prepared(
        client,
        target,
        RuntimeTelegramDc::production(1),
        Box::new(UnusedCarrier(carrier)),
        Some(seed),
        &state,
    );
    assert!(
        matches!(result, WsTunnelResult::BootstrapFailed { error, .. } if error.kind() == io::ErrorKind::Interrupted)
    );
    assert_eq!(origin.read(&mut [0; 1]).unwrap(), 0, "discarded carrier must close");

    let (mut origin, carrier) = connected_pair();
    origin.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
    let (_peer, client) = connected_pair();
    let result = run_ws_tunnel_prepared(
        client,
        target,
        RuntimeTelegramDc::production(2),
        Box::new(UnusedCarrier(carrier)),
        Some(build_test_init_packet(2)),
        &state,
    );
    assert!(
        matches!(result, WsTunnelResult::WsOpenOrRelayFailed { error, .. } if error.kind() == io::ErrorKind::Interrupted)
    );
    assert_eq!(origin.read(&mut [0; 1]).unwrap(), 0, "stopped carrier must close without payload");
}

#[test]
fn dns_and_real_tls_bootstrap_share_one_budget() {
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let address = origin.local_addr().unwrap();
    let backend = thread::spawn(move || {
        let (mut socket, _) = origin.accept().unwrap();
        socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let mut header = [0; 5];
        socket.read_exact(&mut header).unwrap();
        assert_eq!(header[0], 0x16, "real TLS ClientHello");
        let result = socket.read_to_end(&mut Vec::new());
        assert!(result.is_ok() || result.is_err_and(|e| e.kind() == io::ErrorKind::ConnectionReset));
    });
    let mut config = RuntimeConfig::default();
    config.timeouts.connect_timeout_ms = 500;
    let state = RuntimeState::test(config);
    let (_peer, client) = connected_pair();
    let started = Instant::now();
    let result = run_ws_tunnel_with_seed_impl(
        client,
        address,
        build_test_init_packet(2),
        &state,
        |_state, _dc, _cancel| {
            // DNS delay is injected; TCP and TLS use the production transport.
            thread::sleep(Duration::from_millis(300));
            Ok(address)
        },
        RuntimeState::relay_ws_tunnel,
    );
    let elapsed = started.elapsed();
    backend.join().unwrap();
    assert!(
        matches!(result, WsTunnelResult::WsOpenOrRelayFailed { error, .. } if error.kind() == io::ErrorKind::TimedOut)
    );
    assert!(elapsed < Duration::from_millis(750), "separate budgets took {elapsed:?}");
}
