use std::io;
use std::net::{Shutdown, TcpStream};

use crate::runtime::state::{ClientSlotGuard, RuntimeState};

pub(crate) struct ClientJob {
    pub(crate) client: TcpStream,
    pub(crate) state: RuntimeState,
    pub(crate) slot: ClientSlotGuard,
}

pub(crate) fn process_client_job(job: ClientJob) {
    let ClientJob { client, state, slot } = job;
    let _slot = slot;
    let _active_client = match state.register_active_tcp_socket(&client) {
        Ok(guard) => guard,
        Err(err) => {
            state.note_client_error(&err);
            state.note_client_finished();
            let _ = client.shutdown(Shutdown::Both);
            return;
        }
    };
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn closed_error_classifier_covers_expected_disconnect_kinds() {
        for kind in [
            io::ErrorKind::ConnectionReset,
            io::ErrorKind::ConnectionAborted,
            io::ErrorKind::BrokenPipe,
            io::ErrorKind::UnexpectedEof,
            io::ErrorKind::NotConnected,
        ] {
            let err = io::Error::new(kind, "closed");
            assert!(is_connection_closed_error(&err), "{kind:?} should be treated as closed");
            assert!(!is_connection_timeout_error(&err), "{kind:?} should not be treated as timeout");
        }
    }

    #[test]
    fn timeout_error_classifier_is_narrow() {
        let timeout = io::Error::new(io::ErrorKind::TimedOut, "timeout");
        let refused = io::Error::new(io::ErrorKind::ConnectionRefused, "refused");

        assert!(is_connection_timeout_error(&timeout));
        assert!(!is_connection_closed_error(&timeout));
        assert!(!is_connection_timeout_error(&refused));
        assert!(!is_connection_closed_error(&refused));
    }
}
