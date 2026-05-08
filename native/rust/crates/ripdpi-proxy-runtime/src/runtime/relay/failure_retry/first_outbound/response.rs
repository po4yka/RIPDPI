use std::io::{self, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure;
use ripdpi_proxy_runtime_adapter::model::config::FirstResponseSettings;
use ripdpi_proxy_runtime_adapter::model::decision::ConnectionRoute;
use ripdpi_proxy_runtime_adapter::model::session::SessionState;

use crate::runtime::adaptive::note_server_ttl_for_route;
use crate::runtime::relay::failure_retry::first_outbound::observations::observe_first_response_payload;
use crate::runtime::relay::failure_retry::retry_logic::record_stream_relay_success;
use crate::runtime::relay::first_exchange::{read_first_response, FirstResponse};
use crate::runtime::routing::should_track_strategy_target;
use crate::runtime::state::RuntimeState;

pub(super) enum FirstResponseDecision {
    Complete { recorded_success: bool },
    Retry { failure: ClassifiedFailure, response_bytes: Option<Vec<u8>> },
}

pub(super) struct FirstResponseContext<'a> {
    pub(super) state: &'a RuntimeState,
    pub(super) target: SocketAddr,
    pub(super) route: &'a ConnectionRoute,
    pub(super) host: Option<&'a str>,
    pub(super) original_request: &'a [u8],
    pub(super) response_settings: FirstResponseSettings,
    pub(super) success_strategy_family: Option<&'static str>,
    pub(super) primary_strategy_family: Option<&'static str>,
    pub(super) tls_send_start: Option<Instant>,
}

pub(super) fn handle_first_response(
    client: &mut TcpStream,
    upstream: &mut TcpStream,
    session_state: &mut SessionState,
    context: FirstResponseContext<'_>,
) -> io::Result<FirstResponseDecision> {
    match read_first_response(
        context.state,
        context.target,
        context.host,
        upstream,
        context.response_settings,
        context.original_request,
    )? {
        FirstResponse::Forward(bytes, server_ttl) => {
            if let (Some(start), Some(telemetry)) = (context.tls_send_start, &context.state.telemetry) {
                telemetry.on_tls_handshake_completed(context.target, start.elapsed().as_millis() as u64);
            }
            let has_inbound_payload = observe_first_response_payload(session_state, &bytes);
            client.write_all(&bytes)?;
            if !has_inbound_payload {
                return Ok(FirstResponseDecision::Complete { recorded_success: false });
            }
            if should_track_strategy_target(context.target) {
                if let Some(ttl) = server_ttl {
                    note_server_ttl_for_route(
                        context.state,
                        context.target,
                        context.route.group_index,
                        context.host,
                        ttl,
                    )?;
                }
            }
            record_stream_relay_success(
                context.state,
                context.target,
                context.route,
                context.host,
                Some(context.original_request),
                context.success_strategy_family,
                context.primary_strategy_family,
            )?;
            Ok(FirstResponseDecision::Complete { recorded_success: true })
        }
        FirstResponse::NoData => Ok(FirstResponseDecision::Complete { recorded_success: false }),
        FirstResponse::Failure { failure, response_bytes } => {
            Ok(FirstResponseDecision::Retry { failure, response_bytes })
        }
    }
}
