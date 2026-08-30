use std::collections::HashMap;

use super::scoring::DirectPathBlockClass;

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
    /// Per-arm attempt counters. Keys are static arm labels matching
    /// `RankedArm::label`; values are the number of attempts recorded for the
    /// current block-class window. Cleared alongside the rest of the negative
    /// evidence so a positive signal resets the budget.
    pub(super) arm_attempts: HashMap<&'static str, u32>,
}

/// Derive the block class from a recorded `TupleState`.
pub(super) fn block_class_from_state(entry: &TupleState) -> DirectPathBlockClass {
    match entry.terminal_state {
        Some(TerminalState::QuicSuccess) => DirectPathBlockClass::QuicConfirmed,
        Some(TerminalState::AllIpsFailed) => DirectPathBlockClass::AllIpsFailed,
        Some(TerminalState::NoTcpFallbackDetected) => DirectPathBlockClass::NoTcpFallback,
        None => match (entry.udp_failed, entry.tls_post_client_hello_failed) {
            (true, true) => DirectPathBlockClass::QuicBlockedAndTlsPostClientHello,
            (true, false) => DirectPathBlockClass::QuicBlocked,
            (false, true) => DirectPathBlockClass::TlsPostClientHello,
            (false, false) => DirectPathBlockClass::Clean,
        },
    }
}

pub(super) fn clear_negative_state(entry: &mut TupleState) {
    entry.udp_failed = false;
    entry.tls_post_client_hello_failed = false;
    entry.pending_udp_suppressed_at_ms = None;
    // Reset the per-arm attempt counters: a positive signal restarts the
    // budget window for the new block class.
    entry.arm_attempts.clear();
}
