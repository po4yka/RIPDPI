use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::net::IpAddr;
use std::path::Path;

use ripdpi_config::RuntimeConfig;
use ring::digest;

use super::types::{LearnedHostRecord, LearnedHostStore, LearnedNetworkScopeStore, LoadLearnedHostStoreError};
use super::{
    next_temp_file_nonce, now_millis, HostAutolearnEvent, RuntimePolicy, DEFAULT_NETWORK_SCOPE_KEY,
    EMPTY_LEARNED_HOSTS, HOST_AUTOLEARN_STORE_VERSION,
};

impl RuntimePolicy {
    pub fn drain_autolearn_events(&mut self) -> Vec<HostAutolearnEvent> {
        self.autolearn_events.drain(..).collect()
    }

    pub fn autolearn_state(&self, config: &RuntimeConfig) -> (bool, usize, usize) {
        let now_ms = now_millis();
        let penalized =
            self.learned_hosts(config).values().filter(|record| host_has_active_penalty(record, now_ms)).count();
        (config.host_autolearn.enabled, self.learned_hosts(config).len(), penalized)
    }

    pub(super) fn learned_hosts(&self, config: &RuntimeConfig) -> &BTreeMap<String, LearnedHostRecord> {
        self.learned_hosts_by_scope.get(network_scope_key(config)).unwrap_or(&EMPTY_LEARNED_HOSTS)
    }

    pub(super) fn learned_hosts_mut(&mut self, config: &RuntimeConfig) -> &mut BTreeMap<String, LearnedHostRecord> {
        self.learned_hosts_by_scope.entry(network_scope_key(config).to_owned()).or_default()
    }

    pub(crate) fn note_host_failure(
        &mut self,
        config: &RuntimeConfig,
        host: &str,
        group_index: usize,
    ) -> io::Result<()> {
        if !config.host_autolearn.enabled {
            return Ok(());
        }
        let Some(host) = normalize_learned_host(host) else {
            return Ok(());
        };
        let now_ms = now_millis();
        let record = self.learned_hosts_mut(config).entry(host.clone()).or_default();
        let stats = record.group_stats.entry(group_index).or_default();
        stats.failure_count = stats.failure_count.saturating_add(1);
        stats.last_failure_at_ms = now_ms;
        stats.penalty_until_ms = now_ms.saturating_add(config.host_autolearn.penalty_ttl_secs.max(1) as u64 * 1_000);
        record.updated_at_ms = now_ms;
        ensure_host_order(record, group_index);
        self.enforce_autolearn_limit(config, now_ms);
        self.persist_host_store(config)?;
        self.autolearn_events.push_back(HostAutolearnEvent {
            action: "group_penalized",
            host: Some(host),
            group_index: Some(group_index),
        });
        Ok(())
    }

    pub(crate) fn note_host_success(
        &mut self,
        config: &RuntimeConfig,
        host: &str,
        group_index: usize,
    ) -> io::Result<()> {
        if !config.host_autolearn.enabled {
            return Ok(());
        }
        let now_ms = now_millis();
        let record = self.learned_hosts_mut(config).entry(host.to_owned()).or_default();
        let stats = record.group_stats.entry(group_index).or_default();
        stats.success_count = stats.success_count.saturating_add(1);
        stats.last_success_at_ms = now_ms;
        stats.penalty_until_ms = 0;
        record.updated_at_ms = now_ms;
        promote_group(record, group_index);
        self.enforce_autolearn_limit(config, now_ms);
        self.persist_host_store(config)?;
        self.autolearn_events.push_back(HostAutolearnEvent {
            action: "host_promoted",
            host: Some(host.to_owned()),
            group_index: Some(group_index),
        });
        Ok(())
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

    fn persist_host_store(&self, config: &RuntimeConfig) -> io::Result<()> {
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
    Ok(store
        .scopes
        .into_iter()
        .map(|(scope, scope_store)| {
            let hosts = scope_store
                .hosts
                .into_iter()
                .filter_map(|(host, mut record)| {
                    let normalized_host = normalize_learned_host(&host)?;
                    record.preferred_groups.retain(|group_index| *group_index < config.groups.len());
                    record.group_stats.retain(|group_index, _| *group_index < config.groups.len());
                    (!record.preferred_groups.is_empty() || !record.group_stats.is_empty())
                        .then_some((normalized_host, record))
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

pub(super) fn normalize_learned_host(host: &str) -> Option<String> {
    let trimmed = host.trim().trim_end_matches('.');
    if trimmed.is_empty() {
        return None;
    }
    let normalized = trimmed.to_ascii_lowercase();
    if normalized.parse::<IpAddr>().is_ok() {
        return None;
    }
    Some(normalized)
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
    use serde_json::json;

    use crate::runtime_policy::test_support::{autolearn_config, sample_dest};
    use crate::runtime_policy::types::LearnedGroupStats;
    use crate::runtime_policy::{ConnectionRoute, RuntimePolicy};

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
        policy.note_host_failure(&config, "example.org", 1).expect("penalize preferred group");

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
    fn legacy_v1_host_autolearn_store_is_invalidated() {
        let config = autolearn_config(1, 32);
        let path = config.host_autolearn.store_path.clone().expect("store path");
        let payload = json!({
            "version": 1,
            "fingerprint": config_fingerprint(&config),
            "hosts": {
                "example.org": {
                    "preferred_groups": [0],
                    "group_stats": {},
                    "updated_at_ms": 1
                }
            }
        });
        fs::write(&path, serde_json::to_vec_pretty(&payload).expect("serialize legacy payload"))
            .expect("write legacy store");

        let mut policy = RuntimePolicy::load(&config);

        assert!(policy.learned_hosts(&config).is_empty());
        assert!(policy.drain_autolearn_events().iter().any(|event| event.action == "store_reset"));
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
}
