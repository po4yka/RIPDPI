use std::io;
use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use crate::runtime::relay::failure_retry::first_outbound::execution::execute_first_write;
use crate::runtime::relay::failure_retry::first_outbound::payload::{FirstOutboundRead, prepare_first_payload};
use crate::runtime::relay::failure_retry::first_outbound::response::{
    FirstResponseContext, FirstResponseDecision, handle_first_response,
};
use crate::runtime::relay::failure_retry::first_outbound::route_retry::{
    FirstResponseFailureContext, RouteRetryState, handle_first_response_failure, handle_first_write_failure,
};
use crate::runtime::relay::first_exchange::needs_first_exchange;
use crate::runtime::relay::session::{FirstOutboundSession, RelaySession};
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeConnectionRoute;
use ripdpi_proxy_runtime_adapter::model::runtime_api::AttemptCorrelationId;

mod execution;
mod payload;
mod response;
mod route_retry;

pub(crate) struct PreparedRelay {
    pub(crate) upstream: TcpStream,
    pub(crate) route: RuntimeConnectionRoute,
    pub(crate) session_state: RelaySession,
    pub(crate) success_recorded: bool,
    pub(crate) success_host: Option<String>,
    pub(crate) success_payload: Option<Vec<u8>>,
    pub(crate) success_strategy_family: Option<&'static str>,
    pub(crate) client_closed: bool,
}

impl PreparedRelay {
    fn without_first_payload(
        upstream: TcpStream,
        route: RuntimeConnectionRoute,
        session: FirstOutboundSession,
        client_closed: bool,
    ) -> Self {
        Self {
            upstream,
            route,
            session_state: session.into_relay_session(),
            success_recorded: false,
            success_host: None,
            success_payload: None,
            success_strategy_family: None,
            client_closed,
        }
    }
}

pub(super) struct FirstOutboundCoordinator<'a> {
    state: &'a RuntimeState,
    target: SocketAddr,
    route: RuntimeConnectionRoute,
    seed_request: Option<Vec<u8>>,
    attempt_token: Option<AttemptCorrelationId>,
}

impl<'a> FirstOutboundCoordinator<'a> {
    fn new(
        state: &'a RuntimeState,
        target: SocketAddr,
        route: RuntimeConnectionRoute,
        seed_request: Option<Vec<u8>>,
        attempt_token: Option<AttemptCorrelationId>,
    ) -> Self {
        Self { state, target, route, seed_request, attempt_token }
    }

    fn run(self, client: &mut TcpStream, mut upstream: TcpStream) -> io::Result<PreparedRelay> {
        let first_payload = match prepare_first_payload(client, &upstream, self.state, self.seed_request)? {
            FirstOutboundRead::Payload(first_payload) => first_payload,
            FirstOutboundRead::ClientClosed => {
                return Ok(PreparedRelay::without_first_payload(
                    upstream,
                    self.route,
                    FirstOutboundSession::new(),
                    true,
                ));
            }
            FirstOutboundRead::ServerFirst => {
                // Server-speaks-first protocols (SMTP, IMAP, FTP, SSH, ...)
                // wait for the upstream greeting before they send anything.
                // Skip the first-write exchange (first-response inspection and
                // route retry) and relay both directions. The greeting is
                // still unread in `upstream`. Client payloads then use the
                // steady-state relay desync path, with the same Lua flow that
                // a first write would open.
                tracing::debug!(
                    group_index = self.route.group_index,
                    "upstream spoke first; relaying without the first-write exchange"
                );
                let mut session = FirstOutboundSession::new();
                session.lua_flow = self.state.new_lua_flow();
                return Ok(PreparedRelay::without_first_payload(upstream, self.route, session, false));
            }
        };

        let mut route = self.route;
        let mut retry_state = RouteRetryState::default();
        let mut session_state;
        let mut success_recorded = false;
        let mut success_strategy_family;
        let inspect_first_response = needs_first_exchange(self.state)?;

        loop {
            let _active_upstream = self.state.register_active_upstream_tcp_socket(&upstream)?;
            session_state = FirstOutboundSession::new();
            let tls_send_start = first_payload.is_tls.then(Instant::now);
            match execute_first_write(
                &mut upstream,
                self.state,
                self.target,
                &route,
                &first_payload.original_request,
                first_payload.host.as_deref(),
                &mut session_state,
                self.attempt_token.as_ref(),
            ) {
                Ok(strategy_family) => success_strategy_family = strategy_family,
                Err(err) => {
                    let retry = handle_first_write_failure(
                        self.state,
                        self.target,
                        &route,
                        first_payload.host.clone(),
                        &first_payload.original_request,
                        err,
                        &mut retry_state,
                    )?;
                    route = retry.route;
                    upstream = retry.upstream;
                    continue;
                }
            }

            if !inspect_first_response {
                break;
            }

            match handle_first_response(
                client,
                &mut upstream,
                &mut session_state,
                FirstResponseContext {
                    state: self.state,
                    target: self.target,
                    route: &route,
                    host: first_payload.host.as_deref(),
                    original_request: &first_payload.original_request,
                    success_strategy_family,
                    primary_strategy_family: self.state.primary_tcp_strategy_family(route.group_index),
                    tls_send_start,
                },
            )? {
                FirstResponseDecision::Continue(pending) => {
                    // No route retry can run after this move. The outbound copy may
                    // now commit continuation bytes that this coordinator cannot replay.
                    session_state.defer_first_response(*pending);
                    break;
                }
                FirstResponseDecision::Complete { recorded_success } => {
                    success_recorded = recorded_success;
                    break;
                }
                FirstResponseDecision::Retry { failure, response_bytes } => {
                    let Some(retry) = handle_first_response_failure(
                        client,
                        &mut session_state,
                        &mut retry_state,
                        FirstResponseFailureContext {
                            state: self.state,
                            target: self.target,
                            route: &route,
                            host: first_payload.host.clone(),
                            original_request: &first_payload.original_request,
                            failure: &failure,
                            response_bytes,
                        },
                    )?
                    else {
                        session_state.reject_first_response();
                        break;
                    };
                    route = retry.route;
                    upstream = retry.upstream;
                }
            }
        }

        Ok(PreparedRelay {
            upstream,
            route,
            session_state: session_state.into_relay_session(),
            success_recorded,
            success_host: first_payload.host,
            success_payload: Some(first_payload.original_request),
            success_strategy_family,
            client_closed: false,
        })
    }
}

#[inline(never)]
pub(crate) fn prepare_relay(
    client: &mut TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    route: RuntimeConnectionRoute,
    seed_request: Option<Vec<u8>>,
    attempt_token: Option<AttemptCorrelationId>,
) -> io::Result<PreparedRelay> {
    FirstOutboundCoordinator::new(state, target, route, seed_request, attempt_token).run(client, upstream)
}
