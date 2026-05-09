use std::io::{self, Read};

use ripdpi_proxy_runtime_adapter::model::decision::ConnectionRoute;

use super::autolearn::{advance_after_failure, record_route_success};
use super::block_signal::record_block_signal;
use super::resolver::resolve_probe_target;
use super::target_catalog::PROBE_TIMEOUT;
use crate::runtime::desync::DesyncSendRequest;
use crate::runtime::routing::{classify_response_failure, connect_target, emit_failure_classified};
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeClassifiedFailure;
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

    let progress = RuntimeState::single_payload_progress(payload.len());
    let send_result = state.send_tcp_desync_payload(
        &mut upstream,
        DesyncSendRequest {
            group_index: route.group_index,
            group_override: None,
            payload: &payload,
            progress: progress.into_adapter(),
            host: Some(domain),
            target,
        },
    );

    if let Err(err) = send_result {
        let io_err = err.into_io_error();
        let failure = RuntimeState::classify_warmup_send_error(&io_err);
        emit_failure_classified(state, target, &failure, Some(domain));
        return advance_after_failure(state, target, &route, domain, &payload, &failure);
    }

    let _ = warmup_platform::enable_recv_ttl(&upstream);
    let mut response_buf = vec![0u8; state.warmup_probe_response_buffer_size()];
    let read_result = upstream.read(&mut response_buf);

    match read_result {
        Ok(0) => handle_blocked_response(
            state,
            target,
            &route,
            domain,
            &payload,
            RuntimeState::classify_warmup_closed_before_response(),
        ),
        Ok(n) => {
            let response = &response_buf[..n];
            if let Some(failure) = classify_response_failure(state, target, &payload, response, Some(domain)) {
                handle_blocked_response(state, target, &route, domain, &payload, failure)
            } else {
                record_route_success(state, target, &route, domain)?;
                Ok(false)
            }
        }
        Err(err) => {
            let failure = RuntimeState::classify_warmup_first_response_error(&err);
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
    failure: RuntimeClassifiedFailure,
) -> io::Result<bool> {
    record_block_signal(state, domain, &failure);
    emit_failure_classified(state, target, &failure, Some(domain));
    advance_after_failure(state, target, route, domain, payload, &failure)
}
