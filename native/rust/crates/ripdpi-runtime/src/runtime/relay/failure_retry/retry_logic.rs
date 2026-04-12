use std::io;
use std::net::SocketAddr;

use ripdpi_failure_classifier::{
    classify_strategy_execution_failure, classify_transport_error, ClassifiedFailure, FailureAction, FailureClass,
    FailureStage,
};

use crate::runtime::adaptive::{note_adaptive_fake_ttl_success, note_adaptive_tcp_success, note_evolver_success};
use crate::runtime::desync::OutboundSendError;
use crate::runtime::retry::note_retry_success;
use crate::runtime::routing::{note_route_success, route_uses_direct_syn_data_tfo, should_track_strategy_target};
use crate::runtime::state::RuntimeState;
use crate::runtime_policy::{ConnectionRoute, TransportProtocol};

pub(crate) fn record_stream_relay_success(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    success_host: Option<&str>,
    success_payload: Option<&[u8]>,
) -> io::Result<()> {
    if !should_track_strategy_target(target) {
        return Ok(());
    }
    if let Some(request) = success_payload {
        note_adaptive_tcp_success(state, target, route.group_index, success_host, request)?;
        note_retry_success(state, target, route.group_index, success_host, Some(request), TransportProtocol::Tcp)?;
    }
    note_adaptive_fake_ttl_success(state, target, route.group_index, success_host)?;
    note_evolver_success(state, 0);
    note_route_success(state, target, route, success_host)?;
    Ok(())
}

pub(crate) fn classify_first_write_failure(error: &OutboundSendError) -> ClassifiedFailure {
    match error {
        OutboundSendError::Transport(source) => classify_transport_error(FailureStage::FirstWrite, source),
        OutboundSendError::StrategyExecution {
            action,
            strategy_family,
            fallback,
            bytes_committed,
            source_errno,
            source,
        } => {
            let mut failure = classify_strategy_execution_failure(
                FailureStage::FirstWrite,
                action,
                source.kind(),
                *source_errno,
                error.to_string(),
            )
            .unwrap_or_else(|| classify_transport_error(FailureStage::FirstWrite, source));
            failure = failure.with_tag("strategyFamily", (*strategy_family).to_string());
            failure = failure.with_tag("bytesCommitted", bytes_committed.to_string());
            if let Some(fallback_family) = fallback {
                failure = failure.with_tag("fallback", (*fallback_family).to_string());
            }
            if *bytes_committed > 0 {
                failure.action = FailureAction::SurfaceOnly;
            }
            failure
        }
    }
}

pub(crate) fn should_retry_syn_data_without_tfo(
    state: &RuntimeState,
    route: &ConnectionRoute,
    payload: Option<&[u8]>,
    failure: &ClassifiedFailure,
    already_retried: bool,
) -> bool {
    !already_retried
        && route_uses_direct_syn_data_tfo(state, route, payload)
        && failure.action != FailureAction::SurfaceOnly
        && matches!(failure.class, FailureClass::ConnectFailure | FailureClass::TcpReset)
}
