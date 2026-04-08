use std::io;
use std::net::{SocketAddr, TcpStream};

use crate::runtime_policy::ConnectionRoute;

use super::super::routing::{emit_failure_classified, note_block_signal_for_failure};
use super::super::state::RuntimeState;
use super::failure_retry::record_stream_relay_success;
use super::stream_copy::{relay_streams, CONNECTION_FREEZE_MARKER};
#[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
use super::stream_copy_uring;

#[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
#[inline(never)]
pub(super) fn relay_with_uring_if_available(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    route: ConnectionRoute,
    session_state: ripdpi_session::SessionState,
    success_host: Option<String>,
) -> io::Result<ripdpi_session::SessionState> {
    let uring_driver = ripdpi_io_uring::io_uring_capabilities().send_zc.then(|| state.io_uring.as_ref()).flatten();
    if let Some(driver) = uring_driver {
        return stream_copy_uring::relay_streams_uring(
            client,
            upstream,
            state,
            route.group_index,
            session_state,
            success_host,
            driver,
        );
    }
    relay_streams(client, upstream, state, route.group_index, session_state, success_host)
}

#[cfg(not(all(feature = "io-uring", any(target_os = "linux", target_os = "android"))))]
#[inline(never)]
pub(super) fn relay_with_uring_if_available(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    route: ConnectionRoute,
    session_state: ripdpi_session::SessionState,
    success_host: Option<String>,
) -> io::Result<ripdpi_session::SessionState> {
    relay_streams(client, upstream, state, route.group_index, session_state, success_host)
}

#[inline(never)]
pub(super) fn record_relay_result(
    relay_result: &io::Result<ripdpi_session::SessionState>,
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    success_recorded: bool,
    success_host: Option<&str>,
    success_payload: Option<&[u8]>,
) -> io::Result<()> {
    if let Ok(final_state) = relay_result {
        if !success_recorded && final_state.recv_count > 0 {
            record_stream_relay_success(state, target, route, success_host, success_payload)?;
        }
    }
    if let Err(err) = relay_result {
        if err.to_string().contains(CONNECTION_FREEZE_MARKER) {
            let t = &state.config.timeouts;
            let failure =
                ripdpi_failure_classifier::classify_connection_freeze(0, t.freeze_max_stalls, t.freeze_window_ms);
            note_block_signal_for_failure(state, success_host, &failure, None);
            emit_failure_classified(state, target, &failure, success_host);
        }
    }
    Ok(())
}
