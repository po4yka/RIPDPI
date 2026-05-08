use crate::sync::{Arc, Mutex};
use ripdpi_proxy_runtime_adapter::model::tcp_rotation::{CircularTcpRotationController, RotationFailureReason};
use std::io;
use std::net::TcpStream;

use super::super::super::routing::{classify_response_failure, note_block_signal_for_failure};
use super::super::super::state::RuntimeState;

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
    let retrans_delta = ripdpi_proxy_runtime_adapter::platform::relay::tcp_total_retransmissions(reader)
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
    let retrans_delta = ripdpi_proxy_runtime_adapter::platform::relay::tcp_total_retransmissions(reader)
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
