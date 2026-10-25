use std::io::{self, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use super::super::adaptive::note_server_ttl_for_route;
use super::super::failure::{RuntimeFailureAction, RuntimeFailureClass};
use super::super::routing::{emit_failure_classified, note_block_signal_for_failure};
use super::super::state::RuntimeState;
use super::super::types::RuntimeConnectionRoute;
use super::failure_retry::record_stream_relay_success;
use super::first_exchange::{FirstResponse, FirstResponseReader, read_first_response};
use super::session::{FirstResponseOutcome, RelaySharedSession};

/// One owner retains the bounded response collector across duplex handoff.
/// This type has no reconnect operation or replay authority.
pub(super) struct PendingFirstResponse {
    pub(super) reader: FirstResponseReader,
    pub(super) target: SocketAddr,
    pub(super) route: RuntimeConnectionRoute,
    pub(super) host: Option<String>,
    pub(super) original_request: Vec<u8>,
    pub(super) success_strategy_family: Option<&'static str>,
    pub(super) primary_strategy_family: Option<&'static str>,
    pub(super) tls_send_start: Option<Instant>,
}

impl PendingFirstResponse {
    pub(super) fn finish(
        self,
        state: &RuntimeState,
        upstream: &mut TcpStream,
        client: &mut TcpStream,
        session: &RelaySharedSession,
    ) -> io::Result<()> {
        match read_first_response(
            state,
            self.target,
            self.host.as_deref(),
            upstream,
            &self.original_request,
            None,
            self.reader,
        )? {
            FirstResponse::Forward(bytes, server_ttl) => {
                if let Some(start) = self.tls_send_start {
                    state.note_tls_handshake_completed(self.target, start.elapsed().as_millis() as u64);
                }
                let has_payload = session.observe_first_response_payload(&bytes)?;
                // A client can send its next request as soon as this response is visible.
                session.finish_first_response(FirstResponseOutcome::Accepted { success_recorded: false })?;
                client.write_all(&bytes)?;
                if has_payload {
                    if RuntimeState::should_track_strategy_target(self.target)
                        && let Some(ttl) = server_ttl
                    {
                        note_server_ttl_for_route(
                            state,
                            self.target,
                            self.route.group_index,
                            self.host.as_deref(),
                            ttl,
                        )?;
                    }
                    record_stream_relay_success(
                        state,
                        self.target,
                        &self.route,
                        self.host.as_deref(),
                        Some(&self.original_request),
                        self.success_strategy_family,
                        self.primary_strategy_family,
                    )?;
                }
                session.finish_first_response(FirstResponseOutcome::Accepted { success_recorded: has_payload })
            }
            FirstResponse::NoData => {
                session.finish_first_response(FirstResponseOutcome::Accepted { success_recorded: false })
            }
            FirstResponse::Failure { failure, response_bytes } => {
                session.finish_first_response(FirstResponseOutcome::Rejected)?;
                note_block_signal_for_failure(state, self.host.as_deref(), &failure, None);
                emit_failure_classified(state, self.target, &failure, self.host.as_deref());
                if failure.action == RuntimeFailureAction::ResolverOverrideRecommended {
                    return Err(io::Error::new(io::ErrorKind::ConnectionReset, failure.evidence.summary));
                }
                if let Some(bytes) = response_bytes {
                    session.observe_retry_response_payload(&bytes)?;
                    client.write_all(&bytes)?;
                    return Ok(());
                }
                if failure.class == RuntimeFailureClass::SilentDrop {
                    return Ok(());
                }
                Err(io::Error::new(io::ErrorKind::ConnectionReset, failure.evidence.summary))
            }
            FirstResponse::Continuation(_) => Err(io::Error::other("client continuation has already been handed off")),
        }
    }
}
