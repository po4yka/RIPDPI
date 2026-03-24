use std::io::{self, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use crate::runtime_policy::{extract_host, ConnectionRoute, TransportProtocol};
use ripdpi_failure_classifier::{
    classify_strategy_execution_failure, classify_transport_error, ClassifiedFailure, FailureAction, FailureClass,
    FailureStage,
};
use ripdpi_session::SessionState;

use super::super::adaptive::{note_adaptive_fake_ttl_success, note_adaptive_tcp_success, note_server_ttl_for_route};
use super::super::desync::{desync_action_context, send_with_group};
use super::super::retry::note_retry_success;
use super::super::routing::{advance_route_for_failure, emit_failure_classified, note_route_success, reconnect_target};
use super::super::state::RuntimeState;
use super::first_exchange::{needs_first_exchange, read_first_response, read_optional_first_request, FirstResponse};

pub(super) struct PreparedRelay {
    pub(super) upstream: TcpStream,
    pub(super) route: ConnectionRoute,
    pub(super) session_state: SessionState,
    pub(super) success_recorded: bool,
    pub(super) success_host: Option<String>,
    pub(super) success_payload: Option<Vec<u8>>,
}

pub(super) fn prepare_relay(
    client: &mut TcpStream,
    mut upstream: TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    mut route: ConnectionRoute,
    seed_request: Option<Vec<u8>>,
) -> io::Result<PreparedRelay> {
    let mut session_state = SessionState::default();
    let mut success_recorded = false;
    let mut success_host = seed_request.as_ref().and_then(|payload| extract_host(&state.config, payload));
    let mut success_payload = seed_request.clone();

    if seed_request.is_some() || needs_first_exchange(state)? {
        let request_timeout = client.read_timeout()?;
        let first_request = if let Some(seed) = seed_request {
            Some(seed)
        } else {
            read_optional_first_request(client, request_timeout)?
        };
        if let Some(first_request) = first_request {
            let original_request = first_request;
            let host = extract_host(&state.config, &original_request);
            success_host = host.clone();
            success_payload = Some(original_request.clone());

            let is_tls = original_request.first().copied() == Some(0x16);
            loop {
                session_state = SessionState::default();
                let progress = session_state.observe_outbound(&original_request);
                let group = state.config.groups[route.group_index].clone();
                let tls_send_start = is_tls.then(Instant::now);
                if let Err(err) = send_with_group(
                    &mut upstream,
                    state,
                    route.group_index,
                    &group,
                    &original_request,
                    progress,
                    host.as_deref(),
                    target,
                ) {
                    let failure = classify_first_write_failure(&err);
                    emit_failure_classified(state, target, &failure, host.as_deref());
                    let next = advance_route_for_failure(
                        state,
                        target,
                        &route,
                        host.clone(),
                        Some(&original_request),
                        &failure,
                    )?;
                    let Some(next) = next else {
                        return Err(err);
                    };
                    route = next;
                    upstream = reconnect_target(target, state, route.clone(), host.clone(), Some(&original_request))?.0;
                    continue;
                }

                match read_first_response(
                    state,
                    target,
                    host.as_deref(),
                    &mut upstream,
                    &state.config,
                    &original_request,
                )? {
                    FirstResponse::Forward(bytes, server_ttl) => {
                        if let (Some(start), Some(telemetry)) = (tls_send_start, &state.telemetry) {
                            telemetry.on_tls_handshake_completed(target, start.elapsed().as_millis() as u64);
                        }
                        session_state.observe_inbound(&bytes);
                        client.write_all(&bytes)?;
                        if session_state.recv_count > 0 {
                            if let Some(ttl) = server_ttl {
                                note_server_ttl_for_route(state, target, route.group_index, host.as_deref(), ttl)?;
                            }
                            record_stream_relay_success(
                                state,
                                target,
                                &route,
                                host.as_deref(),
                                Some(&original_request),
                            )?;
                            success_recorded = true;
                        }
                        break;
                    }
                    FirstResponse::NoData => break,
                    FirstResponse::Failure { failure, response_bytes } => {
                        emit_failure_classified(state, target, &failure, host.as_deref());
                        let next = advance_route_for_failure(
                            state,
                            target,
                            &route,
                            host.clone(),
                            Some(&original_request),
                            &failure,
                        )?;
                        if let Some(next) = next {
                            route = next;
                            upstream =
                                reconnect_target(target, state, route.clone(), host.clone(), Some(&original_request))?
                                    .0;
                            continue;
                        }
                        if failure.action == FailureAction::ResolverOverrideRecommended {
                            return Err(io::Error::new(io::ErrorKind::ConnectionReset, failure.evidence.summary));
                        }
                        if let Some(bytes) = response_bytes {
                            session_state.observe_inbound(&bytes);
                            client.write_all(&bytes)?;
                            break;
                        }
                        if failure.class == FailureClass::SilentDrop {
                            break;
                        }
                        return Err(io::Error::new(io::ErrorKind::ConnectionReset, failure.evidence.summary));
                    }
                }
            }
        }
    }

    Ok(PreparedRelay { upstream, route, session_state, success_recorded, success_host, success_payload })
}

pub(super) fn record_stream_relay_success(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    success_host: Option<&str>,
    success_payload: Option<&[u8]>,
) -> io::Result<()> {
    if let Some(request) = success_payload {
        note_adaptive_tcp_success(state, target, route.group_index, success_host, request)?;
        note_retry_success(state, target, route.group_index, success_host, Some(request), TransportProtocol::Tcp)?;
    }
    note_adaptive_fake_ttl_success(state, target, route.group_index, success_host)?;
    note_route_success(state, target, route, success_host)?;
    Ok(())
}

pub(super) fn classify_first_write_failure(error: &io::Error) -> ClassifiedFailure {
    if let Some(context) = desync_action_context(error) {
        if let Some(failure) = classify_strategy_execution_failure(
            FailureStage::FirstWrite,
            context.action,
            context.kind,
            context.errno,
            error.to_string(),
        ) {
            return failure;
        }
    }
    classify_transport_error(FailureStage::FirstWrite, error)
}
