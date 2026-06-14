use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio_util::sync::CancellationToken;

use crate::session::{Auth, UdpSession};

mod spawn;

use super::association_state::{UdpAssociation, now_millis};
use super::event_handling::UdpEvent;
use spawn::spawn_udp_worker;

/// Build a UDP association: open the SOCKS5 UDP session and spawn its pump
/// worker.
///
/// # Cancel safety
///
/// NOT cancel-safe. It awaits [`UdpSession::connect`] (itself a non-cancel-safe
/// multi-step SOCKS5 setup) and then spawns the worker. Dropping this future
/// before it returns leaves the half-built session to its `Drop` (fds close)
/// and never registers an association, so no caller-visible association is
/// created from a torn setup. Its caller in `io_loop` awaits it to completion on
/// the association-setup path and does not place it under a `select!`/`timeout`;
/// the long-lived cancellation point is the spawned worker's own loop, which
/// honors `cancel`.
#[allow(clippy::too_many_arguments)]
pub(super) async fn create_udp_association(
    proxy_addr: SocketAddr,
    auth: Auth,
    src: SocketAddr,
    dest: SocketAddr,
    association_id: u64,
    idle_timeout: Duration,
    protect_path: Option<&str>,
    cancel: CancellationToken,
    udp_tx: tokio::sync::mpsc::Sender<UdpEvent>,
) -> io::Result<UdpAssociation> {
    let session = UdpSession::connect(proxy_addr, auth, protect_path).await?.with_recv_timeout(idle_timeout);
    let last_activity = Arc::new(std::sync::atomic::AtomicU64::new(now_millis()));
    let worker = spawn_udp_worker(
        session.clone(),
        Arc::clone(&last_activity),
        cancel.clone(),
        udp_tx.clone(),
        src,
        association_id,
        idle_timeout,
    );

    Ok(UdpAssociation { id: association_id, session, cancel, last_activity, worker, dest })
}
