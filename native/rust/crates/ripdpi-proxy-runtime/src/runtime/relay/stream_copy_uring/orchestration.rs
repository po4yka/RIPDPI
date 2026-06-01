use std::io;
use std::net::TcpStream;
use std::os::fd::AsRawFd;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

use ripdpi_io_uring::IoUringDriver;

use super::super::super::state::RuntimeState;
use super::super::session::RelaySession;
use super::super::stream_copy::{CONNECTION_FREEZE_MARKER, RelayStreamSettings};
use super::cleanup::{clone_relay_sockets, configure_relay_sockets, detach_drop_sack_if_needed, shutdown_relay_pair};
use super::inbound_zc::copy_inbound_zc;
use super::outbound_desync::copy_outbound_half;

/// io_uring-accelerated relay. Replaces `relay_streams` when ZC send is
/// available. The inbound path (upstream -> client) uses `IORING_OP_SEND_ZC`
/// via registered buffers. The outbound path uses the standard desync
/// pipeline since desync strategies require fine-grained socket manipulation.
pub(crate) fn relay_streams_uring(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    group_index: usize,
    settings: RelayStreamSettings,
    session_seed: RelaySession,
    remembered_host_seed: Option<String>,
    uring: &Arc<IoUringDriver>,
) -> io::Result<RelaySession> {
    configure_relay_sockets(&client, &upstream);

    let sockets = clone_relay_sockets(&client, &upstream)?;
    let session_state = session_seed.into_shared();
    let outbound_session = session_state.clone();
    let inbound_session = session_state.clone();
    let outbound_state = state.clone();
    let peer_done = Arc::new(AtomicBool::new(false));
    let freeze_detected = Arc::new(AtomicBool::new(false));

    let freeze_flag = freeze_detected.clone();
    let timeouts = settings.group.timeouts();
    let down_done = peer_done.clone();
    let uring_clone = Arc::clone(uring);
    let client_fd = sockets.client_writer.as_raw_fd();
    let down = thread::Builder::new()
        .name("ripdpi-dn-zc".into())
        .spawn(move || {
            copy_inbound_zc(
                sockets.upstream_reader,
                sockets.client_writer,
                client_fd,
                inbound_session,
                down_done,
                timeouts,
                freeze_flag,
                &uring_clone,
            )
        })
        .map_err(|err| io::Error::other(format!("failed to spawn inbound relay thread: {err}")))?;

    let up_result = copy_outbound_half(
        sockets.client_reader,
        sockets.upstream_writer,
        outbound_state,
        group_index,
        outbound_session,
        peer_done.clone(),
        remembered_host_seed,
    );
    if up_result.is_err() {
        peer_done.store(true, Ordering::Release);
        shutdown_relay_pair(&upstream, &client);
    }
    let down_result = down.join().map_err(|_| io::Error::other("downstream thread panicked"))?;

    shutdown_relay_pair(&upstream, &client);
    detach_drop_sack_if_needed(settings.group.drop_sack(), &upstream);

    up_result?;
    down_result?;

    if freeze_detected.load(Ordering::Acquire) {
        return Err(io::Error::new(io::ErrorKind::TimedOut, CONNECTION_FREEZE_MARKER));
    }

    session_state.snapshot()
}
