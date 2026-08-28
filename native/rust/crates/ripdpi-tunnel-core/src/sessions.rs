use std::collections::{HashMap, VecDeque};

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
    /// The synthetic IPv4 address (host-byte-order u32) pinned in the DNS cache
    /// for the duration of this session, if any. Unpinned when the session ends.
    pub pinned_synthetic_ip: Option<u32>,
    /// Exact generation-stamped attribution registration released on close.
    pub attribution_id: Option<ripdpi_flow_app_attribution::FlowRegistrationId>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SessionOrderEntry {
    handle: SocketHandle,
    sequence: u64,
}

/// Bounded session table with O(1) lookup, removal, and FIFO eviction.
pub struct ActiveSessions {
    /// Fast lookup by socket handle.
    entries: HashMap<SocketHandle, SessionEntry>,
    /// Current generation for each live handle. Queue tombstones from removed
    /// or reused handles are rejected without scanning either queue.
    sequences: HashMap<SocketHandle, u64>,
    /// Insertion order for FIFO capacity eviction.
    eviction_order: VecDeque<SessionOrderEntry>,
    /// Independently rotating order for budgeted bridge work.
    work_order: VecDeque<SessionOrderEntry>,
    /// Monotonic counter for insertion ordering.
    next_sequence: u64,
    /// Maximum number of concurrent sessions; 0 = unlimited.
    max: usize,
}

impl ActiveSessions {
    pub fn new(max: usize) -> Self {
        Self {
            entries: HashMap::new(),
            sequences: HashMap::new(),
            eviction_order: VecDeque::new(),
            work_order: VecDeque::new(),
            next_sequence: 0,
            max,
        }
    }

    /// Insert a new session.
    ///
    /// If `max > 0` and the table is full, the oldest entry is evicted:
    /// its `cancel` token is cancelled and its `smoltcp_side` is dropped
    /// (belt-and-suspenders EOF to the session task).
    ///
    /// Returns the evicted session's `SocketHandle` so the caller can remove
    /// it from the `SocketSet`, preventing socket handle leaks.
    pub fn insert(&mut self, handle: SocketHandle, entry: SessionEntry) -> Option<(SocketHandle, Option<u32>)> {
        debug_assert!(!self.entries.contains_key(&handle), "active socket handle inserted twice");
        let seq = self.next_sequence;
        self.next_sequence = self.next_sequence.wrapping_add(1);

        let evicted_handle = if self.max > 0 && self.entries.len() >= self.max {
            self.pop_oldest_live().and_then(|evicted| {
                self.sequences.remove(&evicted.handle);
                self.entries.remove(&evicted.handle).map(|oldest| {
                    oldest.cancel.cancel();
                    if let Some(registration_id) = oldest.attribution_id {
                        ripdpi_flow_app_attribution::evict_flow_if_current(registration_id);
                    }
                    drop(oldest.smoltcp_side);
                    oldest.handle.abort();
                    (evicted.handle, oldest.pinned_synthetic_ip)
                })
            })
        } else {
            None
        };

        self.entries.insert(handle, entry);
        self.sequences.insert(handle, seq);
        let order_entry = SessionOrderEntry { handle, sequence: seq };
        self.eviction_order.push_back(order_entry);
        self.work_order.push_back(order_entry);
        evicted_handle
    }

    /// Check whether the given socket handle already has an active session.
    pub fn contains(&self, handle: SocketHandle) -> bool {
        self.entries.contains_key(&handle)
    }

    /// Remove a session by socket handle, returning it if present.
    pub fn remove(&mut self, handle: SocketHandle) -> Option<SessionEntry> {
        let entry = self.entries.remove(&handle)?;
        self.sequences.remove(&handle);
        Some(entry)
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
        self.entries.get_mut(&handle)
    }

    /// Return at most `limit` live handles and rotate them behind the remaining
    /// work. Generation stamps discard tombstones when a socket handle is
    /// removed and later reused.
    pub fn next_work_batch(&mut self, limit: usize) -> Vec<SocketHandle> {
        let mut handles = Vec::with_capacity(limit.min(self.entries.len()));
        let mut remaining = self.work_order.len();
        while remaining > 0 && handles.len() < limit {
            remaining -= 1;
            let Some(entry) = self.work_order.pop_front() else {
                break;
            };
            if self.sequences.get(&entry.handle) == Some(&entry.sequence) {
                self.work_order.push_back(entry);
                handles.push(entry.handle);
            }
        }
        handles
    }

    /// Iterate over (SocketHandle, &mut SessionEntry).
    pub fn iter_mut(&mut self) -> impl Iterator<Item = (SocketHandle, &mut SessionEntry)> {
        self.entries.iter_mut().map(|(h, e)| (*h, e))
    }

    fn pop_oldest_live(&mut self) -> Option<SessionOrderEntry> {
        while let Some(entry) = self.eviction_order.pop_front() {
            if self.sequences.get(&entry.handle) == Some(&entry.sequence) {
                return Some(entry);
            }
        }
        None
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
            pinned_synthetic_ip: None,
            attribution_id: None,
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

        let (mut e1, cancel1) = make_entry();
        e1.pinned_synthetic_ip = Some(0xC612_0001);
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
        assert_eq!(evicted, Some((h1, Some(0xC612_0001))), "eviction must return the mapping lease to release");

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

    #[tokio::test]
    async fn work_batches_are_bounded_rotating_and_skip_removed_generations() {
        let mut socket_set = SocketSet::new(vec![]);
        let handles: Vec<_> = (0..3).map(|_| socket_set.add(make_tcp_socket())).collect();
        let mut sessions = ActiveSessions::new(3);
        for handle in &handles {
            let (entry, _) = make_entry();
            assert!(sessions.insert(*handle, entry).is_none());
        }

        assert_eq!(sessions.next_work_batch(2), handles[..2]);
        assert_eq!(sessions.next_work_batch(2), vec![handles[2], handles[0]]);
        sessions.remove(handles[1]).expect("remove middle session");

        let batch = sessions.next_work_batch(3);
        assert_eq!(batch.len(), 2);
        assert!(batch.contains(&handles[0]));
        assert!(batch.contains(&handles[2]));
        assert!(!batch.contains(&handles[1]));
    }
}
