use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::task::Waker;
use std::time::Duration;

use tokio::io::{DuplexStream, duplex};

use crate::Stats;
use crate::io_loop::DUPLEX_BUF;
use crate::session::{Auth, TargetAddr, TcpSession};

use super::{SessionDuplex, SessionLifecycle};

pub(in crate::io_loop::tcp_accept) fn create_session_duplex(
    proxy_sockaddr: SocketAddr,
    auth: &Auth,
    target: TargetAddr,
    protect_path: Option<&str>,
    connect_timeout: Duration,
    read_write_timeout: Duration,
    lifecycle: SessionLifecycle,
    stats: &Arc<Stats>,
) -> SessionDuplex {
    let (smoltcp_side, session_side) = duplex(DUPLEX_BUF);
    let SessionLifecycle { cancel, bridge_waker } = lifecycle;
    let session_inst = TcpSession::with_stats(
        proxy_sockaddr,
        auth.clone(),
        target,
        protect_path.map(str::to_owned),
        connect_timeout,
        read_write_timeout,
        Arc::clone(stats),
    );
    let session_cancel = cancel.clone();
    let upstream_failed = Arc::new(AtomicBool::new(false));
    let task_failed = Arc::clone(&upstream_failed);
    let handle = tokio::spawn(async move {
        let mut end = SessionEnd { stream: session_side, upstream_failed: task_failed, bridge_waker, failed: true };
        let result = session_inst.run(&mut end.stream, session_cancel).await;
        end.failed = result.is_err();
        drop(end);
        result
    });
    SessionDuplex { smoltcp_side, cancel, handle, upstream_failed }
}

/// The session task's duplex end. Dropping it publishes the session outcome first, so a panic that
/// unwinds through `run()` reads as a failure (a RST to the app) and not as a complete response.
struct SessionEnd {
    stream: DuplexStream,
    upstream_failed: Arc<AtomicBool>,
    bridge_waker: Waker,
    failed: bool,
}

impl Drop for SessionEnd {
    fn drop(&mut self) {
        // Release pairs with the pump's Acquire load: publish the outcome before the notification
        // and before `stream` drops after this body.
        self.upstream_failed.store(self.failed, Ordering::Release);
        // Directional EOF can clear the duplex read waker before the relay task ends. Publish an
        // explicit completion wake so a long native TCP timer cannot delay terminal cleanup.
        self.bridge_waker.wake_by_ref();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncReadExt;

    /// # Cancel safety:
    /// cancel-safe: task.await borrows no shared state; read and the timed Notify wait are
    /// cancel-safe. The isolated runtime owns the task and both duplex ends.
    #[tokio::test(flavor = "current_thread")]
    async fn a_panicking_session_reports_failure_before_its_duplex_end_closes() {
        let (mut smoltcp_side, session_side) = duplex(64);
        let upstream_failed = Arc::new(AtomicBool::new(false));
        let sessions = crate::ActiveSessions::new(1);
        let end = SessionEnd {
            stream: session_side,
            upstream_failed: Arc::clone(&upstream_failed),
            bridge_waker: sessions.bridge_waker().clone(),
            failed: true,
        };

        let task = tokio::spawn(async move {
            let _end = end;
            panic!("session bug");
        });

        assert!(task.await.is_err_and(|error| error.is_panic()));
        let mut byte = [0u8; 1];
        assert_eq!(smoltcp_side.read(&mut byte).await.expect("EOF after the panic"), 0);
        assert!(upstream_failed.load(Ordering::Acquire), "a panicked session must not close with FIN");
        tokio::time::timeout(Duration::from_secs(1), sessions.bridge_ready())
            .await
            .expect("terminal panic wakes the bridge even without a duplex reader");
    }
}
