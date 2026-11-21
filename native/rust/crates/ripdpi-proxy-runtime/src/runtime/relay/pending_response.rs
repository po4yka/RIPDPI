use std::io::{self, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use super::super::failure::{RuntimeFailureAction, RuntimeFailureClass};
use super::super::routing::emit_failure_classified;
use super::super::state::RuntimeState;
use super::first_exchange::{FirstResponse, FirstResponseReader, read_first_response};
use super::session::{FirstResponseOutcome, RelaySharedSession};

/// One owner retains the bounded response collector across duplex handoff.
/// This type has no reconnect operation or replay authority.
pub(super) struct PendingFirstResponse {
    pub(super) reader: FirstResponseReader,
    pub(super) target: SocketAddr,
    pub(super) host: Option<String>,
    pub(super) original_request: Vec<u8>,
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
        let mut reader = self.reader;
        reader.watch_response_owner(session.clone());
        match read_first_response(
            state,
            self.target,
            self.host.as_deref(),
            upstream,
            &self.original_request,
            None,
            reader,
        )? {
            FirstResponse::Forward(bytes, server_ttl) => {
                if let Some(start) = self.tls_send_start {
                    state.note_tls_handshake_completed(self.target, start.elapsed().as_millis() as u64);
                }
                let has_payload = session.observe_first_response_payload(&bytes)?;
                if has_payload {
                    let _ = session.accept_fake_ttl_response(server_ttl)?;
                }
                session.finish_evolution_response(state, has_payload)?;
                // A client can send its next request as soon as this response is visible.
                session.finish_first_response(FirstResponseOutcome::Accepted)?;
                client.write_all(&bytes)?;
                state.note_application_traffic(0, bytes.len() as u64);
                Ok(())
            }
            FirstResponse::Neutral(bytes) => {
                session.finish_first_response(FirstResponseOutcome::Neutral)?;
                session.finish_evolution_response(state, false)?;
                session.observe_retry_response_payload(&bytes)?;
                client.write_all(&bytes)?;
                state.note_application_traffic(0, bytes.len() as u64);
                Ok(())
            }
            FirstResponse::NoData => {
                session.finish_first_response(FirstResponseOutcome::Neutral)?;
                session.finish_evolution_response(state, false)
            }
            FirstResponse::Failure { failure, response_bytes } => {
                session.finish_evolution_response(state, false)?;
                session.finish_first_response(FirstResponseOutcome::Rejected)?;
                // read_first_response already noted this failure's block signal.
                emit_failure_classified(state, self.target, &failure, self.host.as_deref());
                if failure.action == RuntimeFailureAction::ResolverOverrideRecommended {
                    return Err(io::Error::new(io::ErrorKind::ConnectionReset, failure.evidence.summary));
                }
                if let Some(bytes) = response_bytes {
                    session.observe_retry_response_payload(&bytes)?;
                    client.write_all(&bytes)?;
                    state.note_application_traffic(0, bytes.len() as u64);
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

#[cfg(all(test, not(any(target_os = "linux", target_os = "android"))))]
mod tests;

#[cfg(test)]
mod neutral_tests;
