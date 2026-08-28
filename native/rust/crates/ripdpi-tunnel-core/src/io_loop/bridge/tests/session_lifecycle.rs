use std::io;

use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

use crate::{ActiveSessions, SessionEntry, TunDevice};

use super::super::pump_active_sessions;
use super::support::establish_tcp_connection;

#[tokio::test]
async fn u24_pump_removes_closed_session() {
    let mut device = TunDevice::new(1500);
    let (_iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);

    let cancel = CancellationToken::new();
    let (smoltcp_side, session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
    let entry = SessionEntry {
        smoltcp_side,
        cancel,
        handle: join_handle,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    // Drop session side to cause EOF
    drop(session_side);
    tokio::task::yield_now().await;

    // Pump should detect the closed duplex
    let mut dns_cache = None;
    pump_active_sessions(&mut socket_set, &mut sessions, &mut dns_cache).await;

    // The session should have upstream_closed set to true, or be removed entirely
    // depending on whether the TCP socket is still active
    if let Some(entry) = sessions.get_mut(handle) {
        assert!(entry.upstream_closed, "upstream_closed should be set when session side is dropped");
    }
    // If session was removed, that's also valid
}

#[tokio::test]
async fn u26_pump_upstream_closed_closes_tcp() {
    let mut device = TunDevice::new(1500);
    let (_iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);

    let cancel = CancellationToken::new();
    let (smoltcp_side, _session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
    let entry = SessionEntry {
        smoltcp_side,
        cancel,
        handle: join_handle,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: true, // Simulate upstream already closed
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    // Pump should call tcp.close() since upstream_closed and pending empty
    let mut dns_cache = None;
    pump_active_sessions(&mut socket_set, &mut sessions, &mut dns_cache).await;

    // Check that the TCP socket was closed (state should transition from Established)
    // The socket might be removed or in a closing state
    if socket_set.iter().any(|(h, _)| h == handle) {
        let tcp = socket_set.get::<TcpSocket>(handle);
        assert_ne!(
            tcp.state(),
            tcp::State::Established,
            "TCP should no longer be in Established state after upstream close"
        );
    }

    // Cleanup
    let handles: Vec<_> = sessions.iter_mut().map(|(h, _)| h).collect();
    for h in handles {
        if let Some(entry) = sessions.remove(h) {
            entry.cancel.cancel();
            entry.handle.abort();
        }
    }
}

#[tokio::test]
async fn u28_pump_removal_cancels_session_task_token() {
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
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    socket_set.get_mut::<TcpSocket>(handle).abort();

    let mut dns_cache = None;
    pump_active_sessions(&mut socket_set, &mut sessions, &mut dns_cache).await;

    assert!(sessions.is_empty(), "inactive TCP sockets should remove their session");
    assert!(child_cancel.is_cancelled(), "session removal must cancel the session task token");
}
