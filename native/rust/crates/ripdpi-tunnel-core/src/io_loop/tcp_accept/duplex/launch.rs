use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use tokio::io::{DuplexStream, duplex};
use tokio_util::sync::CancellationToken;

use crate::Stats;
use crate::io_loop::DUPLEX_BUF;
use crate::session::{Auth, TargetAddr, TcpSession};

use super::SessionDuplex;

pub(in crate::io_loop::tcp_accept) fn create_session_duplex(
    proxy_sockaddr: SocketAddr,
    auth: &Auth,
    target: TargetAddr,
    protect_path: Option<&str>,
    connect_timeout: Duration,
    read_write_timeout: Duration,
    parent_cancel: &CancellationToken,
    stats: &Arc<Stats>,
) -> SessionDuplex {
    let (smoltcp_side, session_side) = duplex(DUPLEX_BUF);
    let cancel = parent_cancel.child_token();
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
        let mut end = SessionEnd { stream: session_side, upstream_failed: task_failed, failed: true };
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
    failed: bool,
}

impl Drop for SessionEnd {
    fn drop(&mut self) {
        // Release pairs with the pump's Acquire load: the flag is published before `stream` drops
        // after this body, and the pump reads it only after it sees that drop as EOF.
        self.upstream_failed.store(self.failed, Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncReadExt;

    #[tokio::test]
    async fn a_panicking_session_reports_failure_before_its_duplex_end_closes() {
        let (mut smoltcp_side, session_side) = duplex(64);
        let upstream_failed = Arc::new(AtomicBool::new(false));
        let end = SessionEnd { stream: session_side, upstream_failed: Arc::clone(&upstream_failed), failed: true };

        let task = tokio::spawn(async move {
            let _end = end;
            panic!("session bug");
        });

        assert!(task.await.is_err_and(|error| error.is_panic()));
        let mut byte = [0u8; 1];
        assert_eq!(smoltcp_side.read(&mut byte).await.expect("EOF after the panic"), 0);
        assert!(upstream_failed.load(Ordering::Acquire), "a panicked session must not close with FIN");
    }
}
