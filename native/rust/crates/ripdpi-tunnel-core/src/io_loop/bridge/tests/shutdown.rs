use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use smoltcp::iface::SocketSet;
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use tokio::task::JoinHandle;
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

use crate::{ActiveSessions, SessionEntry, TunDevice};

use super::super::shutdown_active_sessions;
use super::support::establish_tcp_connection;

struct TaskAlive(Arc<AtomicBool>);

impl Drop for TaskAlive {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

fn insert_stalled_session(
    sessions: &mut ActiveSessions,
    socket_set: &mut SocketSet<'static>,
    _target_addr: &str,
) -> (CancellationToken, Arc<AtomicBool>, tokio::sync::oneshot::Receiver<()>) {
    let socket = TcpSocket::new(tcp::SocketBuffer::new(vec![0; 256]), tcp::SocketBuffer::new(vec![0; 256]));
    let socket_handle = socket_set.add(socket);
    let cancel = CancellationToken::new();
    let alive = Arc::new(AtomicBool::new(true));
    let task_alive = TaskAlive(alive.clone());
    let (started_tx, started_rx) = tokio::sync::oneshot::channel();
    let task = tokio::spawn(async move {
        let _task_alive = task_alive;
        let _ = started_tx.send(());
        std::future::pending::<io::Result<()>>().await
    });
    let (smoltcp_side, _session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let entry = SessionEntry {
        smoltcp_side,
        cancel: cancel.clone(),
        handle: task,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    assert!(sessions.insert(socket_handle, entry).is_none());
    (cancel, alive, started_rx)
}

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
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    assert!(!child_cancel.is_cancelled());

    let mut dns_cache = None;
    shutdown_active_sessions(&mut sessions, &mut socket_set, &mut dns_cache).await;

    assert!(child_cancel.is_cancelled(), "session cancel token must be cancelled after shutdown");
    assert!(sessions.is_empty(), "all sessions should be removed after shutdown");
}

#[tokio::test(start_paused = true)]
async fn shutdown_uses_one_grace_period_and_aborts_stalled_session_tasks() {
    let mut socket_set = SocketSet::new(vec![]);
    let mut sessions = ActiveSessions::new(8);
    let (cancel1, alive1, started1) = insert_stalled_session(&mut sessions, &mut socket_set, "203.0.113.31:443");
    let (cancel2, alive2, started2) = insert_stalled_session(&mut sessions, &mut socket_set, "203.0.113.32:443");
    started1.await.expect("first stalled task should start");
    started2.await.expect("second stalled task should start");

    let started_at = Instant::now();
    let mut dns_cache = None;
    shutdown_active_sessions(&mut sessions, &mut socket_set, &mut dns_cache).await;

    assert_eq!(started_at.elapsed(), Duration::from_secs(5), "all tasks must share one shutdown grace period");
    assert!(cancel1.is_cancelled() && cancel2.is_cancelled(), "all session tokens must be cancelled together");
    assert!(!alive1.load(Ordering::Acquire), "first stalled task must be aborted and joined");
    assert!(!alive2.load(Ordering::Acquire), "second stalled task must be aborted and joined");
    assert!(sessions.is_empty(), "all sessions should be removed after shutdown");
}
