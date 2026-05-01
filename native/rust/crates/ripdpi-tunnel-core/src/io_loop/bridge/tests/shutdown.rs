use std::io;

use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

use crate::{ActiveSessions, SessionEntry, TunDevice};

use super::super::shutdown_active_sessions;
use super::support::establish_tcp_connection;

#[tokio::test]
async fn u27_shutdown_cancels_all() {
    let mut device = TunDevice::new(1500);
    let (_iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);

    let cancel = CancellationToken::new();
    let child_cancel = cancel.child_token();
    let (smoltcp_side, _session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let session_cancel = child_cancel.clone();
    let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async move {
        session_cancel.cancelled().await;
        Ok(())
    });
    let entry = SessionEntry {
        smoltcp_side,
        cancel: child_cancel.clone(),
        handle: join_handle,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        pinned_synthetic_ip: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    assert!(!child_cancel.is_cancelled());

    let mut dns_cache = None;
    shutdown_active_sessions(&mut sessions, &mut socket_set, &mut dns_cache).await;

    assert!(child_cancel.is_cancelled(), "session cancel token must be cancelled after shutdown");
    assert!(sessions.is_empty(), "all sessions should be removed after shutdown");
}
