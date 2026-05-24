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
    let (request_bytes, response_bytes, retrans_baseline, stream_start) = match rotation.lock() {
        Ok(rotation) => {
            let Some(observed) = rotation.observed_round() else {
                return;
            };
            (
                observed.request_bytes.clone(),
                observed.response_bytes.clone(),
                observed.retrans_baseline,
                observed.stream_start,
            )
        }
        Err(_) => return,
    };
    if let Some(target) = target {
        if let Some(failure) =
            classify_response_failure(state, target, &request_bytes, &response_bytes, host.as_deref())
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
    }
    let retrans_delta = relay_platform::relay_tcp_total_retransmissions(reader)
        .ok()
        .flatten()
        .zip(retrans_baseline)
        .map(|(current, baseline)| current.saturating_sub(baseline))
        .unwrap_or_default();
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
    let (has_observation, retrans_baseline) = match rotation.lock() {
        Ok(rotation) => (
            rotation.observed_round().is_some(),
            rotation.observed_round().and_then(|observed| observed.retrans_baseline),
        ),
        Err(_) => return,
    };
    if !has_observation {
        return;
    }
    let host = remembered_host_value(remembered_host);
    let failure = RuntimeState::classify_first_response_transport_error(&err);
    let retrans_delta = relay_platform::relay_tcp_total_retransmissions(reader)
        .ok()
        .flatten()
        .zip(retrans_baseline)
        .map(|(current, baseline)| current.saturating_sub(baseline))
        .unwrap_or_default();
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
        first_response_settings, DesyncGroup, OffsetBase, OffsetExpr, RotationCandidate, RotationPolicy, RuntimeConfig,
        TcpChainStep, TcpChainStepKind,
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
            b"HTTP/1.1 200 OK\r\n\r\n",
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
