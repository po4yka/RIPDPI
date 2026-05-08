use std::io::{self, Read};

use ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure;
use ripdpi_proxy_runtime_adapter::model::decision::ConnectionRoute;
use ripdpi_proxy_runtime_adapter::model::session::OutboundProgress;

use super::autolearn::{advance_after_failure, record_route_success};
use super::block_signal::record_block_signal;
use super::classification::{
    classify_closed_before_response, classify_first_response_error, classify_response, classify_send_error,
    emit_classified_failure,
};
use super::resolver::resolve_probe_target;
use super::target_catalog::PROBE_TIMEOUT;
use crate::runtime::desync::{send_with_group, DesyncSendRequest};
use crate::runtime::routing::connect_target;
use crate::runtime::state::RuntimeState;
use ripdpi_proxy_runtime_adapter::platform::warmup as warmup_platform;

/// Probe a single domain by resolving it, connecting through the desync
/// pipeline, sending a TLS ClientHello, and reading the first response.
///
/// Returns `Ok(true)` if the probe triggered an autolearn escalation,
/// `Ok(false)` if the connection succeeded on the first group.
pub(crate) fn probe_domain(state: &RuntimeState, domain: &str) -> io::Result<bool> {
    let target = resolve_probe_target(state, domain)?;
    let payload = RuntimeState::build_probe_client_hello(domain);
    let (mut upstream, route) = connect_target(target, state, Some(&payload), false, Some(domain.to_owned()))?;

    let _ = upstream.set_write_timeout(Some(PROBE_TIMEOUT));
    let _ = upstream.set_read_timeout(Some(PROBE_TIMEOUT));

    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let send_result = send_with_group(
        &mut upstream,
        state,
        DesyncSendRequest {
            group_index: route.group_index,
            group_override: None,
            payload: &payload,
            progress,
            host: Some(domain),
            target,
        },
    );

    if let Err(err) = send_result {
        let io_err = err.into_io_error();
        let failure = classify_send_error(&io_err);
        emit_classified_failure(state, target, &failure, domain);
        return advance_after_failure(state, target, &route, domain, &payload, &failure);
    }

    let _ = warmup_platform::enable_recv_ttl(&upstream);
    let mut response_buf = vec![0u8; state.warmup_probe_response_buffer_size()];
    let read_result = upstream.read(&mut response_buf);

    match read_result {
        Ok(0) => handle_blocked_response(state, target, &route, domain, &payload, classify_closed_before_response()),
        Ok(n) => {
            let response = &response_buf[..n];
            if let Some(failure) = classify_response(state, target, &payload, response, domain) {
                handle_blocked_response(state, target, &route, domain, &payload, failure)
            } else {
                record_route_success(state, target, &route, domain)?;
                Ok(false)
            }
        }
        Err(err) => {
            let failure = classify_first_response_error(&err);
            handle_blocked_response(state, target, &route, domain, &payload, failure)
        }
    }
}

fn handle_blocked_response(
    state: &RuntimeState,
    target: std::net::SocketAddr,
    route: &ConnectionRoute,
    domain: &str,
    payload: &[u8],
    failure: ClassifiedFailure,
) -> io::Result<bool> {
    record_block_signal(state, domain, &failure);
    emit_classified_failure(state, target, &failure, domain);
    advance_after_failure(state, target, route, domain, payload, &failure)
}
