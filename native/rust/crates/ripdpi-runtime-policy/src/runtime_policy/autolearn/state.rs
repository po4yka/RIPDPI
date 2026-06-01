use ripdpi_failure_classifier::BlockSignal;

use crate::runtime_policy::types::LearnedHostRecord;
use crate::runtime_policy::{BLOCK_CONFIRMATION_WINDOW_MS, BLOCKED_HOST_TTL_MS, RuntimePolicy};

pub(super) fn ensure_host_order(record: &mut LearnedHostRecord, group_index: usize) {
    if !record.preferred_groups.contains(&group_index) {
        record.preferred_groups.push(group_index);
    }
}

pub(super) fn promote_group(record: &mut LearnedHostRecord, group_index: usize) {
    record.preferred_groups.retain(|current| *current != group_index);
    record.preferred_groups.insert(0, group_index);
}

pub(super) fn host_has_active_penalty(record: &LearnedHostRecord, now_ms: u64) -> bool {
    record.group_stats.values().any(|stats| stats.penalty_until_ms > now_ms)
}

pub(in crate::runtime_policy) fn host_has_active_block(record: &LearnedHostRecord, now_ms: u64) -> bool {
    record.blocked_until_ms.is_some_and(|value| value > now_ms)
}

pub(in crate::runtime_policy) fn record_has_learned_winner(record: &LearnedHostRecord) -> bool {
    record.group_stats.values().any(|stats| stats.success_count > 0)
}

pub(in crate::runtime_policy) fn host_penalty_active_for_group(
    record: &LearnedHostRecord,
    group_index: usize,
    now_ms: u64,
) -> bool {
    record.group_stats.get(&group_index).is_some_and(|stats| stats.penalty_until_ms > now_ms)
}

pub(super) fn refresh_block_metadata(
    record: &mut LearnedHostRecord,
    now_ms: u64,
    signal: BlockSignal,
    provider: Option<String>,
) {
    record.blocked_until_ms = Some(now_ms.saturating_add(BLOCKED_HOST_TTL_MS));
    record.last_blocked_at_ms = Some(now_ms);
    record.last_block_signal = Some(signal);
    record.last_block_provider = provider;
    record.updated_at_ms = now_ms;
}

pub(super) fn prune_expired_host_state(record: &mut LearnedHostRecord, now_ms: u64) {
    if record.blocked_until_ms.is_some_and(|value| value <= now_ms) {
        record.blocked_until_ms = None;
        record.last_blocked_at_ms = None;
        record.last_block_signal = None;
        record.last_block_provider = None;
    }
}

pub(super) fn host_record_has_persisted_state(record: &LearnedHostRecord) -> bool {
    !record.preferred_groups.is_empty()
        || !record.group_stats.is_empty()
        || record.blocked_until_ms.is_some()
        || record.last_blocked_at_ms.is_some()
}

impl RuntimePolicy {
    pub(super) fn prune_expired_autolearn_state(&mut self, now_ms: u64) {
        for hosts in self.learned_hosts_by_scope.values_mut() {
            hosts.retain(|_, record| {
                prune_expired_host_state(record, now_ms);
                host_record_has_persisted_state(record)
            });
        }
        for pending in self.pending_blocked_hosts_by_scope.values_mut() {
            pending.retain(|_, record| {
                record.first_detected_at_ms != 0
                    && now_ms.saturating_sub(record.first_detected_at_ms) <= BLOCK_CONFIRMATION_WINDOW_MS
            });
        }
    }
}
