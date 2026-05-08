use std::io;
use std::net::TcpStream;

use crate::runtime::state::{ClientSlotGuard, RuntimeState};

pub(crate) struct ClientJob {
    pub(crate) client: TcpStream,
    pub(crate) state: RuntimeState,
    pub(crate) slot: ClientSlotGuard,
}

pub(crate) fn process_client_job(job: ClientJob) {
    let ClientJob { client, state, slot } = job;
    let _slot = slot;
    let result = super::super::handshake::handle_client(client, &state);
    if let Err(err) = &result {
        let shutting_down = state.shutdown_requested();
        if shutting_down && is_connection_closed_error(err) {
            tracing::trace!("ripdpi client error during shutdown (expected): {err}");
        } else if is_connection_closed_error(err) {
            if state.has_embedded_control() {
                tracing::trace!("ripdpi client disconnected: {err}");
            } else {
                tracing::debug!("ripdpi client disconnected: {err}");
            }
        } else if is_connection_timeout_error(err) {
            tracing::warn!("ripdpi client timeout: {err}");
        } else {
            tracing::error!("ripdpi client error: {err}");
        }
        state.note_client_error(err);
    }
    state.note_client_finished();
}

/// Returns `true` for I/O errors that are expected when the proxy shuts down
/// while clients still have active connections (e.g. ECONNRESET, EPIPE).
fn is_connection_closed_error(err: &io::Error) -> bool {
    matches!(
        err.kind(),
        io::ErrorKind::ConnectionReset
            | io::ErrorKind::ConnectionAborted
            | io::ErrorKind::BrokenPipe
            | io::ErrorKind::UnexpectedEof
            | io::ErrorKind::NotConnected
    )
}

/// Returns `true` for I/O errors that indicate a connection timed out.
fn is_connection_timeout_error(err: &io::Error) -> bool {
    matches!(err.kind(), io::ErrorKind::TimedOut)
}
