use std::io;
use std::net::TcpStream;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

use ripdpi_io_uring::IoUringDriver;

use super::super::super::state::RuntimeState;
use super::super::session::RelaySession;
use super::super::stream_copy::{CONNECTION_FREEZE_MARKER, RelayStreamSettings, shutdown_relay_pair};
use super::cleanup::{clone_relay_sockets, configure_relay_sockets, detach_drop_sack_if_needed};
use super::inbound_zc::copy_inbound_zc;
use super::outbound_desync::copy_outbound_half;

/// io_uring-accelerated relay. Replaces `relay_streams` when registered fixed
/// buffers are available. The inbound path (upstream -> client) uses
/// `IORING_OP_WRITE_FIXED`; the outbound path uses the standard desync pipeline
/// since desync strategies require fine-grained socket manipulation.
pub(crate) fn relay_streams_uring(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    group_index: usize,
    settings: RelayStreamSettings,
    mut session_seed: RelaySession,
    remembered_host_seed: Option<String>,
    uring: &Arc<IoUringDriver>,
) -> io::Result<RelaySession> {
    configure_relay_sockets(&client, &upstream);

    let mut sockets = clone_relay_sockets(&client, &upstream)?;
    let pending_response = session_seed.take_pending_response();
    let session_state = session_seed.into_shared();
    let outbound_session = session_state.clone();
    let inbound_session = session_state.clone();
    let outbound_state = state.clone();
    let inbound_state = state.clone();
    let peer_done = Arc::new(AtomicBool::new(false));
    let freeze_detected = Arc::new(AtomicBool::new(false));

    let freeze_flag = freeze_detected.clone();
    let timeouts = settings.group.timeouts();
    let down_done = peer_done.clone();
    let uring_clone = Arc::clone(uring);
    let down = thread::Builder::new()
        .name("ripdpi-dn-zc".into())
        .spawn(move || {
            let result = (|| {
                if let Some(pending) = pending_response {
                    pending.finish(
                        &inbound_state,
                        &mut sockets.upstream_reader,
                        &mut sockets.client_writer,
                        &inbound_session,
                    )?;
                }
                copy_inbound_zc(
                    &mut sockets.upstream_reader,
                    &mut sockets.client_writer,
                    inbound_session,
                    down_done.clone(),
                    timeouts,
                    freeze_flag,
                    &uring_clone,
                )
            })();
            if result.is_err() {
                down_done.store(true, Ordering::Release);
                shutdown_relay_pair(&sockets.upstream_reader, &sockets.client_writer);
            }
            result
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
