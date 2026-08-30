use super::*;

use crate::runtime::config::{RuntimeConfig, WsTunnelMode};
use crate::runtime::failure::RuntimeClassifiedFailure;
use crate::runtime::state::RuntimeState;
use crate::runtime::ws::RuntimeTelegramDc;
use ripdpi_proxy_runtime_adapter::model::proxy_config::{ProxyDirectPathCapability, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::model::runtime_api::RuntimeTelemetrySink;
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

fn owned_stack_context() -> ProxyRuntimeContext {
    ProxyRuntimeContext {
        direct_path_capabilities: vec![ProxyDirectPathCapability {
            authority: "example.org:443".to_string(),
            quic_usable: None,
            udp_usable: None,
            fallback_required: None,
            repeated_handshake_failure_class: None,
            transport_policy_version: 1,
            ip_set_digest: String::new(),
            dns_classification: None,
            quic_mode: "ALLOW".to_string(),
            preferred_stack: "H3".to_string(),
            dns_mode: "SYSTEM".to_string(),
            tcp_family: "NONE".to_string(),
            outcome: "OWNED_STACK_ONLY".to_string(),
            transport_class: None,
            reason_code: Some("OWNED_STACK_REQUIRED".to_string()),
            cooldown_until: None,
            updated_at: 0,
        }],
        ..ProxyRuntimeContext::default()
    }
}

#[test]
fn pre_sent_success_reply_is_propagated_to_immediate_relay() {
    let (_peer, mut client) = connected_pair();
    let state = runtime_state(RuntimeConfig::default(), None);
    let target = SocketAddr::from(([192, 0, 2, 22], 22));
    let immediate_calls = StdArc::new(AtomicUsize::new(0));

    let result = connect_and_relay_with(
        &mut client,
        target,
        &state,
        None,
        SuccessReply::Socks5,
        None,
        |_client, _reply, _upstream| Ok(()),
        |_client, _state| unreachable!("WS should be disabled"),
        |_client, _seed, _state| unreachable!("WS should be disabled"),
        |_client, _state, _target, _host_hint, _handshake| Ok(DelayConnect::Immediate { success_reply_sent: true }),
        {
            let immediate_calls = immediate_calls.clone();
            move |_client, _target, _state, _host, _reply, success_reply_sent, _attempt_token| {
                assert!(success_reply_sent, "immediate relay must not emit a second success reply");
                immediate_calls.fetch_add(1, StdOrdering::Relaxed);
                Ok(())
            }
        },
        |_client, _target, _state, _host, _route, _payload, _attempt_token| {
            unreachable!("delayed relay should not run")
        },
        |_client, _target, _state, _host, _seed| unreachable!("WS fallback should not run"),
    );

    assert!(result.is_ok());
    assert_eq!(immediate_calls.load(StdOrdering::Relaxed), 1);
}

#[test]
fn owned_stack_only_capability_rejects_transparent_connection_before_relay() {
    let (_peer, mut client) = connected_pair();
    let target = SocketAddr::from(([198, 51, 100, 42], 443));
    let state = RuntimeState::test_with_context(RuntimeConfig::default(), Some(owned_stack_context()));
    let delay_calls = StdArc::new(AtomicUsize::new(0));
    let ws_calls = StdArc::new(AtomicUsize::new(0));
    let relay_calls = StdArc::new(AtomicUsize::new(0));

    let err = connect_and_relay_with(
        &mut client,
        target,
        &state,
        Some("example.org".to_string()),
        SuccessReply::None,
        None,
        |_client, _reply, _upstream| unreachable!("transparent mode must not send a success reply"),
        {
            let ws_calls = ws_calls.clone();
            move |_client, _state| {
                ws_calls.fetch_add(1, StdOrdering::Relaxed);
                unreachable!("owned-stack-only connection must not start WS")
            }
        },
        |_client, _seed, _state| unreachable!("owned-stack-only connection must not start WS"),
        {
            let delay_calls = delay_calls.clone();
            move |_client, _state, _target, _host_hint, _handshake| {
                delay_calls.fetch_add(1, StdOrdering::Relaxed);
                Ok(DelayConnect::Closed)
            }
        },
        {
            let relay_calls = relay_calls.clone();
            move |_client, _target, _state, _host, _reply, _success_reply_sent, _attempt_token| {
                relay_calls.fetch_add(1, StdOrdering::Relaxed);
                unreachable!("owned-stack-only connection must not start a relay")
            }
        },
        |_client, _target, _state, _host, _route, _payload, _attempt_token| {
            unreachable!("owned-stack-only connection must not start a delayed relay")
        },
        |_client, _target, _state, _host, _seed| unreachable!("owned-stack-only connection must not fall back"),
    )
    .expect_err("owned-stack-only transparent connection must be rejected");

    assert_eq!(err.kind(), io::ErrorKind::PermissionDenied);
    assert_eq!(err.policy_rejection(), Some(ConnectPolicyRejection::OwnedStackRequired));
    assert_eq!(err.into_io_error().to_string(), "OWNED_STACK_REQUIRED");
    assert_eq!(delay_calls.load(StdOrdering::Relaxed), 0);
    assert_eq!(ws_calls.load(StdOrdering::Relaxed), 0);
    assert_eq!(relay_calls.load(StdOrdering::Relaxed), 0);
}

#[derive(Default)]
struct TestTelemetry {
    ws_escalations: StdMutex<Vec<(SocketAddr, u8, bool)>>,
    ws_fake_sni_active: StdMutex<Vec<(SocketAddr, u8)>>,
    direct_path_signals: StdMutex<Vec<(String, String, String)>>,
}

impl RuntimeTelemetrySink for TestTelemetry {
    fn on_listener_started(&self, _bind_addr: SocketAddr, _max_clients: usize, _group_count: usize) {}

    fn on_listener_stopped(&self) {}

    fn on_client_accepted(&self) {}

    fn on_client_finished(&self) {}

    fn on_client_error(&self, _error: &io::Error) {}

    fn on_route_selected(&self, _target: SocketAddr, _group_index: usize, _host: Option<&str>, _phase: &'static str) {}

    fn on_failure_classified(&self, _target: SocketAddr, _failure: &RuntimeClassifiedFailure, _host: Option<&str>) {}

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

    fn on_ws_tunnel_fake_sni_active(&self, target: SocketAddr, dc: u8) {
        self.ws_fake_sni_active.lock().expect("ws fake sni lock").push((target, dc));
    }

    fn on_direct_path_learning_signal(
        &self,
        authority: &str,
        ip_set_digest: &str,
        event: &'static str,
        _strategy_family: Option<&str>,
    ) {
        self.direct_path_signals.lock().expect("direct path signals lock").push((
            authority.to_string(),
            ip_set_digest.to_string(),
            event.to_string(),
        ));
    }
}

#[test]
fn owned_stack_rejection_emits_structured_runtime_signal() {
    let telemetry = StdArc::new(TestTelemetry::default());
    let target = SocketAddr::from(([198, 51, 100, 42], 443));
    let state = RuntimeState::test_with_telemetry_and_context(
        RuntimeConfig::default(),
        Some(telemetry.clone()),
        Some(owned_stack_context()),
    );

    assert!(state.owned_stack_required_for_transparent_target(target, Some("example.org"), 0));
    let signals = telemetry.direct_path_signals.lock().expect("direct path signals lock");
    assert_eq!(signals.len(), 1);
    assert_eq!(signals[0].0, "example.org:443");
    assert!(!signals[0].1.is_empty());
    assert_eq!(signals[0].2, "OWNED_STACK_REQUIRED");
}

#[test]
fn hostless_transparent_target_does_not_apply_owned_stack_domain_policy() {
    let target = SocketAddr::from(([198, 51, 100, 42], 443));
    let mut context = owned_stack_context();
    context.direct_path_capabilities[0].authority = target.to_string();
    let state = RuntimeState::test_with_context(RuntimeConfig::default(), Some(context));

    assert!(!state.owned_stack_required_for_transparent_target(target, None, 0));
}

#[test]
fn mismatched_ip_set_does_not_apply_owned_stack_policy() {
    let target = SocketAddr::from(([198, 51, 100, 42], 443));
    let mut context = owned_stack_context();
    context.direct_path_capabilities[0].ip_set_digest = "different-ip-set".to_string();
    let state = RuntimeState::test_with_context(RuntimeConfig::default(), Some(context));

    assert!(!state.owned_stack_required_for_transparent_target(target, Some("example.org"), 0));
}

#[test]
fn hostname_target_does_not_apply_ip_scoped_owned_stack_policy() {
    let target = SocketAddr::from(([198, 51, 100, 42], 443));
    let mut context = owned_stack_context();
    context.direct_path_capabilities[0].authority = target.to_string();
    let state = RuntimeState::test_with_context(RuntimeConfig::default(), Some(context));

    assert!(!state.owned_stack_required_for_transparent_target(target, Some("unrelated.example"), 0));
}

#[test]
fn hostname_owned_stack_policy_outranks_earlier_ip_capability() {
    let target = SocketAddr::from(([198, 51, 100, 42], 443));
    let mut context = owned_stack_context();
    let mut ip_capability = context.direct_path_capabilities[0].clone();
    ip_capability.authority = target.to_string();
    ip_capability.outcome = "TRANSPARENT_OK".to_string();
    ip_capability.reason_code = None;
    context.direct_path_capabilities.insert(0, ip_capability);
    let state = RuntimeState::test_with_context(RuntimeConfig::default(), Some(context));

    assert!(state.owned_stack_required_for_transparent_target(target, Some("example.org"), 0));
}

#[test]
fn ip_literal_hostname_does_not_apply_ip_scoped_owned_stack_policy() {
    let target = SocketAddr::from(([198, 51, 100, 42], 443));
    let mut context = owned_stack_context();
    context.direct_path_capabilities[0].authority = target.ip().to_string();
    let state = RuntimeState::test_with_context(RuntimeConfig::default(), Some(context));

    assert!(!state.owned_stack_required_for_transparent_target(target, Some("198.51.100.42"), 0));
}

fn fallback_validated_mtproto_with_fake_sni(fake_sni: Option<&str>, allow_insecure_sni: bool) -> StdArc<TestTelemetry> {
    let (_peer, mut client) = connected_pair();
    let mut config = RuntimeConfig::default();
    config.adaptive.ws_tunnel_mode = WsTunnelMode::Fallback;
    config.adaptive.ws_tunnel_fake_sni = fake_sni.map(ToOwned::to_owned);
    config.adaptive.ws_tunnel_allow_insecure_sni = allow_insecure_sni;
    let telemetry = StdArc::new(TestTelemetry::default());
    let state = runtime_state(config, Some(telemetry.clone()));
    let target = SocketAddr::from(([149, 154, 167, 91], 443));
    let preserved_seed = vec![9_u8; 64];

    let result = connect_and_relay_with(
        &mut client,
        target,
        &state,
        Some("telegram-dc2".to_string()),
        SuccessReply::Socks5,
        None,
        |_client, _reply, _upstream| Ok(()),
        |_client, _state| unreachable!("fresh WS sniff should not be used"),
        |_client, _replay_seed, _state| WsTunnelResult::ValidatedMtproto { dc: RuntimeTelegramDc::production(2) },
        |_client, _state, _target, _host_hint, _handshake| Ok(DelayConnect::Immediate { success_reply_sent: false }),
        move |_client, _target, _state, _dc_host, _reply, _success_reply_sent, _attempt_token| {
            Err(ConnectRelayError::with_seed_request(
                io::Error::other("desync exhausted"),
                true,
                Some(preserved_seed.clone()),
            ))
        },
        |_client, _target, _state, _dc_host, _route, _payload, _attempt_token| {
            unreachable!("delayed relay should not run")
        },
        |_client, _target, _state, _dc_host, _seed_request| unreachable!("after-WS plain fallback should not run"),
    );
    assert!(result.is_ok());
    telemetry
}

#[test]
fn fake_sni_counter_fires_only_when_cover_and_opt_in_are_both_set() {
    // fake-SNI cover present and operator opt-in set -> counter fires once.
    let telemetry = fallback_validated_mtproto_with_fake_sni(Some("yandex.ru"), true);
    let target = SocketAddr::from(([149, 154, 167, 91], 443));
    assert_eq!(telemetry.ws_fake_sni_active.lock().expect("fake sni lock").as_slice(), &[(target, 2)]);
}

#[test]
fn fake_sni_counter_silent_without_opt_in_or_cover() {
    // Cover set but no opt-in -> no fake-SNI handshake established, counter stays empty.
    let telemetry = fallback_validated_mtproto_with_fake_sni(Some("yandex.ru"), false);
    assert!(telemetry.ws_fake_sni_active.lock().expect("fake sni lock").is_empty());

    // Opt-in set but no cover domain -> nothing insecure happened, counter stays empty.
    let telemetry = fallback_validated_mtproto_with_fake_sni(None, true);
    assert!(telemetry.ws_fake_sni_active.lock().expect("fake sni lock").is_empty());
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
        None,
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
        |_client, _target, _state, _dc_host, _reply, _success_reply_sent, _attempt_token| {
            unreachable!("plain immediate relay should not run")
        },
        |_client, _target, _state, _dc_host, _route, _payload, _attempt_token| {
            unreachable!("plain delayed relay should not run")
        },
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
fn always_mode_fails_closed_for_validated_mtproto_after_bootstrap_failure() {
    let (_peer, mut client) = connected_pair();
    let mut config = RuntimeConfig::default();
    config.adaptive.ws_tunnel_mode = WsTunnelMode::Always;
    let state = runtime_state(config, None);
    let target = SocketAddr::from(([149, 154, 167, 91], 443));
    let seed_request = vec![7_u8; 64];
    let bootstrap_seed = seed_request.clone();
    let write_count = StdArc::new(AtomicUsize::new(0));

    let err = connect_and_relay_with(
        &mut client,
        target,
        &state,
        Some("telegram-dc2".to_string()),
        SuccessReply::Socks5,
        None,
        {
            let write_count = write_count.clone();
            move |_client, _reply, _upstream| {
                write_count.fetch_add(1, StdOrdering::Relaxed);
                Ok(())
            }
        },
        move |_client, _state| WsTunnelResult::BootstrapFailed {
            dc: RuntimeTelegramDc::production(2),
            seed_request: bootstrap_seed.clone(),
            error: io::Error::new(io::ErrorKind::TimedOut, "bootstrap timed out"),
        },
        |_client, _seed_request, _state| unreachable!("fallback WS should not be used"),
        |_client, _state, _target, _host_hint, _handshake| unreachable!("desync path should not run"),
        |_client, _target, _state, _dc_host, _reply, _success_reply_sent, _attempt_token| {
            unreachable!("plain immediate relay should not run")
        },
        |_client, _target, _state, _dc_host, _route, _payload, _attempt_token| {
            unreachable!("plain delayed relay should not run")
        },
        |_client, _target, _state, _dc_host, _seed_request| unreachable!("after-WS plain fallback should not run"),
    )
    .expect_err("validated MTProto in Always mode must not fall back after bootstrap failure");

    assert_eq!(err.kind(), io::ErrorKind::TimedOut);
    assert!(err.success_reply_sent());
    assert_eq!(err.seed_request(), Some(seed_request.as_slice()));
    assert_eq!(write_count.load(StdOrdering::Relaxed), 1);
}

#[test]
fn always_mode_fails_closed_for_validated_mtproto_after_ws_relay_failure() {
    let (_peer, mut client) = connected_pair();
    let mut config = RuntimeConfig::default();
    config.adaptive.ws_tunnel_mode = WsTunnelMode::Always;
    let state = runtime_state(config, None);
    let target = SocketAddr::from(([149, 154, 167, 91], 443));
    let seed_request = vec![8_u8; 64];
    let relay_seed = seed_request.clone();
    let write_count = StdArc::new(AtomicUsize::new(0));

    let err = connect_and_relay_with(
        &mut client,
        target,
        &state,
        Some("telegram-dc2".to_string()),
        SuccessReply::Socks5,
        None,
        {
            let write_count = write_count.clone();
            move |_client, _reply, _upstream| {
                write_count.fetch_add(1, StdOrdering::Relaxed);
                Ok(())
            }
        },
        move |_client, _state| WsTunnelResult::WsOpenOrRelayFailed {
            dc: RuntimeTelegramDc::production(2),
            seed_request: relay_seed.clone(),
            error: io::Error::new(io::ErrorKind::ConnectionReset, "relay reset"),
        },
        |_client, _seed_request, _state| unreachable!("fallback WS should not be used"),
        |_client, _state, _target, _host_hint, _handshake| unreachable!("desync path should not run"),
        |_client, _target, _state, _dc_host, _reply, _success_reply_sent, _attempt_token| {
            unreachable!("plain immediate relay should not run")
        },
        |_client, _target, _state, _dc_host, _route, _payload, _attempt_token| {
            unreachable!("plain delayed relay should not run")
        },
        |_client, _target, _state, _dc_host, _seed_request| unreachable!("after-WS plain fallback should not run"),
    )
    .expect_err("validated MTProto in Always mode must not fall back after relay failure");

    assert_eq!(err.kind(), io::ErrorKind::ConnectionReset);
    assert!(err.success_reply_sent());
    assert_eq!(err.seed_request(), Some(seed_request.as_slice()));
    assert_eq!(write_count.load(StdOrdering::Relaxed), 1);
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
        None,
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
            WsTunnelResult::ValidatedMtproto { dc: RuntimeTelegramDc::production(2) }
        },
        |_client, _state, _target, _host_hint, _handshake| Ok(DelayConnect::Immediate { success_reply_sent: false }),
        move |_client, _target, _state, _dc_host, _reply, _success_reply_sent, _attempt_token| {
            Err(ConnectRelayError::with_seed_request(
                io::Error::other("desync exhausted"),
                true,
                Some(preserved_seed.clone()),
            ))
        },
        |_client, _target, _state, _dc_host, _route, _payload, _attempt_token| {
            unreachable!("delayed relay should not run")
        },
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
        None,
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
        |_client, _state, _target, _host_hint, _handshake| Ok(DelayConnect::Immediate { success_reply_sent: false }),
        move |_client, _target, _state, _dc_host, _reply, _success_reply_sent, _attempt_token| {
            Err(ConnectRelayError::with_seed_request(
                io::Error::new(io::ErrorKind::TimedOut, "desync timeout"),
                true,
                Some(preserved_seed.clone()),
            ))
        },
        |_client, _target, _state, _dc_host, _route, _payload, _attempt_token| {
            unreachable!("delayed relay should not run")
        },
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
        None,
        |_client, _reply, _upstream| Ok(()),
        |_client, _state| unreachable!("fresh WS sniff should not be used"),
        move |_client, replay_seed, _state| {
            assert_eq!(replay_seed, fallback_seed);
            WsTunnelResult::BootstrapFailed {
                dc: RuntimeTelegramDc::production(2),
                seed_request: replay_seed,
                error: io::Error::new(io::ErrorKind::TimedOut, "bootstrap timed out"),
            }
        },
        |_client, _state, _target, _host_hint, _handshake| Ok(DelayConnect::Immediate { success_reply_sent: false }),
        move |_client, _target, _state, _dc_host, _reply, _success_reply_sent, _attempt_token| {
            Err(ConnectRelayError::with_seed_request(
                io::Error::new(io::ErrorKind::TimedOut, "desync timeout"),
                true,
                Some(preserved_seed.clone()),
            ))
        },
        |_client, _target, _state, _dc_host, _route, _payload, _attempt_token| {
            unreachable!("delayed relay should not run")
        },
        |_client, _target, _state, _dc_host, _seed_request| unreachable!("after-WS plain fallback should not run"),
    )
    .expect_err("bootstrap failure should keep original error");

    assert_eq!(err.kind(), io::ErrorKind::TimedOut);
    assert_eq!(telemetry.ws_escalations.lock().expect("ws escalations lock").as_slice(), &[(target, 2, false)],);
}
