use crate::sync::{Arc, Mutex};
use ripdpi_proxy_runtime_adapter::model::tcp_rotation::{CircularTcpRotationController, RotationFailureReason};
use std::io;
use std::net::TcpStream;

use super::super::super::routing::{classify_response_failure, note_block_signal_for_failure};
use super::super::super::state::RuntimeState;
use super::super::platform as relay_platform;

pub(super) fn remembered_host_value(remembered_host: &Arc<Mutex<Option<String>>>) -> Option<String> {
    remembered_host.lock().ok().and_then(|host| host.clone())
}

pub(super) fn observe_rotation_inbound_chunk(
    state: &RuntimeState,
    target: Option<std::net::SocketAddr>,
    remembered_host: &Arc<Mutex<Option<String>>>,
    rotation: Option<&Arc<Mutex<CircularTcpRotationController>>>,
    reader: &TcpStream,
    chunk: &[u8],
) {
    let Some(rotation) = rotation else {
        return;
    };
    let host = remembered_host_value(remembered_host);
    let ready = match rotation.lock() {
        Ok(mut rotation) => rotation.observe_response_chunk(chunk),
        Err(_) => return,
    };
    if !ready {
        return;
    }
    let (
        request_bytes,
        response_bytes,
        classification_bytes,
        retrans_baseline,
        stream_start,
        inconclusive,
        invalid_alert,
        neutral_http,
    ) = match rotation.lock() {
        Ok(rotation) => {
            let Some(observed) = rotation.observed_round() else {
                return;
            };
            (
                observed.request_bytes.clone(),
                observed.response_bytes.clone(),
                if !observed.response_is_inconclusive() || observed.tls_tracker.inspecting_http() {
                    observed
                        .tls_tracker
                        .classification_bytes(&observed.response_bytes, !observed.response_is_inconclusive())
                        .map(<[u8]>::to_vec)
                } else {
                    None
                },
                observed.retrans_baseline,
                observed.stream_start,
                observed.response_is_inconclusive(),
                observed.tls_tracker.invalid_alert(&observed.response_bytes),
                observed.tls_tracker.neutral_http(),
            )
        }
        Err(_) => return,
    };
    if let Some(target) = target
        && let Some(bytes) = classification_bytes.as_deref()
        && let Some(failure) = classify_response_failure(state, target, &request_bytes, bytes, host.as_deref())
    {
        note_block_signal_for_failure(state, host.as_deref(), &failure, None);
        if let Ok(mut rotation) = rotation.lock() {
            rotation.observe_round_failure(
                host.as_deref(),
                Some(target),
                RotationFailureReason::ResponseClassified(failure.class),
                0,
            );
        }
        return;
    }
    if inconclusive
        || neutral_http
        || ripdpi_proxy_runtime_adapter::failure::is_tls_closure_response(&response_bytes)
        || invalid_alert
    {
        if let Ok(mut rotation) = rotation.lock() {
            rotation.observe_round_inconclusive();
        }
        return;
    }
    let retrans_delta = relay_platform::relay_tcp_total_retransmissions(reader)
        .ok()
        .flatten()
        .zip(retrans_baseline)
        .map_or_default(|(current, baseline)| current.saturating_sub(baseline));
    if let Ok(mut rotation) = rotation.lock() {
        if rotation.retransmission_failure_matches_observation(stream_start, retrans_delta) {
            rotation.observe_round_failure(
                host.as_deref(),
                target,
                RotationFailureReason::Retransmissions,
                retrans_delta,
            );
        } else {
            rotation.observe_round_success();
        }
    }
}

pub(super) fn observe_rotation_transport_failure(
    state: &RuntimeState,
    target: Option<std::net::SocketAddr>,
    remembered_host: &Arc<Mutex<Option<String>>>,
    rotation: Option<&Arc<Mutex<CircularTcpRotationController>>>,
    reader: &TcpStream,
    err: io::Error,
) {
    let Some(rotation) = rotation else {
        return;
    };
    if err.kind() == io::ErrorKind::UnexpectedEof {
        let completed_http = match rotation.lock() {
            Ok(mut controller) => controller.finish_http_response_on_eof(),
            Err(_) => false,
        };
        if completed_http {
            observe_rotation_inbound_chunk(state, target, remembered_host, Some(rotation), reader, &[]);
            return;
        }
    }
    let (has_observation, retrans_baseline) = match rotation.lock() {
        Ok(rotation) => (
            rotation.observed_round().is_some(),
            rotation.observed_round().and_then(|observed| observed.retrans_baseline),
        ),
        Err(_) => return,
    };
    // Content inspection limits do not hide EOF/RST/I/O errors of a still-owned round.
    // A content-neutral round already removed its observation and cannot be revived here.
    if !has_observation {
        return;
    }
    let host = remembered_host_value(remembered_host);
    let failure = RuntimeState::classify_first_response_transport_error(&err);
    let retrans_delta = relay_platform::relay_tcp_total_retransmissions(reader)
        .ok()
        .flatten()
        .zip(retrans_baseline)
        .map_or_default(|(current, baseline)| current.saturating_sub(baseline));
    note_block_signal_for_failure(state, host.as_deref(), &failure, None);
    if let Ok(mut rotation) = rotation.lock() {
        rotation.observe_round_failure(
            host.as_deref(),
            target,
            RotationFailureReason::Transport(failure.class),
            retrans_delta,
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_proxy_runtime_adapter::model::config::{
        DesyncGroup, OffsetBase, OffsetExpr, RotationCandidate, RotationPolicy, RuntimeConfig, TcpChainStep,
        TcpChainStepKind, first_response_settings,
    };
    use std::io::ErrorKind;
    use std::net::{Ipv4Addr, SocketAddr, TcpListener};

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }

    fn rotation_controller() -> CircularTcpRotationController {
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(2))];
        CircularTcpRotationController::new(
            group,
            RotationPolicy {
                candidates: vec![RotationCandidate {
                    tcp_chain: vec![TcpChainStep::new(
                        TcpChainStepKind::Fake,
                        OffsetExpr::marker(OffsetBase::EndHost, 1),
                    )],
                }],
                ..RotationPolicy::default()
            },
        )
        .expect("rotation controller")
    }

    fn start_observed_round(rotation: &Arc<Mutex<CircularTcpRotationController>>, config: &RuntimeConfig) {
        rotation.lock().expect("rotation lock").start_round(
            first_response_settings(config),
            1,
            0,
            b"GET / HTTP/1.1\r\n\r\n",
            Some(0),
            Some("example.com"),
            None,
        );
        assert!(rotation.lock().expect("rotation lock").observed_round().is_some());
    }

    #[test]
    fn dpi38_rotation_closure_preserves_prior_failure_budget() {
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(1))];
        let mut controller = CircularTcpRotationController::new(
            group,
            RotationPolicy {
                candidates: vec![RotationCandidate {
                    tcp_chain: vec![TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(1))],
                }],
                fails: 2,
                rst: u32::MAX,
                retrans: u32::MAX,
                ..RotationPolicy::default()
            },
        )
        .expect("rotation policy");
        controller.observe_round_failure(
            None,
            None,
            RotationFailureReason::ResponseClassified(crate::runtime::failure::RuntimeFailureClass::TlsAlert),
            0,
        );
        let config = RuntimeConfig::default();
        controller.start_round(
            first_response_settings(&config),
            2,
            0,
            crate::runtime::payload::RUNTIME_DEFAULT_FAKE_TLS,
            None,
            None,
            None,
        );
        let rotation = Arc::new(Mutex::new(controller));
        let state = RuntimeState::test(config.clone());
        let (mut reader, mut origin) = connected_pair();
        use std::io::{Read, Write};
        let closure = [0x15, 3, 3, 0, 2, 1, 90];
        origin.write_all(&closure).expect("actual closure record");
        let mut received = [0; 7];
        reader.read_exact(&mut received).expect("loopback alert bytes");
        assert_eq!(received, closure);
        observe_rotation_inbound_chunk(
            &state,
            Some(reader.peer_addr().expect("target")),
            &Arc::new(Mutex::new(None)),
            Some(&rotation),
            &reader,
            &received,
        );
        let mut rotation = rotation.lock().expect("rotation lock");
        assert!(rotation.observed_round().is_none(), "closure ends an inconclusive observation");
        rotation.start_round(first_response_settings(&config), 3, 0, b"GET / HTTP/1.1\r\n\r\n", None, None, None);
        rotation.observe_round_failure(
            None,
            None,
            RotationFailureReason::ResponseClassified(crate::runtime::failure::RuntimeFailureClass::TlsAlert),
            0,
        );
        rotation.start_round(first_response_settings(&config), 4, 0, b"GET / HTTP/1.1\r\n\r\n", None, None, None);
        assert_eq!(
            rotation.current_send_group().actions.tcp_chain[0].kind(),
            TcpChainStepKind::Fake,
            "two failures still meet the threshold across a neutral closure"
        );
    }

    #[test]
    fn dpi38_owned_eof_after_window_stays_typed_and_neutral_round_cannot_revive() {
        use crate::runtime::relay::session::{FirstOutboundSession, FirstResponseOutcome};
        use std::io::{Read, Write};
        use std::net::Shutdown;
        use std::sync::atomic::AtomicBool;
        use std::time::{Duration, Instant};

        for neutral_before_eof in [false, true] {
            let mut config = RuntimeConfig::default();
            config.timeouts.timeout_ms = 50;
            let state = RuntimeState::test(config.clone());
            let mut group = DesyncGroup::new(0);
            group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(1))];
            let mut controller = CircularTcpRotationController::new(
                group,
                RotationPolicy {
                    candidates: vec![RotationCandidate {
                        tcp_chain: vec![TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(1))],
                    }],
                    fails: 1,
                    rst: u32::MAX,
                    retrans: u32::MAX,
                    ..RotationPolicy::default()
                },
            )
            .expect("one-failure policy");
            let (mut upstream, mut origin) = connected_pair();
            let (mut writer, mut app) = connected_pair();
            app.set_read_timeout(Some(Duration::from_secs(2))).expect("bounded client fixture");
            controller.start_round(
                first_response_settings(&config),
                1,
                0,
                crate::runtime::payload::RUNTIME_DEFAULT_FAKE_TLS,
                None,
                None,
                None,
            );
            let rotation = Arc::new(Mutex::new(controller));
            let peer_rotation = rotation.clone();
            let closure = [0x15, 3, 3, 0, 2, 1, 90];
            let origin_worker = std::thread::spawn(move || {
                if neutral_before_eof {
                    origin.write_all(&closure).expect("actual closure bytes");
                    let limit = Instant::now() + Duration::from_secs(1);
                    while peer_rotation.lock().expect("rotation lock").observed_round().is_some()
                        && Instant::now() < limit
                    {
                        std::thread::sleep(Duration::from_millis(1));
                    }
                    assert!(
                        peer_rotation.lock().expect("rotation lock").observed_round().is_none(),
                        "neutral content ends ownership before EOF"
                    );
                }
                std::thread::sleep(Duration::from_millis(75));
                if !neutral_before_eof {
                    let guard = peer_rotation.lock().expect("rotation lock");
                    let observation = guard.observed_round().expect("expired content still owns its transport round");
                    assert!(observation.response_is_inconclusive(), "the content window expired before the real EOF");
                }
                origin.shutdown(Shutdown::Write).expect("real upstream EOF");
            });
            let session = FirstOutboundSession::new().into_relay_session().into_shared();
            session
                .finish_first_response(FirstResponseOutcome::Accepted { success_recorded: true })
                .expect("previous accepted exchange");
            super::super::copy_halves::copy_inbound_half(
                &mut upstream,
                &mut writer,
                &state,
                session,
                Arc::new(Mutex::new(None)),
                Some(rotation.clone()),
                Arc::new(AtomicBool::new(false)),
                super::super::freeze::FreezeDetector::new(0, 0, 0),
                Arc::new(AtomicBool::new(false)),
            )
            .expect("real inbound EOF path");
            origin_worker.join().expect("owned origin worker");
            let mut forwarded = Vec::new();
            app.read_to_end(&mut forwarded).expect("exact forwarded bytes and FIN");
            assert_eq!(forwarded, if neutral_before_eof { closure.as_slice() } else { &[] });
            let mut rotation = rotation.lock().expect("rotation lock");
            rotation.start_round(first_response_settings(&config), 2, 0, b"GET / HTTP/1.1\r\n\r\n", None, None, None);
            assert_eq!(
                rotation.current_send_group().actions.tcp_chain[0].kind(),
                if neutral_before_eof { TcpChainStepKind::Split } else { TcpChainStepKind::Fake },
                "only a still-owned transport EOF can advance the policy"
            );
        }
    }

    #[test]
    fn dpi38_rotating_http_observer_uses_complete_bounded_body_and_exact_forwarding() {
        use crate::runtime::relay::session::{FirstOutboundSession, FirstResponseOutcome};
        use std::io::{Read, Write};
        use std::net::Shutdown;
        use std::sync::atomic::AtomicBool;
        use std::time::{Duration, Instant};

        for mode in 0..3 {
            let body = b"This website has been blocked";
            let head = match mode {
                0 => format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n", body.len()),
                1 => "HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n".to_string(),
                _ => "HTTP/1.0 200 OK\r\n\r\n".to_string(),
            };
            let mut response = [head.as_bytes(), body].concat();
            let mut config = RuntimeConfig::default();
            config.timeouts.timeout_ms = 1_000;
            if mode == 1 {
                config.timeouts.timeout_bytes_limit = (head.len() + body.len() + 3) as i32;
                response.resize(head.len() + 10_000, b'x');
            }
            let state = RuntimeState::test(config.clone());
            let mut group = DesyncGroup::new(0);
            group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(1))];
            let mut controller = CircularTcpRotationController::new(
                group,
                RotationPolicy {
                    candidates: vec![RotationCandidate {
                        tcp_chain: vec![TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(1))],
                    }],
                    fails: 1,
                    rst: u32::MAX,
                    retrans: u32::MAX,
                    ..RotationPolicy::default()
                },
            )
            .expect("one-failure rotation");
            controller.start_round(first_response_settings(&config), 1, 0, b"GET / HTTP/1.1\r\n\r\n", None, None, None);
            let rotation = Arc::new(Mutex::new(controller));
            let (mut upstream, mut origin) = connected_pair();
            let (mut writer, mut app) = connected_pair();
            app.set_read_timeout(Some(Duration::from_secs(2))).expect("bounded client");
            let source = response.clone();
            let peer_rotation = rotation.clone();
            let origin_worker = std::thread::spawn(move || {
                let split = match mode {
                    0 => head.len() + 8,
                    1 => head.len() + body.len() + 3,
                    _ => source.len(),
                };
                origin.write_all(&source[..split]).expect("fragmented real HTTP prefix");
                let limit = Instant::now() + Duration::from_secs(1);
                loop {
                    let guard = peer_rotation.lock().expect("rotation lock");
                    let ready = if mode == 1 {
                        guard.observed_round().is_none()
                    } else {
                        guard.observed_round().is_some_and(|observed| observed.response_bytes.len() == split)
                    };
                    drop(guard);
                    if ready {
                        break;
                    }
                    assert!(Instant::now() < limit, "observer must consume bounded prefix");
                    std::thread::sleep(Duration::from_millis(1));
                }
                origin.write_all(&source[split..]).expect("remaining real HTTP bytes");
                origin.shutdown(Shutdown::Write).expect("graceful source EOF");
            });
            let session = FirstOutboundSession::new().into_relay_session().into_shared();
            session
                .finish_first_response(FirstResponseOutcome::Accepted { success_recorded: true })
                .expect("earlier exchange");
            super::super::copy_halves::copy_inbound_half(
                &mut upstream,
                &mut writer,
                &state,
                session,
                Arc::new(Mutex::new(None)),
                Some(rotation.clone()),
                Arc::new(AtomicBool::new(false)),
                super::super::freeze::FreezeDetector::new(0, 0, 0),
                Arc::new(AtomicBool::new(false)),
            )
            .expect("real rotating inbound loop");
            origin_worker.join().expect("owned fixture");
            let mut bytes = Vec::new();
            app.read_to_end(&mut bytes).expect("exact source bytes and FIN");
            assert_eq!(bytes, response);
            let mut controller = rotation.lock().expect("rotation lock");
            assert!(controller.observed_round().is_none());
            controller.start_round(first_response_settings(&config), 2, 0, b"GET / HTTP/1.1\r\n\r\n", None, None, None);
            assert_eq!(
                controller.current_send_group().actions.tcp_chain[0].kind(),
                if mode == 1 { TcpChainStepKind::Split } else { TcpChainStepKind::Fake },
                "only complete bounded body evidence advances rotation"
            );
        }
    }

    #[test]
    fn remembered_host_value_returns_present_host_and_handles_poisoning() {
        let remembered_host = Arc::new(Mutex::new(Some("example.com".to_string())));
        assert_eq!(remembered_host_value(&remembered_host).as_deref(), Some("example.com"));

        let poisoned = remembered_host.clone();
        let _ = std::panic::catch_unwind(move || {
            let _guard = poisoned.lock().expect("lock host");
            panic!("poison host lock");
        });

        assert!(remembered_host_value(&remembered_host).is_none());
    }

    #[test]
    fn inbound_observer_marks_ready_round_successful() {
        let config = RuntimeConfig::default();
        let state = RuntimeState::test(config.clone());
        let remembered_host = Arc::new(Mutex::new(Some("example.com".to_string())));
        let rotation = Arc::new(Mutex::new(rotation_controller()));
        let (reader, _writer) = connected_pair();
        start_observed_round(&rotation, &config);

        observe_rotation_inbound_chunk(
            &state,
            None,
            &remembered_host,
            Some(&rotation),
            &reader,
            b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
        );

        assert!(rotation.lock().expect("rotation lock").observed_round().is_none());
    }

    #[test]
    fn transport_observer_records_failure_after_observed_round() {
        let config = RuntimeConfig::default();
        let state = RuntimeState::test(config.clone());
        let remembered_host = Arc::new(Mutex::new(Some("example.com".to_string())));
        let rotation = Arc::new(Mutex::new(rotation_controller()));
        let (reader, _writer) = connected_pair();
        let target = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), 443);
        start_observed_round(&rotation, &config);

        observe_rotation_transport_failure(
            &state,
            Some(target),
            &remembered_host,
            Some(&rotation),
            &reader,
            io::Error::new(ErrorKind::ConnectionReset, "reset"),
        );

        assert!(rotation.lock().expect("rotation lock").observed_round().is_none());
    }
}
