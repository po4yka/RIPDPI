use std::io::{self, Write};
use std::net::{SocketAddr, TcpStream};

use crate::runtime::desync::OutboundSendError;
use crate::runtime::failure::{RuntimeClassifiedFailure, RuntimeFailureAction, RuntimeFailureClass};
use crate::runtime::relay::failure_retry::retry_logic::{
    classify_first_write_failure, should_retry_syn_data_without_tfo,
};
use crate::runtime::relay::session::FirstOutboundSession;
use crate::runtime::routing::{
    advance_route_for_failure, emit_failure_classified, note_block_signal_for_failure, reconnect_target,
    reconnect_target_without_tfo, route_uses_direct_syn_data_tfo,
};
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeConnectionRoute;

#[derive(Default)]
pub(super) struct RouteRetryState {
    syn_data_retry_attempted: bool,
}

pub(super) struct ReconnectedRoute {
    pub(super) upstream: TcpStream,
    pub(super) route: RuntimeConnectionRoute,
}

pub(super) struct FirstResponseFailureContext<'a> {
    pub(super) state: &'a RuntimeState,
    pub(super) target: SocketAddr,
    pub(super) route: &'a RuntimeConnectionRoute,
    pub(super) host: Option<String>,
    pub(super) original_request: &'a [u8],
    pub(super) failure: &'a RuntimeClassifiedFailure,
    pub(super) response_bytes: Option<Vec<u8>>,
}

pub(super) fn handle_first_write_failure(
    state: &RuntimeState,
    target: SocketAddr,
    route: &RuntimeConnectionRoute,
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
    session_state: &mut FirstOutboundSession,
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

    if context.failure.action == RuntimeFailureAction::ResolverOverrideRecommended {
        return Err(io::Error::new(io::ErrorKind::ConnectionReset, context.failure.evidence.summary.clone()));
    }
    if let Some(bytes) = context.response_bytes {
        session_state.observe_retry_response_payload(&bytes);
        client.write_all(&bytes)?;
        return Ok(None);
    }
    if context.failure.class == RuntimeFailureClass::SilentDrop {
        return Ok(None);
    }
    Err(io::Error::new(io::ErrorKind::ConnectionReset, context.failure.evidence.summary.clone()))
}

fn should_retry_syn_data(
    state: &RuntimeState,
    route: &RuntimeConnectionRoute,
    original_request: &[u8],
    failure: &RuntimeClassifiedFailure,
    retry_state: &RouteRetryState,
) -> bool {
    let route_requests_direct_syn_data_tfo = route_uses_direct_syn_data_tfo(state, route, Some(original_request));
    should_retry_syn_data_without_tfo(route_requests_direct_syn_data_tfo, failure, retry_state.syn_data_retry_attempted)
}

fn reconnect_without_tfo(
    state: &RuntimeState,
    target: SocketAddr,
    route: &RuntimeConnectionRoute,
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
    route: RuntimeConnectionRoute,
    host: Option<String>,
    original_request: &[u8],
) -> io::Result<ReconnectedRoute> {
    let upstream = reconnect_target(target, state, route.clone(), host, Some(original_request))?.0;
    Ok(ReconnectedRoute { upstream, route })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::config::RuntimeConfig;
    use crate::runtime::failure::RuntimeFailureStage;
    use std::io::Read;
    use std::net::{Ipv4Addr, TcpListener};
    use std::time::Duration;

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }

    fn runtime_state() -> RuntimeState {
        RuntimeState::test(RuntimeConfig::default())
    }

    fn route() -> RuntimeConnectionRoute {
        RuntimeConnectionRoute { group_index: 0, attempted_mask: 1 }
    }

    fn failure(
        class: RuntimeFailureClass,
        action: RuntimeFailureAction,
        summary: &'static str,
    ) -> RuntimeClassifiedFailure {
        RuntimeClassifiedFailure::new(class, RuntimeFailureStage::FirstResponse, action, summary)
    }

    #[test]
    fn first_response_failure_surfaces_resolver_override_without_retry() {
        let state = runtime_state();
        let route = route();
        let target = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), 443);
        let failure = failure(
            RuntimeFailureClass::DnsTampering,
            RuntimeFailureAction::ResolverOverrideRecommended,
            "resolver override",
        );
        let (mut client, _peer) = connected_pair();
        let mut session = FirstOutboundSession::new();
        let mut retry_state = RouteRetryState::default();

        let result = handle_first_response_failure(
            &mut client,
            &mut session,
            &mut retry_state,
            FirstResponseFailureContext {
                state: &state,
                target,
                route: &route,
                host: Some("example.com".to_string()),
                original_request: b"GET / HTTP/1.1\r\n\r\n",
                failure: &failure,
                response_bytes: None,
            },
        );
        let Err(err) = result else {
            panic!("resolver override should surface");
        };

        assert_eq!(err.kind(), io::ErrorKind::ConnectionReset);
    }

    #[test]
    fn first_response_failure_forwards_retry_response_payload() {
        let state = runtime_state();
        let route = route();
        let target = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), 443);
        let failure = failure(RuntimeFailureClass::Unknown, RuntimeFailureAction::None, "response available");
        let (mut client, mut peer) = connected_pair();
        let mut session = FirstOutboundSession::new();
        let mut retry_state = RouteRetryState::default();
        peer.set_read_timeout(Some(Duration::from_secs(1))).expect("set peer timeout");

        let reconnected = handle_first_response_failure(
            &mut client,
            &mut session,
            &mut retry_state,
            FirstResponseFailureContext {
                state: &state,
                target,
                route: &route,
                host: None,
                original_request: b"GET / HTTP/1.1\r\n\r\n",
                failure: &failure,
                response_bytes: Some(b"HTTP/1.1 403 Forbidden\r\n\r\n".to_vec()),
            },
        )
        .expect("response forwarding succeeds");

        let mut forwarded = [0u8; 26];
        peer.read_exact(&mut forwarded).expect("read forwarded response");
        assert!(reconnected.is_none());
        assert_eq!(&forwarded, b"HTTP/1.1 403 Forbidden\r\n\r\n");
    }

    #[test]
    fn first_response_failure_treats_silent_drop_as_terminal_without_error() {
        let state = runtime_state();
        let route = route();
        let target = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), 443);
        let failure = failure(RuntimeFailureClass::SilentDrop, RuntimeFailureAction::None, "silent drop");
        let (mut client, _peer) = connected_pair();
        let mut session = FirstOutboundSession::new();
        let mut retry_state = RouteRetryState::default();

        let reconnected = handle_first_response_failure(
            &mut client,
            &mut session,
            &mut retry_state,
            FirstResponseFailureContext {
                state: &state,
                target,
                route: &route,
                host: None,
                original_request: b"GET / HTTP/1.1\r\n\r\n",
                failure: &failure,
                response_bytes: None,
            },
        )
        .expect("silent drop should not force reconnect");

        assert!(reconnected.is_none());
    }
}
