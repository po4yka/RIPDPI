// Ranked-arm dispatcher for adaptive direct-path learning.
#![allow(dead_code)]

use std::collections::HashMap;
use std::net::SocketAddr;

use crate::runtime_policy::TransportProtocol;

mod key;
mod observer;
mod scoring;
mod state;

pub use key::direct_path_ip_set_digest;
pub use observer::DirectPathLearningObserver;
pub use scoring::{DirectPathBlockClass, RankedArm};

use key::{TupleKey, tuple_key_for_targets};
use observer::emit_learning_signal;
#[cfg(test)]
use scoring::DEFAULT_ATTEMPT_BUDGET;
use scoring::{apply_attempt_budgets, ranked_arms_for_class};
use state::{TerminalState, TupleState, block_class_from_state, clear_negative_state};

const NO_TCP_FALLBACK_WINDOW_MS: u64 = 3_000;
/// Bounds per-session tuple state; CDN IP rotation and hostless flows create new keys indefinitely.
const MAX_DIRECT_PATH_TUPLES: usize = 4096;

#[derive(Default)]
pub struct DirectPathLearningState {
    tuples: HashMap<TupleKey, TupleState>,
    /// Lower bound of every pending UDP-suppression start, so the per-iteration UDP loop check does
    /// not scan every tuple. A cleared pending entry leaves it stale-low, which costs one extra scan.
    earliest_pending_udp_suppressed_ms: Option<u64>,
    /// Write sequence for least-recently-written eviction at the tuple cap.
    tuple_writes: u64,
}

impl DirectPathLearningState {
    pub fn note_transport_attempt(&mut self, host: Option<&str>, targets: &[SocketAddr], transport: TransportProtocol) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        // Only existing state has anything to clear; a new default entry would carry no information.
        if transport == TransportProtocol::Tcp
            && let Some(entry) = self.tuples.get_mut(&tuple_key)
        {
            entry.pending_udp_suppressed_at_ms = None;
            entry.terminal_state = None;
        }
    }

    fn tuple_entry(&mut self, tuple_key: TupleKey) -> &mut TupleState {
        if self.tuples.len() >= MAX_DIRECT_PATH_TUPLES && !self.tuples.contains_key(&tuple_key) {
            // Evict the least recently written tuple, preferring one without a pending UDP timeout.
            let victim = self
                .tuples
                .iter()
                .min_by_key(|(_, entry)| (entry.pending_udp_suppressed_at_ms.is_some(), entry.last_write))
                .map(|(key, _)| key.clone());
            if let Some(victim) = victim {
                self.tuples.remove(&victim);
            }
        }
        self.tuple_writes += 1;
        let entry = self.tuples.entry(tuple_key).or_default();
        entry.last_write = self.tuple_writes;
        entry
    }

    pub fn note_udp_suppressed(&mut self, host: Option<&str>, targets: &[SocketAddr], now_ms: u64) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuple_entry(tuple_key);
        let pending_since = *entry.pending_udp_suppressed_at_ms.get_or_insert(now_ms);
        entry.terminal_state = None;
        self.earliest_pending_udp_suppressed_ms =
            Some(self.earliest_pending_udp_suppressed_ms.map_or(pending_since, |earliest| earliest.min(pending_since)));
    }

    pub fn note_udp_failure(&mut self, host: Option<&str>, targets: &[SocketAddr]) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuple_entry(tuple_key);
        entry.udp_failed = true;
        entry.terminal_state = None;
    }

    pub fn note_owned_stack_required(
        &mut self,
        observer: Option<&dyn DirectPathLearningObserver>,
        host: Option<&str>,
        targets: &[SocketAddr],
    ) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuple_entry(tuple_key.clone());
        if entry.owned_stack_required_emitted {
            return;
        }
        entry.owned_stack_required_emitted = true;
        emit_learning_signal(observer, &tuple_key, "OWNED_STACK_REQUIRED", None);
    }

    pub fn note_tls_post_client_hello_failure(&mut self, host: Option<&str>, targets: &[SocketAddr]) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuple_entry(tuple_key);
        entry.tls_post_client_hello_failed = true;
        entry.terminal_state = None;
    }

    pub fn note_quic_success(
        &mut self,
        observer: Option<&dyn DirectPathLearningObserver>,
        host: Option<&str>,
        targets: &[SocketAddr],
    ) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuple_entry(tuple_key.clone());
        let should_emit = entry.terminal_state != Some(TerminalState::QuicSuccess);
        clear_negative_state(entry);
        entry.terminal_state = Some(TerminalState::QuicSuccess);
        if should_emit {
            emit_learning_signal(observer, &tuple_key, "QUIC_SUCCESS", None);
        }
    }

    pub fn note_tcp_success(
        &mut self,
        observer: Option<&dyn DirectPathLearningObserver>,
        host: Option<&str>,
        targets: &[SocketAddr],
        strategy_family: Option<&str>,
    ) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let Some(entry) = self.tuples.get_mut(&tuple_key) else {
            return;
        };
        if entry.tls_post_client_hello_failed {
            clear_negative_state(entry);
            emit_learning_signal(observer, &tuple_key, "TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK", strategy_family);
            return;
        }
        if entry.udp_failed {
            clear_negative_state(entry);
            emit_learning_signal(observer, &tuple_key, "QUIC_BLOCKED_TCP_OK", None);
        }
    }

    pub fn note_all_ips_failed(
        &mut self,
        observer: Option<&dyn DirectPathLearningObserver>,
        host: Option<&str>,
        targets: &[SocketAddr],
    ) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuple_entry(tuple_key.clone());
        if entry.terminal_state == Some(TerminalState::AllIpsFailed) {
            return;
        }
        clear_negative_state(entry);
        entry.terminal_state = Some(TerminalState::AllIpsFailed);
        emit_learning_signal(observer, &tuple_key, "ALL_IPS_FAILED", None);
    }

    /// Cheap check for [`Self::emit_due_timeouts`]; callers can test it under a read lock.
    pub fn has_due_timeouts(&self, now_ms: u64) -> bool {
        self.earliest_pending_udp_suppressed_ms
            .is_some_and(|earliest| now_ms.saturating_sub(earliest) >= NO_TCP_FALLBACK_WINDOW_MS)
    }

    pub fn emit_due_timeouts(&mut self, observer: Option<&dyn DirectPathLearningObserver>, now_ms: u64) {
        if !self.has_due_timeouts(now_ms) {
            return;
        }
        let due = self
            .tuples
            .iter()
            .filter_map(|(tuple_key, entry)| {
                entry
                    .pending_udp_suppressed_at_ms
                    .filter(|value| now_ms.saturating_sub(*value) >= NO_TCP_FALLBACK_WINDOW_MS)
                    .map(|_| tuple_key.clone())
            })
            .collect::<Vec<_>>();

        for tuple_key in due {
            if let Some(entry) = self.tuples.get_mut(&tuple_key) {
                clear_negative_state(entry);
                entry.terminal_state = Some(TerminalState::NoTcpFallbackDetected);
                emit_learning_signal(observer, &tuple_key, "NO_TCP_FALLBACK_DETECTED", None);
            }
        }
        self.earliest_pending_udp_suppressed_ms =
            self.tuples.values().filter_map(|entry| entry.pending_udp_suppressed_at_ms).min();
    }

    /// Derive the current [`DirectPathBlockClass`] for a (host, targets) tuple.
    ///
    /// Returns `DirectPathBlockClass::Clean` when no negative evidence has been
    /// recorded yet, so callers can always obtain a valid class without special-
    /// casing the absent-tuple case.
    pub fn block_class_for(&self, host: Option<&str>, targets: &[SocketAddr]) -> DirectPathBlockClass {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return DirectPathBlockClass::Clean;
        };
        let Some(entry) = self.tuples.get(&tuple_key) else {
            return DirectPathBlockClass::Clean;
        };
        block_class_from_state(entry)
    }

    /// Return a ranked list of transport arms for the given (host, targets) tuple.
    ///
    /// Arms are ordered from highest priority (index 0) to lowest. The list
    /// always contains at least one entry. Callers may iterate and attempt
    /// each arm in order, stopping on the first success. Each arm's
    /// `attempt_budget` reflects the *remaining* budget after subtracting
    /// previously recorded attempts via [`Self::note_arm_attempt`]; arms
    /// whose budget is fully exhausted are dropped from the list. When all
    /// arms for the current class are exhausted the list collapses to a
    /// single `relay_fallback` entry so callers always have an escalation
    /// path.
    pub fn ranked_arms_for(&self, host: Option<&str>, targets: &[SocketAddr]) -> Vec<RankedArm> {
        let class = self.block_class_for(host, targets);
        let mut arms = ranked_arms_for_class(class);

        let attempts: Option<&HashMap<&'static str, u32>> = tuple_key_for_targets(host, targets)
            .as_ref()
            .and_then(|key| self.tuples.get(key))
            .map(|entry| &entry.arm_attempts);
        apply_attempt_budgets(&mut arms, attempts);

        arms
    }

    /// Record an attempt against the arm `arm_label` for the (host, targets)
    /// tuple. Subsequent calls to [`Self::ranked_arms_for`] subtract the
    /// recorded attempts from the arm's `attempt_budget`; once the budget is
    /// exhausted the arm is dropped from the ranked list. Counters are reset
    /// when a positive signal clears the negative state for the tuple.
    pub fn note_arm_attempt(&mut self, host: Option<&str>, targets: &[SocketAddr], arm_label: &'static str) {
        let Some(tuple_key) = tuple_key_for_targets(host, targets) else {
            return;
        };
        let entry = self.tuple_entry(tuple_key);
        *entry.arm_attempts.entry(arm_label).or_insert(0) += 1;
    }
}

#[cfg(test)]
mod tests;
