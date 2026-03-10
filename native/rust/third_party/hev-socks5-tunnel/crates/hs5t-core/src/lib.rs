pub mod classify;
pub mod device;
pub mod io_loop;
pub mod sessions;
pub mod stats;
pub mod tunnel_api;

pub use classify::{classify_ip_packet, IpClass};
pub use device::TunDevice;
pub use io_loop::io_loop_task;
pub use sessions::{ActiveSessions, SessionEntry};
pub use stats::Stats;
pub use tunnel_api::run_tunnel;

#[cfg(test)]
mod tests {
    use std::time::Duration;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio_util::sync::CancellationToken;

    // ── U-06: Duplex bridge — data flows from smoltcp_side to session_side ─────

    /// U-06: Write bytes to smoltcp_side, read from session_side — data arrives intact.
    #[tokio::test]
    async fn u06_duplex_bridge_data_flows() {
        let (mut smoltcp_side, mut session_side) = tokio::io::duplex(4096);

        let data = b"hello from smoltcp side";

        smoltcp_side.write_all(data).await.unwrap();
        smoltcp_side.shutdown().await.unwrap();

        let mut received = Vec::new();
        session_side.read_to_end(&mut received).await.unwrap();

        assert_eq!(
            received, data,
            "data written to smoltcp_side must arrive at session_side intact"
        );
    }

    // ── U-07: Duplex bridge — session_side drop causes smoltcp_side EOF ────────

    /// U-07: Drop session_side → smoltcp_side try_read returns 0 bytes (EOF signal).
    #[tokio::test]
    async fn u07_session_side_drop_causes_smoltcp_side_eof() {
        let (mut smoltcp_side, session_side) = tokio::io::duplex(4096);

        // Drop session_side (simulates TcpSession task completing / exiting).
        drop(session_side);

        // smoltcp_side must see EOF (read returns 0 bytes).
        let mut buf = [0u8; 64];
        let n = smoltcp_side.read(&mut buf).await.unwrap_or(0);

        assert_eq!(
            n, 0,
            "drop of session_side must produce EOF (0 bytes) on smoltcp_side"
        );
    }

    // ── U-09: Cancellation — parent cancel propagates to child token ───────────

    /// U-09: Parent CancellationToken cancelled → child token observes cancellation
    ///       within 100 ms.
    #[tokio::test]
    async fn u09_parent_cancel_propagates_to_child() {
        let parent = CancellationToken::new();
        let child = parent.child_token();

        assert!(
            !child.is_cancelled(),
            "child must not be cancelled before parent cancel"
        );

        // Spawn a task that waits for the child token.
        let child_clone = child.clone();
        let join = tokio::spawn(async move {
            child_clone.cancelled().await;
        });

        // Cancel the parent.
        parent.cancel();

        // Task must complete within 100 ms.
        let result = tokio::time::timeout(Duration::from_millis(100), join).await;

        assert!(
            result.is_ok(),
            "spawned task must observe child cancellation within 100 ms"
        );
        assert!(
            child.is_cancelled(),
            "child token must be marked as cancelled"
        );
    }

    // ── U-09b: child cancel does not affect parent ─────────────────────────────

    /// Cancelling a child token must not cancel the parent.
    #[tokio::test]
    async fn u09b_child_cancel_does_not_affect_parent() {
        let parent = CancellationToken::new();
        let child = parent.child_token();

        child.cancel();

        assert!(child.is_cancelled());
        assert!(
            !parent.is_cancelled(),
            "cancelling child must not cancel parent"
        );
    }

    // ── Verify Stats is accessible from lib ───────────────────────────────────

    /// Smoke test: Stats can be constructed and snapshotted from lib root.
    #[test]
    fn stats_accessible_from_lib() {
        let s = crate::Stats::new();
        assert_eq!(s.snapshot(), (0, 0, 0, 0));
    }
}
