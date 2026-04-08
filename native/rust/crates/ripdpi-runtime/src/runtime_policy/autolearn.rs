use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::net::IpAddr;
use std::path::Path;

use ring::digest;
use ripdpi_config::RuntimeConfig;
use ripdpi_failure_classifier::BlockSignal;

use super::types::{
    HostAutolearnState, LearnedHostRecord, LearnedHostStore, LearnedNetworkScopeStore, LoadLearnedHostStoreError,
    PendingBlockedHost,
};
use super::{
    next_temp_file_nonce, now_millis, HostAutolearnEvent, RuntimePolicy, BLOCKED_HOST_TTL_MS,
    BLOCK_CONFIRMATION_WINDOW_MS, DEFAULT_NETWORK_SCOPE_KEY, EMPTY_LEARNED_HOSTS, HOST_AUTOLEARN_STORE_VERSION,
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

    pub(crate) fn note_block_signal(
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

    fn prune_expired_autolearn_state(&mut self, now_ms: u64) {
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

    fn persist_host_store(&mut self, config: &RuntimeConfig) {
        let now_ms = now_millis();
        if now_ms.saturating_sub(self.last_persist_at_ms) < super::AUTOLEARN_PERSIST_DEBOUNCE_MS {
            return;
        }
        match self.write_host_store(config) {
            Ok(()) => {
                self.last_persist_at_ms = now_ms;
            }
            Err(err) => {
                tracing::warn!("autolearn store write failed (non-fatal): {err}");
            }
        }
    }

    /// Force-persist the host store, bypassing the debounce window.
    /// Call this on proxy shutdown to avoid losing recent state.
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

    /// Force-persist the host store, bypassing the debounce window.
    /// Call this on proxy shutdown to avoid losing recent state.
    pub fn flush_host_store(&mut self, config: &RuntimeConfig) {
        match self.write_host_store(config) {
            Ok(()) => {
                self.last_persist_at_ms = now_millis();
            }
            Err(err) => {
                tracing::warn!("autolearn store flush failed (non-fatal): {err}");
            }
        }
    }

    fn write_host_store(&self, config: &RuntimeConfig) -> io::Result<()> {
        if !config.host_autolearn.enabled {
            return Ok(());
        }
        let Some(path) = config.host_autolearn.store_path.as_deref() else {
            return Ok(());
        };
        let store = LearnedHostStore {
            version: HOST_AUTOLEARN_STORE_VERSION,
            fingerprint: config_fingerprint(config),
            scopes: self
                .learned_hosts_by_scope
                .iter()
                .map(|(scope, hosts)| (scope.clone(), LearnedNetworkScopeStore { hosts: hosts.clone() }))
                .collect(),
        };
        let payload = serde_json::to_vec_pretty(&store)
            .map_err(|err| io::Error::other(format!("failed to serialize host autolearn store: {err}")))?;
        atomic_write(Path::new(path), &payload)
    }
}

pub(super) fn load_learned_host_store(
    config: &RuntimeConfig,
) -> Result<BTreeMap<String, BTreeMap<String, LearnedHostRecord>>, LoadLearnedHostStoreError> {
    let Some(path) = config.host_autolearn.store_path.as_deref() else {
        return Ok(BTreeMap::new());
    };
    let path = Path::new(path);
    if !path.exists() {
        return Ok(BTreeMap::new());
    }
    let payload = fs::read(path).map_err(|_| LoadLearnedHostStoreError::Io)?;
    let store =
        serde_json::from_slice::<LearnedHostStore>(&payload).map_err(|_| LoadLearnedHostStoreError::Invalidated)?;
    if store.version != HOST_AUTOLEARN_STORE_VERSION || store.fingerprint != config_fingerprint(config) {
        return Err(LoadLearnedHostStoreError::Invalidated);
    }
    let now_ms = now_millis();
    Ok(store
        .scopes
        .into_iter()
        .map(|(scope, scope_store)| {
            let hosts = scope_store
                .hosts
                .into_iter()
                .filter_map(|(host, mut record)| {
                    let normalized_host = normalize_learned_host(&host)?;
                    prune_expired_host_state(&mut record, now_ms);
                    record.preferred_groups.retain(|group_index| *group_index < config.groups.len());
                    record.group_stats.retain(|group_index, _| *group_index < config.groups.len());
                    host_record_has_persisted_state(&record).then_some((normalized_host, record))
                })
                .collect::<BTreeMap<_, _>>();
            (scope, hosts)
        })
        .filter(|(_, hosts)| !hosts.is_empty())
        .collect())
}

fn ensure_host_order(record: &mut LearnedHostRecord, group_index: usize) {
    if !record.preferred_groups.contains(&group_index) {
        record.preferred_groups.push(group_index);
    }
}

fn promote_group(record: &mut LearnedHostRecord, group_index: usize) {
    record.preferred_groups.retain(|current| *current != group_index);
    record.preferred_groups.insert(0, group_index);
}

fn host_has_active_penalty(record: &LearnedHostRecord, now_ms: u64) -> bool {
    record.group_stats.values().any(|stats| stats.penalty_until_ms > now_ms)
}

pub(super) fn host_has_active_block(record: &LearnedHostRecord, now_ms: u64) -> bool {
    record.blocked_until_ms.is_some_and(|value| value > now_ms)
}

pub(super) fn record_has_learned_winner(record: &LearnedHostRecord) -> bool {
    record.group_stats.values().any(|stats| stats.success_count > 0)
}

pub(super) fn host_penalty_active_for_group(record: &LearnedHostRecord, group_index: usize, now_ms: u64) -> bool {
    record.group_stats.get(&group_index).is_some_and(|stats| stats.penalty_until_ms > now_ms)
}

fn refresh_block_metadata(record: &mut LearnedHostRecord, now_ms: u64, signal: BlockSignal, provider: Option<String>) {
    record.blocked_until_ms = Some(now_ms.saturating_add(BLOCKED_HOST_TTL_MS));
    record.last_blocked_at_ms = Some(now_ms);
    record.last_block_signal = Some(signal);
    record.last_block_provider = provider;
    record.updated_at_ms = now_ms;
}

fn prune_expired_host_state(record: &mut LearnedHostRecord, now_ms: u64) {
    if record.blocked_until_ms.is_some_and(|value| value <= now_ms) {
        record.blocked_until_ms = None;
        record.last_blocked_at_ms = None;
        record.last_block_signal = None;
        record.last_block_provider = None;
    }
}

fn host_record_has_persisted_state(record: &LearnedHostRecord) -> bool {
    !record.preferred_groups.is_empty()
        || !record.group_stats.is_empty()
        || record.blocked_until_ms.is_some()
        || record.last_blocked_at_ms.is_some()
}

pub(super) fn normalize_learned_host(host: &str) -> Option<String> {
    let trimmed = host.trim().trim_end_matches('.');
    if trimmed.is_empty() {
        return None;
    }
    let normalized = trimmed.to_ascii_lowercase();
    if normalized.parse::<IpAddr>().is_ok() {
        return None;
    }
    if is_system_telemetry_host(&normalized) {
        tracing::debug!(host = normalized.as_str(), "autolearn: skipping system telemetry host");
        return None;
    }
    Some(normalized)
}

/// Returns true for hosts belonging to OS/vendor telemetry, push notification,
/// and cloud infrastructure services that are never DPI-blocked and should not
/// consume autolearn slots.
fn is_system_telemetry_host(host: &str) -> bool {
    const EXCLUDED_SUFFIXES: &[&str] = &[
        ".googleapis.com",
        ".gstatic.com",
        ".googlevideo.com",
        ".google-analytics.com",
        ".googleadservices.com",
        "mtalk.google.com",
        "connectivitycheck.gstatic.com",
        ".hicloud.com",
        ".dbankcloud.com",
        ".dbankcloud.ru",
        ".dbankcdn.com",
        ".hwcdn.net",
        ".samsungcloud.com",
        ".samsungelectronics.com",
        ".samsung-gasp.com",
        ".icloud.com",
        ".apple.com",
        ".mzstatic.com",
        ".msftconnecttest.com",
        ".windowsupdate.com",
        ".trafficmanager.net",
        ".miui.com",
        ".xiaomi.com",
        ".firebaseio.com",
        ".crashlytics.com",
        ".app-measurement.com",
    ];

    for suffix in EXCLUDED_SUFFIXES {
        if host == suffix.trim_start_matches('.') || host.ends_with(suffix) {
            return true;
        }
    }
    false
}

fn config_fingerprint(config: &RuntimeConfig) -> String {
    let mut input = format!("{:?}", config.groups);
    input.push_str(&format!("|{}", config.groups.len()));
    let d = digest::digest(&digest::SHA256, input.as_bytes());
    d.as_ref().iter().fold(String::new(), |mut s, b| {
        use std::fmt::Write;
        write!(s, "{b:02x}").unwrap();
        s
    })
}

fn network_scope_key(config: &RuntimeConfig) -> &str {
    config
        .adaptive
        .network_scope_key
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or(DEFAULT_NETWORK_SCOPE_KEY)
}

fn atomic_write(path: &Path, payload: &[u8]) -> io::Result<()> {
    let Some(parent) = path.parent() else {
        return fs::write(path, payload);
    };
    fs::create_dir_all(parent)?;
    let tmp_name = format!(
        ".{}.tmp-{}-{}",
        path.file_name().and_then(|name| name.to_str()).unwrap_or("autolearn"),
        std::process::id(),
        next_temp_file_nonce()
    );
    let tmp_path = parent.join(tmp_name);
    fs::write(&tmp_path, payload)?;
    if path.exists() {
        let _ = fs::remove_file(path);
    }
    fs::rename(tmp_path, path)
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::runtime_policy::test_support::{autolearn_config, sample_dest};
    use crate::runtime_policy::types::LearnedGroupStats;
    use crate::runtime_policy::{ConnectionRoute, RuntimePolicy};
    use serde_json::json;

    #[test]
    fn successful_fallback_promotes_final_group_for_host() {
        let config = autolearn_config(3, 32);
        let dest = sample_dest(443);
        let mut policy = RuntimePolicy::load(&config);

        policy
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                Some("example.org"),
            )
            .expect("learn first group");
        policy
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 2, attempted_mask: config.groups[0].bit },
                Some("example.org"),
            )
            .expect("promote fallback winner");

        let learned = policy.learned_hosts(&config).get("example.org").expect("learned host");
        assert_eq!(learned.preferred_groups.first().copied(), Some(2));
    }

    #[test]
    fn penalties_suppress_group_until_ttl_expiry() {
        let config = autolearn_config(2, 32);
        let dest = sample_dest(443);
        let mut policy = RuntimePolicy::load(&config);

        policy
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 1, attempted_mask: 0 },
                Some("example.org"),
            )
            .expect("learn preferred group");
        policy.note_host_failure(&config, "example.org", 1);

        let penalized_route = policy
            .select_initial(
                dest,
                None,
                Some("example.org"),
                true,
                crate::runtime_policy::TransportProtocol::Tcp,
                &config,
            )
            .expect("fallback while penalized");
        assert_eq!(penalized_route.group_index, 0);

        policy
            .learned_hosts_mut(&config)
            .get_mut("example.org")
            .and_then(|record| record.group_stats.get_mut(&1))
            .expect("penalized stats")
            .penalty_until_ms = now_millis().saturating_sub(1);

        let recovered_route = policy
            .select_initial(
                dest,
                None,
                Some("example.org"),
                true,
                crate::runtime_policy::TransportProtocol::Tcp,
                &config,
            )
            .expect("preferred route after penalty expiry");
        assert_eq!(recovered_route.group_index, 1);
    }

    #[test]
    fn fingerprint_mismatch_invalidates_learned_state() {
        let mut config = autolearn_config(1, 32);
        let dest = sample_dest(443);
        {
            let mut policy = RuntimePolicy::load(&config);
            policy
                .note_route_success(
                    &config,
                    dest,
                    &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                    Some("example.org"),
                )
                .expect("persist learned host");
        }

        config.groups.push(ripdpi_config::DesyncGroup::new(1));
        let mut policy = RuntimePolicy::load(&config);

        assert!(policy.learned_hosts(&config).is_empty());
        let events = policy.drain_autolearn_events();
        assert!(events.iter().any(|event| event.action == "store_reset"));
    }

    #[test]
    fn max_host_eviction_removes_oldest_records() {
        let config = autolearn_config(1, 1);
        let dest = sample_dest(443);
        let mut policy = RuntimePolicy::load(&config);

        policy
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                Some("first.example"),
            )
            .expect("learn first host");
        policy.learned_hosts_mut(&config).get_mut("first.example").expect("first host").updated_at_ms = 1;
        policy
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                Some("second.example"),
            )
            .expect("learn second host");

        assert!(!policy.learned_hosts(&config).contains_key("first.example"));
        assert!(policy.learned_hosts(&config).contains_key("second.example"));
    }

    #[test]
    fn host_autolearn_is_scoped_by_network_scope_key() {
        let mut config_a = autolearn_config(1, 32);
        let path = config_a.host_autolearn.store_path.clone().expect("store path");
        config_a.adaptive.network_scope_key = Some("scope-a".to_string());
        let dest = sample_dest(443);

        let mut policy_a = RuntimePolicy::load(&config_a);
        policy_a
            .note_route_success(
                &config_a,
                dest,
                &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                Some("alpha.example"),
            )
            .expect("learn scope a host");

        let mut config_b = autolearn_config(1, 32);
        config_b.host_autolearn.store_path = Some(path);
        config_b.adaptive.network_scope_key = Some("scope-b".to_string());
        let mut policy_b = RuntimePolicy::load(&config_b);
        assert!(policy_b.learned_hosts(&config_b).is_empty());
        policy_b
            .note_route_success(
                &config_b,
                dest,
                &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                Some("beta.example"),
            )
            .expect("learn scope b host");

        let reloaded_a = RuntimePolicy::load(&config_a);
        assert!(reloaded_a.learned_hosts(&config_a).contains_key("alpha.example"));
        assert!(!reloaded_a.learned_hosts(&config_a).contains_key("beta.example"));

        let reloaded_b = RuntimePolicy::load(&config_b);
        assert!(reloaded_b.learned_hosts(&config_b).contains_key("beta.example"));
        assert!(!reloaded_b.learned_hosts(&config_b).contains_key("alpha.example"));
    }

    #[test]
    fn block_signal_requires_two_confirmations_within_window() {
        let config = autolearn_config(1, 32);
        let mut policy = RuntimePolicy::load(&config);

        policy.note_block_signal(&config, "example.org", BlockSignal::TcpReset, Some("rkn"), true);
        assert_eq!(policy.autolearn_state(&config).blocked_host_count, 0);
        assert!(policy.drain_autolearn_events().is_empty());

        let pending = policy.pending_blocked_hosts_mut(&config).get("example.org").cloned().expect("pending host");
        assert_eq!(pending.count, 1);
        assert_eq!(pending.last_signal, Some(BlockSignal::TcpReset));
        assert_eq!(pending.last_provider.as_deref(), Some("rkn"));

        policy.note_block_signal(&config, "example.org", BlockSignal::TcpReset, Some("rkn"), true);

        let state = policy.autolearn_state(&config);
        assert_eq!(state.blocked_host_count, 1);
        assert_eq!(state.last_block_signal.as_deref(), Some("tcp_reset"));
        assert_eq!(state.last_block_provider.as_deref(), Some("rkn"));

        let record = policy.learned_hosts(&config).get("example.org").expect("blocked host");
        assert!(host_has_active_block(record, now_millis()));
        assert_eq!(record.last_block_signal, Some(BlockSignal::TcpReset));
        assert_eq!(record.last_block_provider.as_deref(), Some("rkn"));

        let events = policy.drain_autolearn_events();
        assert!(events.iter().any(|event| {
            event.action == "host_blocked"
                && event.host.as_deref() == Some("example.org")
                && event.group_index.is_none()
        }));
    }

    #[test]
    fn stale_pending_block_confirmation_resets_after_window() {
        let config = autolearn_config(1, 32);
        let mut policy = RuntimePolicy::load(&config);

        policy.note_block_signal(&config, "example.org", BlockSignal::TcpReset, None, true);
        policy.pending_blocked_hosts_mut(&config).get_mut("example.org").expect("pending host").first_detected_at_ms =
            now_millis().saturating_sub(BLOCK_CONFIRMATION_WINDOW_MS + 1);

        policy.note_block_signal(&config, "example.org", BlockSignal::TcpReset, None, true);

        assert_eq!(policy.autolearn_state(&config).blocked_host_count, 0);
        let pending = policy.pending_blocked_hosts_mut(&config).get("example.org").expect("reset pending host");
        assert_eq!(pending.count, 1);
    }

    #[test]
    fn blocked_host_state_refreshes_and_expires() {
        let config = autolearn_config(1, 32);
        let mut policy = RuntimePolicy::load(&config);

        policy.note_block_signal(&config, "example.org", BlockSignal::TcpReset, Some("rkn"), true);
        policy.note_block_signal(&config, "example.org", BlockSignal::TcpReset, Some("rkn"), true);

        let old_until = {
            let record = policy.learned_hosts_mut(&config).get_mut("example.org").expect("blocked host");
            record.blocked_until_ms = Some(now_millis().saturating_add(1_000));
            record.blocked_until_ms.expect("old ttl")
        };
        policy.note_block_signal(&config, "example.org", BlockSignal::TcpReset, Some("rkn"), true);
        let refreshed_until = policy
            .learned_hosts(&config)
            .get("example.org")
            .and_then(|record| record.blocked_until_ms)
            .expect("refreshed blocked ttl");
        assert!(refreshed_until > old_until);

        policy.learned_hosts_mut(&config).get_mut("example.org").expect("blocked host").blocked_until_ms =
            Some(now_millis().saturating_sub(1));

        let state = policy.autolearn_state(&config);
        assert_eq!(state.blocked_host_count, 0);
        assert_eq!(state.learned_host_count, 0);
        assert_eq!(state.last_block_signal, None);
        assert_eq!(state.last_block_provider, None);
        assert!(!policy.learned_hosts(&config).contains_key("example.org"));
    }

    #[test]
    fn block_expiry_clears_block_metadata_but_keeps_learned_hosts() {
        let config = autolearn_config(1, 32);
        let dest = sample_dest(443);
        let mut policy = RuntimePolicy::load(&config);

        policy
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                Some("example.org"),
            )
            .expect("learn host");
        policy.note_block_signal(&config, "example.org", BlockSignal::TcpReset, Some("rkn"), true);
        policy.note_block_signal(&config, "example.org", BlockSignal::TcpReset, Some("rkn"), true);
        policy.learned_hosts_mut(&config).get_mut("example.org").expect("blocked host").blocked_until_ms =
            Some(now_millis().saturating_sub(1));

        let state = policy.autolearn_state(&config);
        let record = policy.learned_hosts(&config).get("example.org").expect("learned host after expiry");

        assert_eq!(state.blocked_host_count, 0);
        assert_eq!(state.learned_host_count, 1);
        assert_eq!(state.last_block_signal, None);
        assert_eq!(state.last_block_provider, None);
        assert_eq!(record.preferred_groups, vec![0]);
        assert_eq!(record.last_blocked_at_ms, None);
        assert_eq!(record.last_block_signal, None);
        assert_eq!(record.last_block_provider, None);
    }

    #[test]
    fn blocked_host_store_is_scoped_by_network_scope_key() {
        let mut config_a = autolearn_config(1, 32);
        let path = config_a.host_autolearn.store_path.clone().expect("store path");
        config_a.adaptive.network_scope_key = Some("scope-a".to_string());

        let mut policy_a = RuntimePolicy::load(&config_a);
        policy_a.note_block_signal(&config_a, "alpha.example", BlockSignal::TcpReset, Some("rkn"), true);
        policy_a.note_block_signal(&config_a, "alpha.example", BlockSignal::TcpReset, Some("rkn"), true);

        let mut config_b = autolearn_config(1, 32);
        config_b.host_autolearn.store_path = Some(path);
        config_b.adaptive.network_scope_key = Some("scope-b".to_string());

        let mut policy_b = RuntimePolicy::load(&config_b);
        assert_eq!(policy_b.autolearn_state(&config_b).blocked_host_count, 0);
        policy_b.note_block_signal(&config_b, "beta.example", BlockSignal::TcpReset, None, true);
        policy_b.note_block_signal(&config_b, "beta.example", BlockSignal::TcpReset, None, true);

        let mut reloaded_a = RuntimePolicy::load(&config_a);
        assert_eq!(reloaded_a.autolearn_state(&config_a).blocked_host_count, 1);
        assert!(reloaded_a.learned_hosts(&config_a).contains_key("alpha.example"));
        assert!(!reloaded_a.learned_hosts(&config_a).contains_key("beta.example"));

        let mut reloaded_b = RuntimePolicy::load(&config_b);
        assert_eq!(reloaded_b.autolearn_state(&config_b).blocked_host_count, 1);
        assert!(reloaded_b.learned_hosts(&config_b).contains_key("beta.example"));
        assert!(!reloaded_b.learned_hosts(&config_b).contains_key("alpha.example"));
    }

    #[test]
    fn load_learned_host_store_accepts_records_without_block_metadata() {
        let config = autolearn_config(1, 32);
        let store_path = config.host_autolearn.store_path.clone().expect("store path");
        let payload = json!({
            "version": HOST_AUTOLEARN_STORE_VERSION,
            "fingerprint": config_fingerprint(&config),
            "scopes": {
                DEFAULT_NETWORK_SCOPE_KEY: {
                    "hosts": {
                        "example.org": {
                            "preferred_groups": [0],
                            "group_stats": {
                                "0": {
                                    "success_count": 1,
                                    "failure_count": 0,
                                    "penalty_until_ms": 0,
                                    "last_success_at_ms": 1,
                                    "last_failure_at_ms": 0
                                }
                            },
                            "updated_at_ms": 1
                        }
                    }
                }
            }
        });
        fs::write(&store_path, serde_json::to_vec_pretty(&payload).expect("serialize old store payload"))
            .expect("write old store payload");

        let policy = RuntimePolicy::load(&config);
        let record = policy.learned_hosts(&config).get("example.org").expect("loaded host");
        assert_eq!(record.preferred_groups, vec![0]);
        assert!(record.blocked_until_ms.is_none());
        assert!(record.last_blocked_at_ms.is_none());
        assert!(record.last_block_signal.is_none());
        assert!(record.last_block_provider.is_none());
    }

    // -- config_fingerprint unit tests --

    #[test]
    fn config_fingerprint_is_deterministic() {
        let config = autolearn_config(2, 32);
        let fp1 = config_fingerprint(&config);
        let fp2 = config_fingerprint(&config);
        assert_eq!(fp1, fp2);
    }

    #[test]
    fn config_fingerprint_differs_for_different_group_counts() {
        let config_1 = autolearn_config(1, 32);
        let config_2 = autolearn_config(2, 32);
        assert_ne!(config_fingerprint(&config_1), config_fingerprint(&config_2));
    }

    #[test]
    fn config_fingerprint_is_64_char_lowercase_hex() {
        let config = autolearn_config(1, 32);
        let fp = config_fingerprint(&config);
        assert_eq!(fp.len(), 64, "SHA-256 hex digest must be 64 chars");
        assert!(fp.chars().all(|c| c.is_ascii_hexdigit()), "must be hex");
        assert_eq!(fp, fp.to_lowercase(), "must be lowercase hex");
    }

    #[test]
    fn config_fingerprint_handles_empty_groups() {
        let config = autolearn_config(0, 32);
        let fp = config_fingerprint(&config);
        assert_eq!(fp.len(), 64, "empty groups must still produce valid SHA-256 hex");
        assert!(fp.chars().all(|c| c.is_ascii_hexdigit()));
    }

    #[test]
    fn config_fingerprint_golden_value_one_group() {
        // Two configs with identical group structure must produce the same fingerprint,
        // regardless of other RuntimeConfig fields (store_path, max_hosts, etc.).
        let config_a = autolearn_config(1, 32);
        let config_b = autolearn_config(1, 64);
        let fp_a = config_fingerprint(&config_a);
        let fp_b = config_fingerprint(&config_b);
        assert_eq!(fp_a, fp_b, "fingerprint must depend only on groups, not on other config fields");
    }

    // ---- normalize_learned_host tests ----

    #[test]
    fn normalize_host_lowercases_and_trims() {
        assert_eq!(normalize_learned_host("  Example.COM  "), Some("example.com".to_string()));
    }

    #[test]
    fn normalize_host_strips_trailing_dots() {
        assert_eq!(normalize_learned_host("example.com."), Some("example.com".to_string()));
    }

    #[test]
    fn normalize_host_rejects_empty() {
        assert_eq!(normalize_learned_host(""), None);
        assert_eq!(normalize_learned_host("   "), None);
    }

    #[test]
    fn normalize_host_rejects_ipv4() {
        assert_eq!(normalize_learned_host("192.168.1.1"), None);
        assert_eq!(normalize_learned_host("127.0.0.1"), None);
    }

    #[test]
    fn normalize_host_rejects_ipv6() {
        assert_eq!(normalize_learned_host("::1"), None);
        assert_eq!(normalize_learned_host("fe80::1"), None);
    }

    #[test]
    fn normalize_host_rejects_only_dots() {
        assert_eq!(normalize_learned_host("."), None);
        assert_eq!(normalize_learned_host("..."), None);
    }

    #[test]
    fn normalize_rejects_system_telemetry_hosts() {
        assert!(normalize_learned_host("metrics5.data.hicloud.com").is_none());
        assert!(normalize_learned_host("socialuserlocation.googleapis.com").is_none());
        assert!(normalize_learned_host("mtalk.google.com").is_none());
        assert!(normalize_learned_host("weather-drru.music.dbankcloud.ru").is_none());
        assert!(normalize_learned_host("connectivitycheck.gstatic.com").is_none());
    }

    #[test]
    fn normalize_allows_real_user_domains() {
        assert_eq!(normalize_learned_host("www.youtube.com"), Some("www.youtube.com".to_string()));
        assert_eq!(normalize_learned_host("discord.com"), Some("discord.com".to_string()));
        assert_eq!(normalize_learned_host("meduza.io"), Some("meduza.io".to_string()));
        assert_eq!(normalize_learned_host("signal.org"), Some("signal.org".to_string()));
    }

    // ---- host_has_active_penalty tests ----

    #[test]
    fn penalty_active_when_expiry_in_future() {
        let mut record = LearnedHostRecord::default();
        record.group_stats.insert(0, LearnedGroupStats { penalty_until_ms: 1000, ..Default::default() });
        assert!(host_has_active_penalty(&record, 500));
    }

    #[test]
    fn penalty_expired_when_expiry_equals_now() {
        let mut record = LearnedHostRecord::default();
        record.group_stats.insert(0, LearnedGroupStats { penalty_until_ms: 1000, ..Default::default() });
        assert!(!host_has_active_penalty(&record, 1000));
    }

    #[test]
    fn penalty_expired_when_expiry_in_past() {
        let mut record = LearnedHostRecord::default();
        record.group_stats.insert(0, LearnedGroupStats { penalty_until_ms: 500, ..Default::default() });
        assert!(!host_has_active_penalty(&record, 1000));
    }

    #[test]
    fn no_penalty_when_group_stats_empty() {
        let record = LearnedHostRecord::default();
        assert!(!host_has_active_penalty(&record, 1000));
    }

    #[test]
    fn penalty_active_when_any_group_has_future_expiry() {
        let mut record = LearnedHostRecord::default();
        record.group_stats.insert(0, LearnedGroupStats { penalty_until_ms: 100, ..Default::default() });
        record.group_stats.insert(1, LearnedGroupStats { penalty_until_ms: 2000, ..Default::default() });
        assert!(host_has_active_penalty(&record, 500));
    }

    // ---- persist debounce tests ----

    #[test]
    fn persist_debounce_skips_rapid_second_write() {
        let config = autolearn_config(1, 32);
        let store_path = config.host_autolearn.store_path.clone().expect("store path");
        let dest = sample_dest(443);
        let mut policy = RuntimePolicy::load(&config);

        // First note_host_success writes the store (last_persist_at_ms starts at 0).
        policy
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 0, attempted_mask: 0 },
                Some("first.example"),
            )
            .expect("first success persists");
        assert!(std::path::Path::new(&store_path).exists(), "store file must exist after first write");

        // Remove the file so we can detect whether the next call writes again.
        std::fs::remove_file(&store_path).expect("remove store file");

        // Second call is within the debounce window -- file should NOT be recreated.
        policy.note_host_success(&config, "second.example", 0);
        assert!(!std::path::Path::new(&store_path).exists(), "store file must not be recreated within debounce window");

        // flush_host_store bypasses the debounce and writes unconditionally.
        policy.flush_host_store(&config);
        assert!(std::path::Path::new(&store_path).exists(), "store file must exist after flush");
    }
}
