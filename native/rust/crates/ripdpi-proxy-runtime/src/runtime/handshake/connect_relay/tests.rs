use super::*;

use crate::runtime::state::RuntimeState;
use ripdpi_proxy_runtime_adapter::config::{RuntimeConfig, WsTunnelMode};
use ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure;
use ripdpi_proxy_runtime_adapter::runtime_api::RuntimeTelemetrySink;
use ripdpi_proxy_runtime_adapter::ws_bootstrap::TelegramDc;
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicUsize, Ordering as StdOrdering};
use std::sync::{Arc as StdArc, Mutex as StdMutex};

fn connected_pair() -> (TcpStream, TcpStream) {
    let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
    let addr = listener.local_addr().expect("listener addr");
    let client = TcpStream::connect(addr).expect("connect client");
    let (server, _) = listener.accept().expect("accept client");
    (client, server)
}

fn runtime_state(config: RuntimeConfig, telemetry: Option<StdArc<dyn RuntimeTelemetrySink>>) -> RuntimeState {
    RuntimeState::test_with_telemetry(config, telemetry)
}

#[derive(Default)]
struct TestTelemetry {
    ws_escalations: StdMutex<Vec<(SocketAddr, u8, bool)>>,
}

impl RuntimeTelemetrySink for TestTelemetry {
    fn on_listener_started(&self, _bind_addr: SocketAddr, _max_clients: usize, _group_count: usize) {}

    fn on_listener_stopped(&self) {}

    fn on_client_accepted(&self) {}

    fn on_client_finished(&self) {}

    fn on_client_error(&self, _error: &io::Error) {}

    fn on_route_selected(&self, _target: SocketAddr, _group_index: usize, _host: Option<&str>, _phase: &'static str) {}

    fn on_failure_classified(&self, _target: SocketAddr, _failure: &ClassifiedFailure, _host: Option<&str>) {}

    fn on_route_advanced(
        &self,
        _target: SocketAddr,
        _from_group: usize,
        _to_group: usize,
        _trigger: u32,
        _host: Option<&str>,
    ) {
    }

    fn on_host_autolearn_state(
        &self,
        _enabled: bool,
        _learned_host_count: usize,
        _penalized_host_count: usize,
        _blocked_host_count: usize,
        _last_block_signal: Option<&str>,
        _last_block_provider: Option<&str>,
    ) {
    }

    fn on_host_autolearn_event(&self, _action: &'static str, _host: Option<&str>, _group_index: Option<usize>) {}

    fn on_ws_tunnel_escalation(&self, target: SocketAddr, dc: u8, success: bool) {
        self.ws_escalations.lock().expect("ws escalations lock").push((target, dc, success));
    }
}

#[test]
fn always_mode_replays_non_mtproto_seed_through_plain_connect() {
    let (_peer, mut client) = connected_pair();
    let mut config = RuntimeConfig::default();
    config.adaptive.ws_tunnel_mode = WsTunnelMode::Always;
    let state = runtime_state(config, None);
    let target = SocketAddr::from(([149, 154, 167, 91], 443));
    let seed_request = vec![1_u8, 2, 3, 4, 5];
    let sniff_seed = seed_request.clone();
    let expected_seed = seed_request.clone();
    let write_count = StdArc::new(AtomicUsize::new(0));

    let result = connect_and_relay_with(
        &mut client,
        target,
        &state,
        Some("telegram-dc2".to_string()),
        SuccessReply::Socks5,
        {
            let write_count = write_count.clone();
            move |_client, _reply, _upstream| {
                write_count.fetch_add(1, StdOrdering::Relaxed);
                Ok(())
            }
        },
        move |_client, _state| WsTunnelResult::NotMtproto { seed_request: sniff_seed.clone() },
        |_client, _seed_request, _state| unreachable!("fallback WS should not be used"),
        |_client, _state, _target, _host_hint, _handshake| unreachable!("desync path should not run"),
        |_client, _target, _state, _dc_host, _reply| unreachable!("plain immediate relay should not run"),
        |_client, _target, _state, _dc_host, _route, _payload| unreachable!("plain delayed relay should not run"),
        move |_client, replay_target, _state, dc_host, replay_seed| {
            assert_eq!(replay_target, target);
            assert_eq!(dc_host.as_deref(), Some("telegram-dc2"));
            assert_eq!(replay_seed, expected_seed);
            Ok(())
        },
    );

    assert!(result.is_ok());
    assert_eq!(write_count.load(StdOrdering::Relaxed), 1);
}

#[test]
fn always_mode_replays_seed_through_plain_connect_after_bootstrap_failure() {
    let (_peer, mut client) = connected_pair();
    let mut config = RuntimeConfig::default();
    config.adaptive.ws_tunnel_mode = WsTunnelMode::Always;
    let state = runtime_state(config, None);
    let target = SocketAddr::from(([149, 154, 167, 91], 443));
    let seed_request = vec![7_u8; 64];
    let bootstrap_seed = seed_request.clone();
    let expected_seed = seed_request.clone();

    let result = connect_and_relay_with(
        &mut client,
        target,
        &state,
        Some("telegram-dc2".to_string()),
        SuccessReply::Socks5,
        |_client, _reply, _upstream| Ok(()),
        move |_client, _state| WsTunnelResult::BootstrapFailed {
            dc: TelegramDc::production(2),
            seed_request: bootstrap_seed.clone(),
            error: io::Error::new(io::ErrorKind::TimedOut, "bootstrap timed out"),
        },
        |_client, _seed_request, _state| unreachable!("fallback WS should not be used"),
        |_client, _state, _target, _host_hint, _handshake| unreachable!("desync path should not run"),
        |_client, _target, _state, _dc_host, _reply| unreachable!("plain immediate relay should not run"),
        |_client, _target, _state, _dc_host, _route, _payload| unreachable!("plain delayed relay should not run"),
        move |_client, replay_target, _state, dc_host, replay_seed| {
            assert_eq!(replay_target, target);
            assert_eq!(dc_host.as_deref(), Some("telegram-dc2"));
            assert_eq!(replay_seed, expected_seed);
            Ok(())
        },
    );

    assert!(result.is_ok());
}

#[test]
fn fallback_mode_reuses_preserved_seed_for_validated_mtproto() {
    let (_peer, mut client) = connected_pair();
    let mut config = RuntimeConfig::default();
    config.adaptive.ws_tunnel_mode = WsTunnelMode::Fallback;
    let telemetry = StdArc::new(TestTelemetry::default());
    let state = runtime_state(config, Some(telemetry.clone()));
    let target = SocketAddr::from(([149, 154, 167, 91], 443));
    let seed_request = vec![9_u8; 64];
    let fallback_seed = seed_request.clone();
    let preserved_seed = seed_request.clone();
    let write_count = StdArc::new(AtomicUsize::new(0));

    let result = connect_and_relay_with(
        &mut client,
        target,
        &state,
        Some("telegram-dc2".to_string()),
        SuccessReply::Socks5,
        {
            let write_count = write_count.clone();
            move |_client, _reply, _upstream| {
                write_count.fetch_add(1, StdOrdering::Relaxed);
                Ok(())
            }
        },
        |_client, _state| unreachable!("fresh WS sniff should not be used"),
        move |_client, replay_seed, _state| {
            assert_eq!(replay_seed, fallback_seed);
            WsTunnelResult::ValidatedMtproto { dc: TelegramDc::production(2) }
        },
        |_client, _state, _target, _host_hint, _handshake| Ok(DelayConnect::Immediate),
        move |_client, _target, _state, _dc_host, _reply| {
            Err(ConnectRelayError::with_seed_request(
                io::Error::other("desync exhausted"),
                true,
                Some(preserved_seed.clone()),
            ))
        },
        |_client, _target, _state, _dc_host, _route, _payload| unreachable!("delayed relay should not run"),
        |_client, _target, _state, _dc_host, _seed_request| unreachable!("after-WS plain fallback should not run"),
    );

    assert!(result.is_ok());
    assert_eq!(write_count.load(StdOrdering::Relaxed), 0);
    assert_eq!(telemetry.ws_escalations.lock().expect("ws escalations lock").as_slice(), &[(target, 2, true)],);
}

#[test]
fn fallback_mode_returns_original_error_for_non_mtproto_preserved_seed() {
    let (_peer, mut client) = connected_pair();
    let mut config = RuntimeConfig::default();
    config.adaptive.ws_tunnel_mode = WsTunnelMode::Fallback;
    let telemetry = StdArc::new(TestTelemetry::default());
    let state = runtime_state(config, Some(telemetry.clone()));
    let target = SocketAddr::from(([149, 154, 167, 91], 443));
    let seed_request = vec![7_u8; 64];
    let fallback_seed = seed_request.clone();
    let preserved_seed = seed_request.clone();
    let write_count = StdArc::new(AtomicUsize::new(0));

    let err = connect_and_relay_with(
        &mut client,
        target,
        &state,
        Some("telegram-dc2".to_string()),
        SuccessReply::Socks5,
        {
            let write_count = write_count.clone();
            move |_client, _reply, _upstream| {
                write_count.fetch_add(1, StdOrdering::Relaxed);
                Ok(())
            }
        },
        |_client, _state| unreachable!("fresh WS sniff should not be used"),
        move |_client, replay_seed, _state| {
            assert_eq!(replay_seed, fallback_seed);
            WsTunnelResult::NotMtproto { seed_request: replay_seed }
        },
        |_client, _state, _target, _host_hint, _handshake| Ok(DelayConnect::Immediate),
        move |_client, _target, _state, _dc_host, _reply| {
            Err(ConnectRelayError::with_seed_request(
                io::Error::new(io::ErrorKind::TimedOut, "desync timeout"),
                true,
                Some(preserved_seed.clone()),
            ))
        },
        |_client, _target, _state, _dc_host, _route, _payload| unreachable!("delayed relay should not run"),
        |_client, _target, _state, _dc_host, _seed_request| unreachable!("after-WS plain fallback should not run"),
    )
    .expect_err("non-MTProto fallback should keep original error");

    assert_eq!(err.kind(), io::ErrorKind::TimedOut);
    assert_eq!(write_count.load(StdOrdering::Relaxed), 0);
    assert_eq!(telemetry.ws_escalations.lock().expect("ws escalations lock").as_slice(), &[(target, 2, false)],);
}

#[test]
fn fallback_mode_returns_original_error_for_bootstrap_failure() {
    let (_peer, mut client) = connected_pair();
    let mut config = RuntimeConfig::default();
    config.adaptive.ws_tunnel_mode = WsTunnelMode::Fallback;
    let telemetry = StdArc::new(TestTelemetry::default());
    let state = runtime_state(config, Some(telemetry.clone()));
    let target = SocketAddr::from(([149, 154, 167, 91], 443));
    let seed_request = vec![3_u8; 64];
    let fallback_seed = seed_request.clone();
    let preserved_seed = seed_request.clone();

    let err = connect_and_relay_with(
        &mut client,
        target,
        &state,
        Some("telegram-dc2".to_string()),
        SuccessReply::Socks5,
        |_client, _reply, _upstream| Ok(()),
        |_client, _state| unreachable!("fresh WS sniff should not be used"),
        move |_client, replay_seed, _state| {
            assert_eq!(replay_seed, fallback_seed);
            WsTunnelResult::BootstrapFailed {
                dc: TelegramDc::production(2),
                seed_request: replay_seed,
                error: io::Error::new(io::ErrorKind::TimedOut, "bootstrap timed out"),
            }
        },
        |_client, _state, _target, _host_hint, _handshake| Ok(DelayConnect::Immediate),
        move |_client, _target, _state, _dc_host, _reply| {
            Err(ConnectRelayError::with_seed_request(
                io::Error::new(io::ErrorKind::TimedOut, "desync timeout"),
                true,
                Some(preserved_seed.clone()),
            ))
        },
        |_client, _target, _state, _dc_host, _route, _payload| unreachable!("delayed relay should not run"),
        |_client, _target, _state, _dc_host, _seed_request| unreachable!("after-WS plain fallback should not run"),
    )
    .expect_err("bootstrap failure should keep original error");

    assert_eq!(err.kind(), io::ErrorKind::TimedOut);
    assert_eq!(telemetry.ws_escalations.lock().expect("ws escalations lock").as_slice(), &[(target, 2, false)],);
}
