use std::io::{self, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use crate::runtime::adaptive::note_server_ttl_for_route;
use crate::runtime::failure::RuntimeClassifiedFailure;
use crate::runtime::relay::failure_retry::retry_logic::record_stream_relay_success;
use crate::runtime::relay::first_exchange::{FirstResponse, read_first_response};
use crate::runtime::relay::session::FirstOutboundSession;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeConnectionRoute;

pub(super) enum FirstResponseDecision {
    Complete { recorded_success: bool },
    Retry { failure: RuntimeClassifiedFailure, response_bytes: Option<Vec<u8>> },
}

pub(super) struct FirstResponseContext<'a> {
    pub(super) state: &'a RuntimeState,
    pub(super) target: SocketAddr,
    pub(super) route: &'a RuntimeConnectionRoute,
    pub(super) host: Option<&'a str>,
    pub(super) original_request: &'a [u8],
    pub(super) success_strategy_family: Option<&'static str>,
    pub(super) primary_strategy_family: Option<&'static str>,
    pub(super) tls_send_start: Option<Instant>,
}

pub(super) fn handle_first_response(
    client: &mut TcpStream,
    upstream: &mut TcpStream,
    session_state: &mut FirstOutboundSession,
    context: FirstResponseContext<'_>,
) -> io::Result<FirstResponseDecision> {
    match read_first_response(context.state, context.target, context.host, upstream, context.original_request)? {
        FirstResponse::Forward(bytes, server_ttl) => {
            if let Some(start) = context.tls_send_start {
                context.state.note_tls_handshake_completed(context.target, start.elapsed().as_millis() as u64);
            }
            let has_inbound_payload = session_state.observe_first_response_payload(&bytes);
            client.write_all(&bytes)?;
            if !has_inbound_payload {
                return Ok(FirstResponseDecision::Complete { recorded_success: false });
            }
            if RuntimeState::should_track_strategy_target(context.target)
                && let Some(ttl) = server_ttl
            {
                note_server_ttl_for_route(context.state, context.target, context.route.group_index, context.host, ttl)?;
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
