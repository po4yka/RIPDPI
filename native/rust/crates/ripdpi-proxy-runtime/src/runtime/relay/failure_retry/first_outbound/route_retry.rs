use std::io::{self, Write};
use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::failure::{ClassifiedFailure, FailureAction, FailureClass};
use ripdpi_proxy_runtime_adapter::model::decision::ConnectionRoute;
use ripdpi_proxy_runtime_adapter::model::session::SessionState;

use crate::runtime::desync::OutboundSendError;
use crate::runtime::relay::failure_retry::first_outbound::observations::observe_retry_response_payload;
use crate::runtime::relay::failure_retry::retry_logic::{
    classify_first_write_failure, should_retry_syn_data_without_tfo,
};
use crate::runtime::routing::{
    advance_route_for_failure, emit_failure_classified, note_block_signal_for_failure, reconnect_target,
    reconnect_target_without_tfo, route_uses_direct_syn_data_tfo,
};
use crate::runtime::state::RuntimeState;

#[derive(Default)]
pub(super) struct RouteRetryState {
    syn_data_retry_attempted: bool,
}

pub(super) struct ReconnectedRoute {
    pub(super) upstream: TcpStream,
    pub(super) route: ConnectionRoute,
}

pub(super) struct FirstResponseFailureContext<'a> {
    pub(super) state: &'a RuntimeState,
    pub(super) target: SocketAddr,
    pub(super) route: &'a ConnectionRoute,
    pub(super) host: Option<String>,
    pub(super) original_request: &'a [u8],
    pub(super) failure: &'a ClassifiedFailure,
    pub(super) response_bytes: Option<Vec<u8>>,
}

pub(super) fn handle_first_write_failure(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<String>,
    original_request: &[u8],
    err: OutboundSendError,
    retry_state: &mut RouteRetryState,
) -> io::Result<ReconnectedRoute> {
    let failure = classify_first_write_failure(&err);
    if should_retry_syn_data(state, route, original_request, &failure, retry_state) {
        tracing::debug!(
            group_index = route.group_index,
            target = %target,
            "retrying first outbound connect without TCP Fast Open for SynData"
        );
        return reconnect_without_tfo(state, target, route, host, original_request, retry_state);
    }

    emit_failure_classified(state, target, &failure, host.as_deref());
    let Some(next_route) =
        advance_route_for_failure(state, target, route, host.clone(), Some(original_request), &failure)?
    else {
        return Err(err.into_io_error());
    };
    reconnect_route(state, target, next_route, host, original_request)
}

pub(super) fn handle_first_response_failure(
    client: &mut TcpStream,
    session_state: &mut SessionState,
    retry_state: &mut RouteRetryState,
    context: FirstResponseFailureContext<'_>,
) -> io::Result<Option<ReconnectedRoute>> {
    if should_retry_syn_data(context.state, context.route, context.original_request, context.failure, retry_state) {
        tracing::debug!(
            group_index = context.route.group_index,
            target = %context.target,
            "retrying first response path without TCP Fast Open for SynData"
        );
        return reconnect_without_tfo(
            context.state,
            context.target,
            context.route,
            context.host,
            context.original_request,
            retry_state,
        )
        .map(Some);
    }

    note_block_signal_for_failure(context.state, context.host.as_deref(), context.failure, None);
    emit_failure_classified(context.state, context.target, context.failure, context.host.as_deref());
    if let Some(next_route) = advance_route_for_failure(
        context.state,
        context.target,
        context.route,
        context.host.clone(),
        Some(context.original_request),
        context.failure,
    )? {
        return reconnect_route(context.state, context.target, next_route, context.host, context.original_request)
            .map(Some);
    }

    if context.failure.action == FailureAction::ResolverOverrideRecommended {
        return Err(io::Error::new(io::ErrorKind::ConnectionReset, context.failure.evidence.summary.clone()));
    }
    if let Some(bytes) = context.response_bytes {
        observe_retry_response_payload(session_state, &bytes);
        client.write_all(&bytes)?;
        return Ok(None);
    }
    if context.failure.class == FailureClass::SilentDrop {
        return Ok(None);
    }
    Err(io::Error::new(io::ErrorKind::ConnectionReset, context.failure.evidence.summary.clone()))
}

fn should_retry_syn_data(
    state: &RuntimeState,
    route: &ConnectionRoute,
    original_request: &[u8],
    failure: &ClassifiedFailure,
    retry_state: &RouteRetryState,
) -> bool {
    let route_requests_direct_syn_data_tfo = route_uses_direct_syn_data_tfo(state, route, Some(original_request));
    should_retry_syn_data_without_tfo(route_requests_direct_syn_data_tfo, failure, retry_state.syn_data_retry_attempted)
}

fn reconnect_without_tfo(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<String>,
    original_request: &[u8],
    retry_state: &mut RouteRetryState,
) -> io::Result<ReconnectedRoute> {
    retry_state.syn_data_retry_attempted = true;
    let upstream = reconnect_target_without_tfo(target, state, route.clone(), host, Some(original_request))?.0;
    Ok(ReconnectedRoute { upstream, route: route.clone() })
}

fn reconnect_route(
    state: &RuntimeState,
    target: SocketAddr,
    route: ConnectionRoute,
    host: Option<String>,
    original_request: &[u8],
) -> io::Result<ReconnectedRoute> {
    let upstream = reconnect_target(target, state, route.clone(), host, Some(original_request))?.0;
    Ok(ReconnectedRoute { upstream, route })
}
