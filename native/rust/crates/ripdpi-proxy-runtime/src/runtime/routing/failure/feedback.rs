use std::io;
use std::net::SocketAddr;

use crate::runtime::adaptive::{
    note_adaptive_fake_ttl_failure, note_adaptive_tcp_failure, note_direct_path_tls_post_client_hello_failure,
};
use crate::runtime::retry::note_retry_failure;
use crate::runtime::routing::policy::preferred_targets_for_transport;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::{
    RuntimeClassifiedFailure, RuntimeConnectionRoute, RuntimeFailureClass, RuntimeTransportProtocol,
};

pub(super) fn record_failure_feedback(
    state: &RuntimeState,
    target: SocketAddr,
    route: &RuntimeConnectionRoute,
    host: Option<&str>,
    payload: Option<&[u8]>,
    failure: &RuntimeClassifiedFailure,
) -> io::Result<bool> {
    note_retry_failure(state, target, route.group_index, host, payload, RuntimeTransportProtocol::Tcp)?;
    let penalize = RuntimeState::failure_penalizes_strategy(failure);
    if !penalize {
        return Ok(false);
    }

    if matches!(failure.class, RuntimeFailureClass::TlsAlert | RuntimeFailureClass::TlsHandshakeFailure) {
        let targets = preferred_targets_for_transport(state, target, host, RuntimeTransportProtocol::Tcp);
        let _ = note_direct_path_tls_post_client_hello_failure(state, host, &targets);
    }
    if let Some(payload) = payload {
        note_adaptive_tcp_failure(state, target, route.group_index, host, payload)?;
    }
    note_adaptive_fake_ttl_failure(state, target, route.group_index, host)?;
    state.note_evolver_failure(failure.class);
    Ok(true)
}
