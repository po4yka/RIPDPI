use std::collections::BTreeMap;

use ripdpi_config::RuntimeConfig;
use ripdpi_failure_classifier::BlockSignal;

mod host_filter;
mod state;
mod store;

pub(super) use host_filter::normalize_learned_host;
pub(super) use state::{host_has_active_block, host_penalty_active_for_group, record_has_learned_winner};
pub(super) use store::load_learned_host_store;

use state::{ensure_host_order, host_has_active_penalty, promote_group, refresh_block_metadata};
#[cfg(test)]
use store::config_fingerprint;
use store::network_scope_key;

use super::types::{LearnedHostRecord, PendingBlockedHost};
use super::{
    now_millis, HostAutolearnEvent, HostAutolearnState, RuntimePolicy, BLOCK_CONFIRMATION_WINDOW_MS,
    EMPTY_LEARNED_HOSTS,
};

impl RuntimePolicy {
    pub fn drain_autolearn_events(&mut self) -> Vec<HostAutolearnEvent> {
        self.autolearn_events.drain(..).collect()
    }

    pub fn autolearn_state(&mut self, config: &RuntimeConfig) -> HostAutolearnState {
        let now_ms = now_millis();
        self.prune_expired_autolearn_state(now_ms);
        let penalized =
            self.learned_hosts(config).values().filter(|record| host_has_active_penalty(record, now_ms)).count();
        let blocked =
            self.learned_hosts(config).values().filter(|record| host_has_active_block(record, now_ms)).count();
        let (last_block_signal, last_block_provider) = self
            .learned_hosts(config)
            .values()
            .filter_map(|record| record.last_blocked_at_ms.map(|timestamp| (timestamp, record)))
            .max_by_key(|(timestamp, _)| *timestamp)
            .map_or((None, None), |(_, record)| {
                (record.last_block_signal.map(|value| value.as_str().to_string()), record.last_block_provider.clone())
            });
        HostAutolearnState {
            enabled: config.host_autolearn.enabled,
            learned_host_count: self.learned_hosts(config).len(),
            penalized_host_count: penalized,
            blocked_host_count: blocked,
            last_block_signal,
            last_block_provider,
        }
    }

    pub(super) fn learned_hosts(&self, config: &RuntimeConfig) -> &BTreeMap<String, LearnedHostRecord> {
        self.learned_hosts_by_scope.get(network_scope_key(config)).unwrap_or(&EMPTY_LEARNED_HOSTS)
    }

    pub(super) fn learned_hosts_mut(&mut self, config: &RuntimeConfig) -> &mut BTreeMap<String, LearnedHostRecord> {
        self.learned_hosts_by_scope.entry(network_scope_key(config).to_owned()).or_default()
    }

    pub(super) fn pending_blocked_hosts_mut(
        &mut self,
        config: &RuntimeConfig,
    ) -> &mut BTreeMap<String, PendingBlockedHost> {
        self.pending_blocked_hosts_by_scope.entry(network_scope_key(config).to_owned()).or_default()
    }

    pub(crate) fn note_host_failure(&mut self, config: &RuntimeConfig, host: &str, group_index: usize) {
        if !config.host_autolearn.enabled {
            return;
        }
        let Some(host) = normalize_learned_host(host) else {
            return;
        };
        let now_ms = now_millis();
        self.prune_expired_autolearn_state(now_ms);
        let record = self.learned_hosts_mut(config).entry(host.clone()).or_default();
        let stats = record.group_stats.entry(group_index).or_default();
        stats.failure_count = stats.failure_count.saturating_add(1);
        stats.last_failure_at_ms = now_ms;
        stats.penalty_until_ms = now_ms.saturating_add(config.host_autolearn.penalty_ttl_secs.max(1) as u64 * 1_000);
        record.updated_at_ms = now_ms;
        ensure_host_order(record, group_index);
        self.enforce_autolearn_limit(config, now_ms);
        self.persist_host_store(config);
        self.autolearn_events.push_back(HostAutolearnEvent {
            action: "group_penalized",
            host: Some(host),
            group_index: Some(group_index),
        });
    }

    pub(crate) fn note_host_success(&mut self, config: &RuntimeConfig, host: &str, group_index: usize) {
        if !config.host_autolearn.enabled {
            return;
        }
        let Some(host) = normalize_learned_host(host) else {
            return;
        };
        let now_ms = now_millis();
        self.prune_expired_autolearn_state(now_ms);
        let record = self.learned_hosts_mut(config).entry(host.clone()).or_default();
        let stats = record.group_stats.entry(group_index).or_default();
        stats.success_count = stats.success_count.saturating_add(1);
        stats.last_success_at_ms = now_ms;
        stats.penalty_until_ms = 0;
        record.updated_at_ms = now_ms;
        promote_group(record, group_index);
        self.enforce_autolearn_limit(config, now_ms);
        self.persist_host_store(config);
        self.autolearn_events.push_back(HostAutolearnEvent {
            action: "host_promoted",
            host: Some(host),
            group_index: Some(group_index),
        });
    }

    pub fn note_block_signal(
        &mut self,
        config: &RuntimeConfig,
        host: &str,
        signal: BlockSignal,
        provider: Option<&str>,
        confirmation_allowed: bool,
    ) {
        if !config.host_autolearn.enabled || !confirmation_allowed {
            return;
        }
        let Some(host) = normalize_learned_host(host) else {
            return;
        };
        let provider = provider.map(str::trim).filter(|value| !value.is_empty()).map(ToOwned::to_owned);
        let now_ms = now_millis();
        self.prune_expired_autolearn_state(now_ms);
        let already_blocked =
            self.learned_hosts(config).get(&host).is_some_and(|record| host_has_active_block(record, now_ms));

        let confirmed = if already_blocked {
            let record = self.learned_hosts_mut(config).entry(host.clone()).or_default();
            refresh_block_metadata(record, now_ms, signal, provider.clone());
            true
        } else {
            let pending = self.pending_blocked_hosts_mut(config).entry(host.clone()).or_default();
            if pending.first_detected_at_ms == 0
                || now_ms.saturating_sub(pending.first_detected_at_ms) > BLOCK_CONFIRMATION_WINDOW_MS
            {
                pending.first_detected_at_ms = now_ms;
                pending.count = 1;
                pending.last_signal = Some(signal);
                pending.last_provider = provider.clone();
                false
            } else {
                pending.count = pending.count.saturating_add(1);
                pending.last_signal = Some(signal);
                pending.last_provider = provider.clone();
                if pending.count >= 2 {
                    let record = self.learned_hosts_mut(config).entry(host.clone()).or_default();
                    refresh_block_metadata(record, now_ms, signal, provider.clone());
                    self.pending_blocked_hosts_mut(config).remove(&host);
                    true
                } else {
                    false
                }
            }
        };

        if !confirmed {
            return;
        }

        self.enforce_autolearn_limit(config, now_ms);
        self.persist_host_store(config);
        if !already_blocked {
            self.autolearn_events.push_back(HostAutolearnEvent {
                action: "host_blocked",
                host: Some(host),
                group_index: None,
            });
        }
    }

    fn enforce_autolearn_limit(&mut self, config: &RuntimeConfig, now_ms: u64) {
        let max_hosts = config.host_autolearn.max_hosts.max(1);
        while self.learned_hosts(config).len() > max_hosts {
            let host_to_remove = {
                let hosts = self.learned_hosts(config);
                hosts
                    .iter()
                    .filter(|(_, record)| !host_has_active_penalty(record, now_ms))
                    .min_by_key(|(_, record)| record.updated_at_ms)
                    .or_else(|| hosts.iter().min_by_key(|(_, record)| record.updated_at_ms))
                    .map(|(host, _)| host.clone())
            };
            let Some(host) = host_to_remove else {
                break;
            };
            self.learned_hosts_mut(config).remove(&host);
        }
    }

    /// Seed the autolearn table from strategy probe results.
    ///
    /// Each entry is `(domain, group_index)` -- the domain that should prefer
    /// the given group when the current config is active. Domains not in the
    /// seed set are left for the normal runtime fallback escalation.
    ///
    /// Only domains that pass [`normalize_learned_host`] are recorded. Existing
    /// autolearn entries for a domain are preserved; the seed merely adds a
    /// success record so the preferred-group ordering is established.
    ///
    /// Returns the number of domains actually seeded.
    pub fn seed_from_strategy_results(&mut self, config: &RuntimeConfig, seeds: &[(String, usize)]) -> usize {
        if !config.host_autolearn.enabled || seeds.is_empty() {
            return 0;
        }
        let now_ms = now_millis();
        self.prune_expired_autolearn_state(now_ms);
        let mut seeded = 0usize;
        for (domain, group_index) in seeds {
            if *group_index >= config.groups.len() {
                continue;
            }
            let Some(host) = normalize_learned_host(domain) else {
                continue;
            };
            let record = self.learned_hosts_mut(config).entry(host.clone()).or_default();
            // Only seed if this group has not already been recorded for this host.
            let stats = record.group_stats.entry(*group_index).or_default();
            if stats.success_count == 0 {
                stats.success_count = 1;
                stats.last_success_at_ms = now_ms;
                record.updated_at_ms = now_ms;
                promote_group(record, *group_index);
                seeded += 1;
            }
        }
        if seeded > 0 {
            self.enforce_autolearn_limit(config, now_ms);
            self.persist_host_store(config);
            self.autolearn_events.push_back(HostAutolearnEvent {
                action: "autolearn_seeded",
                host: None,
                group_index: None,
            });
        }
        seeded
    }
}

#[cfg(test)]
mod tests;
