use smoltcp::iface::SocketHandle;
use std::io;
use tokio::io::DuplexStream;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

/// State for a single active TCP or UDP session.
pub struct SessionEntry {
    /// The smoltcp-side of the duplex bridge (Decision A).
    pub smoltcp_side: DuplexStream,
    /// Child cancellation token for this session.
    pub cancel: CancellationToken,
    /// Handle to the spawned session task.
    pub handle: JoinHandle<io::Result<()>>,
    /// Bytes read from the smoltcp socket that have not yet been accepted by
    /// the session-side duplex stream.
    pub pending_to_session: Vec<u8>,
    /// Bytes read from the upstream session that have not yet been accepted by
    /// smoltcp's transmit buffer.
    pub pending_to_smoltcp: Vec<u8>,
    /// The upstream session has closed its write side; the smoltcp socket
    /// should half-close only after `pending_to_smoltcp` has been flushed.
    pub upstream_closed: bool,
}

/// Ordered map of active sessions, supporting oldest-first eviction.
///
/// Insertion order is preserved so that `max > 0` eviction always removes the
/// *oldest* entry (the one inserted first), matching the C reference
/// `hev_list_add_tail` / first-in-first-out eviction.
pub struct ActiveSessions {
    /// Ordered list of (socket_handle, session_entry) pairs.
    /// Entry at index 0 is the oldest.
    entries: Vec<(SocketHandle, SessionEntry)>,
    /// Maximum number of concurrent sessions; 0 = unlimited.
    max: usize,
}

impl ActiveSessions {
    pub fn new(max: usize) -> Self {
        Self { entries: Vec::new(), max }
    }

    /// Insert a new session.
    ///
    /// If `max > 0` and the table is full, the oldest entry is evicted:
    /// its `cancel` token is cancelled and its `smoltcp_side` is dropped
    /// (belt-and-suspenders EOF to the session task).
    ///
    /// Returns the evicted session's `SocketHandle` so the caller can remove
    /// it from the `SocketSet`, preventing socket handle leaks.
    pub fn insert(&mut self, handle: SocketHandle, entry: SessionEntry) -> Option<SocketHandle> {
        let evicted_handle = if self.max > 0 && self.entries.len() >= self.max && !self.entries.is_empty() {
            // Remove oldest (first element).
            let (evicted_h, oldest) = self.entries.remove(0);
            oldest.cancel.cancel();
            // Dropping oldest.smoltcp_side causes session_side to see EOF.
            drop(oldest.smoltcp_side);
            // Don't await the handle — fire and forget, it will terminate via cancel.
            oldest.handle.abort();
            Some(evicted_h)
        } else {
            None
        };
        self.entries.push((handle, entry));
        evicted_handle
    }

    /// Check whether the given socket handle already has an active session.
    pub fn contains(&self, handle: SocketHandle) -> bool {
        self.entries.iter().any(|(h, _)| *h == handle)
    }

    /// Remove a session by socket handle, returning it if present.
    pub fn remove(&mut self, handle: SocketHandle) -> Option<SessionEntry> {
        if let Some(pos) = self.entries.iter().position(|(h, _)| *h == handle) {
            Some(self.entries.remove(pos).1)
        } else {
            None
        }
    }

    /// Number of active sessions.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Returns true if there are no active sessions.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Get a mutable reference to a session entry by socket handle.
    pub fn get_mut(&mut self, handle: SocketHandle) -> Option<&mut SessionEntry> {
        self.entries.iter_mut().find(|(h, _)| *h == handle).map(|(_, e)| e)
    }

    /// Iterate over (SocketHandle, &mut SessionEntry) in insertion order.
    pub fn iter_mut(&mut self) -> impl Iterator<Item = (SocketHandle, &mut SessionEntry)> {
        self.entries.iter_mut().map(|(h, e)| (*h, e))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use smoltcp::iface::SocketSet;
    use smoltcp::socket::tcp::{self, Socket as TcpSocket};

    /// Create a minimal TcpSocket with small buffers for testing.
    fn make_tcp_socket() -> TcpSocket<'static> {
        TcpSocket::new(tcp::SocketBuffer::new(vec![0u8; 256]), tcp::SocketBuffer::new(vec![0u8; 256]))
    }

    /// Build a dummy SessionEntry with a fresh duplex pair and cancel token.
    fn make_entry() -> (SessionEntry, CancellationToken) {
        let parent = CancellationToken::new();
        let child = parent.child_token();
        let (smoltcp_side, _session_side) = tokio::io::duplex(256);
        let handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
        let entry = SessionEntry {
            smoltcp_side,
            cancel: child.clone(),
            handle,
            pending_to_session: Vec::new(),
            pending_to_smoltcp: Vec::new(),
            upstream_closed: false,
        };
        (entry, child)
    }

    /// U-04: ActiveSessions::insert with max=3 — 4th insert evicts oldest;
    ///        the evicted session's CancellationToken is cancelled.
    #[tokio::test]
    async fn u04_insert_with_max_evicts_oldest() {
        let mut socket_set = SocketSet::new(vec![]);
        let h1 = socket_set.add(make_tcp_socket());
        let h2 = socket_set.add(make_tcp_socket());
        let h3 = socket_set.add(make_tcp_socket());
        let h4 = socket_set.add(make_tcp_socket());

        let mut sessions = ActiveSessions::new(3);

        let (e1, cancel1) = make_entry();
        let (e2, _cancel2) = make_entry();
        let (e3, _cancel3) = make_entry();
        let (e4, _cancel4) = make_entry();

        assert!(sessions.insert(h1, e1).is_none());
        assert!(sessions.insert(h2, e2).is_none());
        assert!(sessions.insert(h3, e3).is_none());

        assert_eq!(sessions.len(), 3);
        assert!(!cancel1.is_cancelled(), "cancel1 must not be cancelled before eviction");

        // 4th insert evicts h1 (oldest).
        let evicted = sessions.insert(h4, e4);
        assert_eq!(evicted, Some(h1), "evicted handle must be h1");

        assert_eq!(sessions.len(), 3, "session count must remain at max=3 after eviction");
        assert!(cancel1.is_cancelled(), "evicted session's cancel token must be cancelled");
        assert!(!sessions.contains(h1), "h1 must not be present after eviction");
        assert!(sessions.contains(h2), "h2 must still be present");
        assert!(sessions.contains(h3), "h3 must still be present");
        assert!(sessions.contains(h4), "h4 must be present as the new session");
    }

    /// U-05: ActiveSessions::insert with max=0 — no eviction; 100 inserts all present.
    #[tokio::test]
    async fn u05_insert_with_max_zero_no_eviction() {
        let mut socket_set = SocketSet::new(vec![]);
        let mut sessions = ActiveSessions::new(0); // unlimited

        for _ in 0..100 {
            let h = socket_set.add(make_tcp_socket());
            let (entry, _) = make_entry();
            assert!(sessions.insert(h, entry).is_none());
        }

        assert_eq!(sessions.len(), 100, "unlimited sessions must hold 100 entries");
    }
}
