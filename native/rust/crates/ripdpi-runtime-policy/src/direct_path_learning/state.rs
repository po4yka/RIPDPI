#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum TerminalState {
    QuicSuccess,
    AllIpsFailed,
    NoTcpFallbackDetected,
}

#[derive(Clone, Debug, Default)]
pub(super) struct TupleState {
    pub(super) udp_failed: bool,
    pub(super) tls_post_client_hello_failed: bool,
    pub(super) pending_udp_suppressed_at_ms: Option<u64>,
    pub(super) terminal_state: Option<TerminalState>,
    pub(super) owned_stack_required_emitted: bool,
    /// `DirectPathLearningState` write sequence of the last update, for eviction order.
    pub(super) last_write: u64,
}

pub(super) fn clear_negative_state(entry: &mut TupleState) {
    entry.udp_failed = false;
    entry.tls_post_client_hello_failed = false;
    entry.pending_udp_suppressed_at_ms = None;
}
