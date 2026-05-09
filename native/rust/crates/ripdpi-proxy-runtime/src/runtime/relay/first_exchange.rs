use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure;
#[cfg(test)]
use ripdpi_proxy_runtime_adapter::model::config::{
    first_response_timeout as projected_first_response_timeout, first_response_timeout_count_limit,
    FirstResponseSettings,
};
use ripdpi_proxy_runtime_adapter::platform::first_response as first_response_platform;
#[cfg(test)]
use ripdpi_proxy_runtime_adapter::protocol_payload::FirstResponseBoundaryTracker;

use super::super::routing::{classify_response_failure, note_block_signal_for_failure};
use super::super::state::RuntimeState;

pub(super) enum FirstResponse {
    Forward(Vec<u8>, Option<u8>),
    Failure { failure: ClassifiedFailure, response_bytes: Option<Vec<u8>> },
    NoData,
}

pub(super) fn needs_first_exchange(state: &RuntimeState) -> io::Result<bool> {
    state.first_response_exchange_required()
}

pub(super) fn read_first_response(
    state: &RuntimeState,
    target: SocketAddr,
    host: Option<&str>,
    upstream: &mut TcpStream,
    request: &[u8],
) -> io::Result<FirstResponse> {
    let _ = first_response_platform::enable_recv_ttl(upstream);
    let mut collected = Vec::new();
    let mut chunk = vec![0u8; state.relay_first_response_buffer_size()];
    let mut tls_partial = state.relay_first_response_boundary_tracker(request);
    let mut timeout_count = 0i32;
    let mut observed_server_ttl: Option<u8> = None;

    loop {
        let _ = upstream.set_read_timeout(state.relay_first_response_timeout(&tls_partial));
        let read_result = if collected.is_empty() {
            first_response_platform::read_chunk_with_ttl(upstream, &mut chunk).map(|(n, ttl)| {
                if ttl.is_some() {
                    observed_server_ttl = ttl;
                }
                n
            })
        } else {
            upstream.read(&mut chunk)
        };
        let result = match read_result {
            Ok(0) => Ok(FirstResponse::Failure {
                failure: RuntimeState::classify_first_response_closed_before_response(),
                response_bytes: None,
            }),
            Ok(n) => {
                collected.extend_from_slice(&chunk[..n]);
                tls_partial.observe(&chunk[..n]);

                if tls_partial.waiting_for_tls_record() {
                    continue;
                }

                if let Some(failure) = classify_response_failure(state, target, request, &collected, host) {
                    Ok(FirstResponse::Failure { failure, response_bytes: Some(collected) })
                } else {
                    Ok(FirstResponse::Forward(collected, observed_server_ttl))
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if tls_partial.waiting_for_tls_record() {
                    timeout_count += 1;
                    if timeout_count >= state.relay_first_response_timeout_count_limit() {
                        Ok(FirstResponse::Failure {
                            failure: RuntimeState::classify_first_response_partial_tls_timeout(),
                            response_bytes: None,
                        })
                    } else {
                        continue;
                    }
                } else if state.relay_first_response_reports_timeout_failure() {
                    let failure = RuntimeState::classify_first_response_transport_error(&err);
                    let retransmissions = first_response_platform::tcp_total_retransmissions(upstream).ok().flatten();
                    note_block_signal_for_failure(state, host, &failure, retransmissions);
                    Ok(FirstResponse::Failure { failure, response_bytes: None })
                } else {
                    Ok(FirstResponse::NoData)
                }
            }
            Err(err)
                if matches!(
                    err.kind(),
                    io::ErrorKind::ConnectionReset
                        | io::ErrorKind::ConnectionAborted
                        | io::ErrorKind::BrokenPipe
                        | io::ErrorKind::ConnectionRefused
                        | io::ErrorKind::InvalidInput
                        | io::ErrorKind::TimedOut
                        | io::ErrorKind::HostUnreachable
                ) =>
            {
                let failure = RuntimeState::classify_first_response_transport_error(&err);
                note_block_signal_for_failure(
                    state,
                    host,
                    &failure,
                    first_response_platform::tcp_total_retransmissions(upstream).ok().flatten(),
                );
                Ok(FirstResponse::Failure { failure, response_bytes: None })
            }
            Err(err) => Err(err),
        };
        let _ = upstream.set_read_timeout(None);
        return result;
    }
}

#[cfg(test)]
pub(super) fn first_response_timeout(
    settings: FirstResponseSettings,
    tls_partial: &FirstResponseBoundaryTracker,
) -> Option<std::time::Duration> {
    projected_first_response_timeout(settings, tls_partial.active())
}

#[cfg(test)]
pub(super) fn timeout_count_limit(settings: FirstResponseSettings) -> i32 {
    first_response_timeout_count_limit(settings)
}
