use super::*;

use std::fs;

use crate::runtime_policy::test_support::{autolearn_config, sample_dest};
use crate::runtime_policy::types::LearnedGroupStats;
use crate::runtime_policy::{ConnectionRoute, DEFAULT_NETWORK_SCOPE_KEY, HOST_AUTOLEARN_STORE_VERSION, RuntimePolicy};
use serde_json::json;

#[test]
fn successful_fallback_promotes_final_group_for_host() {
    let config = autolearn_config(3, 32);
    let dest = sample_dest(443);
    let mut policy = RuntimePolicy::load(&config);

    policy
        .note_route_success(&config, dest, &ConnectionRoute { group_index: 0, attempted_mask: 0 }, Some("example.org"))
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
        .note_route_success(&config, dest, &ConnectionRoute { group_index: 1, attempted_mask: 0 }, Some("example.org"))
        .expect("learn preferred group");
    policy.note_host_failure(&config, "example.org", 1);

    let penalized_route = policy
        .select_initial(dest, None, Some("example.org"), true, crate::runtime_policy::TransportProtocol::Tcp, &config)
        .expect("fallback while penalized");
    assert_eq!(penalized_route.group_index, 0);

    policy
        .learned_hosts_mut(&config)
        .get_mut("example.org")
        .and_then(|record| record.group_stats.get_mut(&1))
        .expect("penalized stats")
        .penalty_until_ms = now_millis().saturating_sub(1);

    let recovered_route = policy
        .select_initial(dest, None, Some("example.org"), true, crate::runtime_policy::TransportProtocol::Tcp, &config)
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
        event.action == "host_blocked" && event.host.as_deref() == Some("example.org") && event.group_index.is_none()
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
        .note_route_success(&config, dest, &ConnectionRoute { group_index: 0, attempted_mask: 0 }, Some("example.org"))
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
    policy_a.flush_host_store(&config_a);

    let mut config_b = autolearn_config(1, 32);
    config_b.host_autolearn.store_path = Some(path);
    config_b.adaptive.network_scope_key = Some("scope-b".to_string());

    let mut policy_b = RuntimePolicy::load(&config_b);
    assert_eq!(policy_b.autolearn_state(&config_b).blocked_host_count, 0);
    policy_b.note_block_signal(&config_b, "beta.example", BlockSignal::TcpReset, None, true);
    policy_b.note_block_signal(&config_b, "beta.example", BlockSignal::TcpReset, None, true);
    policy_b.flush_host_store(&config_b);

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
    assert_eq!(normalize_learned_host("proton.me"), Some("proton.me".to_string()));
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
