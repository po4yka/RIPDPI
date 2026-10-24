use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

#[cfg(test)]
use super::super::config::{
    FirstResponseSettings, first_response_timeout as projected_first_response_timeout,
    first_response_timeout_count_limit,
};
use super::super::payload::RuntimeFirstResponseBoundaryTracker;

use super::super::failure::RuntimeClassifiedFailure;
use super::super::routing::{classify_response_failure, note_block_signal_for_failure};
use super::super::state::RuntimeState;
use super::platform as relay_platform;

mod readiness;
#[cfg(test)]
mod tests;

use readiness::{Readiness, wait_for_readiness};

pub(super) enum FirstResponse {
    Forward(Vec<u8>, Option<u8>),
    Failure { failure: RuntimeClassifiedFailure, response_bytes: Option<Vec<u8>> },
    NoData,
    Continuation(Box<FirstResponseReader>),
}

pub(super) struct FirstResponseReader {
    collected: Vec<u8>,
    chunk: Vec<u8>,
    tls_partial: RuntimeFirstResponseBoundaryTracker,
    timeout_count: i32,
    observed_server_ttl: Option<u8>,
    deadline: Option<Instant>,
}

impl FirstResponseReader {
    pub(super) fn new(state: &RuntimeState, request: &[u8]) -> Self {
        let tls_partial = state.relay_first_response_boundary_tracker(request);
        let deadline = state.relay_first_response_timeout(&tls_partial).map(|timeout| Instant::now() + timeout);
        Self {
            collected: Vec::new(),
            chunk: vec![0u8; state.relay_first_response_buffer_size()],
            tls_partial,
            timeout_count: 0,
            observed_server_ttl: None,
            deadline,
        }
    }

    fn restart_deadline(&mut self, state: &RuntimeState) {
        self.deadline = state.relay_first_response_timeout(&self.tls_partial).map(|timeout| Instant::now() + timeout);
    }
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
    client: Option<&TcpStream>,
    mut reader: FirstResponseReader,
) -> io::Result<FirstResponse> {
    let _ = relay_platform::enable_recv_ttl(upstream);

    loop {
        let readiness = wait_for_readiness(state, client, upstream, reader.deadline)?;
        if readiness == Readiness::Client {
            // The outbound copy remains the only owner of continuation bytes.
            return Ok(FirstResponse::Continuation(Box::new(reader)));
        }
        let read_result = if readiness == Readiness::Timeout {
            Err(io::Error::new(io::ErrorKind::TimedOut, "first response deadline"))
        } else if reader.collected.is_empty() {
            relay_platform::read_chunk_with_ttl(upstream, &mut reader.chunk).map(|(n, ttl)| {
                if ttl.is_some() {
                    reader.observed_server_ttl = ttl;
                }
                n
            })
        } else {
            upstream.read(&mut reader.chunk)
        };
        let result = match read_result {
            Ok(0) => Ok(FirstResponse::Failure {
                failure: RuntimeState::classify_first_response_closed_before_response(),
                response_bytes: None,
            }),
            Ok(n) => {
                reader.collected.extend_from_slice(&reader.chunk[..n]);
                reader.tls_partial.observe(&reader.chunk[..n]);

                if reader.tls_partial.waiting_for_tls_record() {
                    reader.restart_deadline(state);
                    continue;
                }

                if let Some(failure) = classify_response_failure(state, target, request, &reader.collected, host) {
                    Ok(FirstResponse::Failure { failure, response_bytes: Some(reader.collected) })
                } else {
                    Ok(FirstResponse::Forward(reader.collected, reader.observed_server_ttl))
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if reader.tls_partial.waiting_for_tls_record() {
                    reader.timeout_count += 1;
                    if reader.timeout_count >= state.relay_first_response_timeout_count_limit() {
                        Ok(FirstResponse::Failure {
                            failure: RuntimeState::classify_first_response_partial_tls_timeout(),
                            response_bytes: None,
                        })
                    } else {
                        reader.restart_deadline(state);
                        continue;
                    }
                } else if state.relay_first_response_reports_timeout_failure() {
                    let failure = RuntimeState::classify_first_response_transport_error(&err);
                    let retransmissions =
                        relay_platform::first_response_tcp_total_retransmissions(upstream).ok().flatten();
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
                    relay_platform::first_response_tcp_total_retransmissions(upstream).ok().flatten(),
                );
                Ok(FirstResponse::Failure { failure, response_bytes: None })
            }
            Err(err) => Err(err),
        };
        return result;
    }
}

#[cfg(test)]
pub(super) fn first_response_timeout(
    settings: FirstResponseSettings,
    tls_partial: &RuntimeFirstResponseBoundaryTracker,
) -> Option<std::time::Duration> {
    projected_first_response_timeout(settings, tls_partial.active())
}

#[cfg(test)]
pub(super) fn timeout_count_limit(settings: FirstResponseSettings) -> i32 {
    first_response_timeout_count_limit(settings)
}
