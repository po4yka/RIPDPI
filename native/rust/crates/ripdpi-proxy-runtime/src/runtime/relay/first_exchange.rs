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
use super::session::RelaySharedSession;

#[cfg(test)]
mod http_response_tests;
mod readiness;
#[cfg(test)]
mod tests;
#[cfg(test)]
mod tls_alert_tests;

use readiness::{Readiness, wait_for_readiness};

pub(super) enum FirstResponse {
    Forward(Vec<u8>, Option<u8>),
    Neutral(Vec<u8>),
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
    response_owner: Option<RelaySharedSession>,
}

impl FirstResponseReader {
    pub(super) fn new(state: &RuntimeState, request: &[u8]) -> Self {
        let tls_partial = state.relay_first_response_boundary_tracker(request);
        let timeout = [state.relay_first_response_timeout(&tls_partial), tls_partial.inspection_timeout()]
            .into_iter()
            .flatten()
            .min();
        let deadline = timeout.map(|timeout| Instant::now() + timeout);
        Self {
            collected: Vec::new(),
            chunk: vec![0u8; state.relay_first_response_buffer_size()],
            tls_partial,
            timeout_count: 0,
            observed_server_ttl: None,
            deadline,
            response_owner: None,
        }
    }

    /// The relay revokes this owner before it closes upstream after a client read error.
    /// An ordinary client write FIN does not revoke the response owner.
    pub(super) fn watch_response_owner(&mut self, owner: RelaySharedSession) {
        self.response_owner = Some(owner);
    }

    fn response_owner_revoked(&self) -> io::Result<bool> {
        self.response_owner.as_ref().map_or(Ok(false), RelaySharedSession::response_owner_revoked)
    }

    fn finish_collected(
        self,
        state: &RuntimeState,
        target: SocketAddr,
        request: &[u8],
        host: Option<&str>,
        inconclusive: bool,
    ) -> FirstResponse {
        // Limits discard body evidence, but complete HTTP headers remain independent evidence.
        let failure = if !inconclusive || self.tls_partial.inspecting_http() {
            self.tls_partial
                .classification_bytes(&self.collected, !inconclusive)
                .and_then(|bytes| classify_response_failure(state, target, request, bytes, host))
        } else {
            None
        };
        if let Some(failure) = failure {
            FirstResponse::Failure { failure, response_bytes: Some(self.collected) }
        } else if inconclusive
            || self.tls_partial.neutral_http()
            || ripdpi_proxy_runtime_adapter::failure::is_tls_closure_response(&self.collected)
            || self.tls_partial.invalid_alert(&self.collected)
        {
            FirstResponse::Neutral(self.collected)
        } else {
            FirstResponse::Forward(self.collected, self.observed_server_ttl)
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
        if reader.response_owner_revoked()? {
            return Ok(FirstResponse::NoData);
        }
        let readiness = wait_for_readiness(state, client, upstream, reader.deadline)?;
        if readiness == Readiness::Client {
            // The outbound copy remains the only owner of continuation bytes.
            return Ok(FirstResponse::Continuation(Box::new(reader)));
        }
        let inspection_timeout = readiness == Readiness::Timeout
            && reader.tls_partial.inspecting_response(&reader.collected)
            && reader.tls_partial.waiting_for_response(&reader.collected);
        let capacity = if reader.tls_partial.inspection_timeout().is_some()
            && (reader.collected.is_empty() || reader.tls_partial.inspecting_response(&reader.collected))
        {
            reader
                .chunk
                .len()
                .min(reader.tls_partial.inspection_bytes_limit().saturating_sub(reader.collected.len()).max(1))
        } else {
            reader.chunk.len()
        };
        let read_result = if readiness == Readiness::Timeout {
            Err(io::Error::new(io::ErrorKind::TimedOut, "first response deadline"))
        } else if reader.collected.is_empty() {
            relay_platform::read_chunk_with_ttl(upstream, &mut reader.chunk[..capacity]).map(|(n, ttl)| {
                if ttl.is_some() {
                    reader.observed_server_ttl = ttl;
                }
                n
            })
        } else {
            upstream.read(&mut reader.chunk[..capacity])
        };
        if state.shutdown_requested() {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "proxy stopped during first response"));
        }
        // A local read shutdown can yield EOF. Check causal ownership before HTTP EOF finalization.
        if reader.response_owner_revoked()? {
            return Ok(FirstResponse::NoData);
        }
        let result = match read_result {
            Ok(0) if reader.tls_partial.finish_http_on_eof() => {
                let expired = reader.deadline.is_some_and(|deadline| Instant::now() >= deadline);
                Ok(reader.finish_collected(state, target, request, host, expired))
            }
            Ok(0) => Ok(FirstResponse::Failure {
                failure: RuntimeState::classify_first_response_closed_before_response(),
                response_bytes: None,
            }),
            Ok(n) => {
                reader.collected.extend_from_slice(&reader.chunk[..n]);
                reader.tls_partial.observe(&reader.chunk[..n]);

                let expired = reader.tls_partial.inspecting_response(&reader.collected)
                    && reader.deadline.is_some_and(|deadline| Instant::now() >= deadline);
                let exhausted = reader.tls_partial.inspection_exhausted(&reader.collected);
                if !expired && !exhausted && reader.tls_partial.waiting_for_response(&reader.collected) {
                    if !reader.tls_partial.inspecting_response(&reader.collected) {
                        reader.restart_deadline(state);
                    }
                    continue;
                }
                Ok(reader.finish_collected(state, target, request, host, expired || exhausted))
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if inspection_timeout {
                    Ok(reader.finish_collected(state, target, request, host, true))
                } else if reader.tls_partial.inspecting_response(&reader.collected) {
                    // A socket I/O timeout is a transport source, not an inspection deadline.
                    let failure = RuntimeState::classify_first_response_transport_error(&err);
                    Ok(FirstResponse::Failure { failure, response_bytes: None })
                } else if reader.tls_partial.waiting_for_tls_record() {
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
                } else if state.relay_first_response_reports_timeout_failure()
                    // A TFO socket still unconnected here never got a SYN-ACK: report the
                    // silent drop so the no-TFO retry can run.
                    || ripdpi_proxy_runtime_adapter::platform::connect::tcp_fast_open_deferred(upstream)
                {
                    let failure = RuntimeState::classify_first_response_transport_error(&err);
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
                Ok(FirstResponse::Failure { failure, response_bytes: None })
            }
            Err(err) => Err(err),
        };
        if let Ok(FirstResponse::Failure { failure, .. }) = &result {
            // The only block-signal emission for a first-response failure: autolearn confirms a
            // block on two signals, so the failure handlers must not note it again.
            let retransmissions = relay_platform::first_response_tcp_total_retransmissions(upstream).ok().flatten();
            note_block_signal_for_failure(state, host, failure, retransmissions);
        }
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
