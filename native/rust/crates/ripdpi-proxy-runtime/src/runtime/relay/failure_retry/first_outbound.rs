use std::io;
use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::model::config::{primary_tcp_strategy_family_with, FirstResponseSettings};
use ripdpi_proxy_runtime_adapter::model::decision::ConnectionRoute;
use ripdpi_proxy_runtime_adapter::model::session::{new_session_state, FirstOutboundPayloadPolicy, SessionState};
use ripdpi_proxy_runtime_adapter::response_triggers::FirstResponseExchangePolicy;

use crate::runtime::relay::failure_retry::first_outbound::execution::execute_first_write;
use crate::runtime::relay::failure_retry::first_outbound::payload::prepare_first_payload;
use crate::runtime::relay::failure_retry::first_outbound::response::{
    handle_first_response, FirstResponseContext, FirstResponseDecision,
};
use crate::runtime::relay::failure_retry::first_outbound::route_retry::{
    handle_first_response_failure, handle_first_write_failure, FirstResponseFailureContext, RouteRetryState,
};
use crate::runtime::relay::first_exchange::needs_first_exchange;
use crate::runtime::state::RuntimeState;

mod execution;
mod observations;
mod payload;
mod response;
mod route_retry;

pub(crate) struct PreparedRelay {
    pub(crate) upstream: TcpStream,
    pub(crate) route: ConnectionRoute,
    pub(crate) session_state: SessionState,
    pub(crate) success_recorded: bool,
    pub(crate) success_host: Option<String>,
    pub(crate) success_payload: Option<Vec<u8>>,
    pub(crate) success_strategy_family: Option<&'static str>,
    pub(crate) client_closed: bool,
}

pub(super) struct FirstOutboundCoordinator<'a> {
    state: &'a RuntimeState,
    target: SocketAddr,
    route: ConnectionRoute,
    seed_request: Option<Vec<u8>>,
    policies: FirstOutboundPolicies,
}

struct FirstOutboundPolicies {
    payload: FirstOutboundPayloadPolicy,
    response: FirstResponseSettings,
    exchange: FirstResponseExchangePolicy,
}

impl<'a> FirstOutboundCoordinator<'a> {
    fn new(state: &'a RuntimeState, target: SocketAddr, route: ConnectionRoute, seed_request: Option<Vec<u8>>) -> Self {
        let policies = FirstOutboundPolicies {
            payload: state.first_outbound_payload_policy.clone(),
            response: state.relay_first_response,
            exchange: state.first_response_exchange_policy,
        };
        Self { state, target, route, seed_request, policies }
    }

    fn run(self, client: &mut TcpStream, mut upstream: TcpStream) -> io::Result<PreparedRelay> {
        let first_payload = prepare_first_payload(client, &self.policies.payload, self.seed_request)?;
        let Some(first_payload) = first_payload else {
            return Ok(PreparedRelay {
                upstream,
                route: self.route,
                session_state: new_session_state(),
                success_recorded: false,
                success_host: None,
                success_payload: None,
                success_strategy_family: None,
                client_closed: true,
            });
        };

        let mut route = self.route;
        let mut retry_state = RouteRetryState::default();
        let mut session_state;
        let mut success_recorded = false;
        let mut success_strategy_family;
        let inspect_first_response = needs_first_exchange(self.state, self.policies.exchange)?;

        loop {
            session_state = new_session_state();
            let tls_send_start = first_payload.is_tls.then(Instant::now);
            match execute_first_write(
                &mut upstream,
                self.state,
                self.target,
                &route,
                &first_payload.original_request,
                first_payload.host.as_deref(),
                &mut session_state,
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
                    response_settings: self.policies.response,
                    success_strategy_family,
                    primary_strategy_family: primary_tcp_strategy_family_with(
                        &self.state.relay_group_settings,
                        route.group_index,
                    ),
                    tls_send_start,
                },
            )? {
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
            session_state,
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
    route: ConnectionRoute,
    seed_request: Option<Vec<u8>>,
) -> io::Result<PreparedRelay> {
    FirstOutboundCoordinator::new(state, target, route, seed_request).run(client, upstream)
}
