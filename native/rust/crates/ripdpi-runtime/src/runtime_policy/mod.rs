mod autolearn;
mod cache;
mod matching;
mod selection;
mod types;

#[cfg(test)]
mod test_support;

use std::collections::{BTreeMap, VecDeque};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::LazyLock;
use std::time::{SystemTime, UNIX_EPOCH};

use types::{CacheRecord, GroupPolicy, LearnedHostRecord};

pub use types::{
    ConnectionRoute, ExtractedHost, HostAutolearnEvent, HostSource, RetrySelectionPenalty, RouteAdvance,
    TransportProtocol,
};

pub(crate) use matching::{extract_host, extract_host_info, group_requires_payload, route_matches_payload};

const HOST_AUTOLEARN_STORE_VERSION: u32 = 2;
const DEFAULT_NETWORK_SCOPE_KEY: &str = "default";

static EMPTY_LEARNED_HOSTS: LazyLock<BTreeMap<String, LearnedHostRecord>> = LazyLock::new(BTreeMap::new);

#[derive(Debug, Default)]
pub struct RuntimePolicy {
    records: Vec<CacheRecord>,
    groups: Vec<GroupPolicy>,
    order: Vec<usize>,
    learned_hosts_by_scope: BTreeMap<String, BTreeMap<String, LearnedHostRecord>>,
    autolearn_events: VecDeque<HostAutolearnEvent>,
}

pub(super) fn now_unix() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as i64
}

pub(super) fn now_millis() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

pub(super) fn next_temp_file_nonce() -> u64 {
    static TEMP_FILE_NONCE: AtomicU64 = AtomicU64::new(0);
    let timestamp = now_millis() << 16;
    let sequence = TEMP_FILE_NONCE.fetch_add(1, Ordering::Relaxed) & 0xFFFF;
    timestamp | sequence
}

#[cfg(test)]
mod tests {
    use std::env;
    use std::fs;

    use ripdpi_config::DesyncGroup;

    use super::test_support::{autolearn_config, sample_dest};
    use super::{ConnectionRoute, RouteAdvance, RuntimePolicy, TransportProtocol};

    #[test]
    fn facade_select_advance_and_note_success_preserves_flow() {
        let mut config = autolearn_config(2, 16);
        let mut second = DesyncGroup::new(1);
        second.matches.detect = ripdpi_config::DETECT_RECONN;
        config.groups[1] = second;
        let dest = sample_dest(443);
        let mut policy = RuntimePolicy::load(&config);

        let initial =
            policy.select_initial(dest, None, None, true, TransportProtocol::Tcp, &config).expect("initial route");
        assert_eq!(initial.group_index, 0);

        let next = policy
            .advance_route(
                &config,
                &initial,
                RouteAdvance {
                    dest,
                    payload: None,
                    transport: TransportProtocol::Tcp,
                    trigger: ripdpi_config::DETECT_RECONN,
                    can_reconnect: true,
                    host: Some("example.org".to_string()),
                    penalize_strategy_failure: true,
                    retry_penalties: None,
                },
            )
            .expect("advance route")
            .expect("next route");
        assert_eq!(next.group_index, 1);

        policy.note_route_success(&config, dest, &next, Some("example.org")).expect("note success");

        let reselected = policy
            .select_initial(dest, None, Some("example.org"), true, TransportProtocol::Tcp, &config)
            .expect("reselected route");
        assert_eq!(reselected.group_index, 1);
    }

    #[test]
    fn facade_reload_preserves_cache_and_autolearn_state() {
        let mut config = autolearn_config(2, 16);
        let cache_path = env::temp_dir().join(format!("ripdpi-runtime-cache-{}.txt", super::next_temp_file_nonce()));
        config.groups[1].policy.cache_file = Some(cache_path.to_string_lossy().into_owned());
        let dest = sample_dest(443);
        let mut policy = RuntimePolicy::load(&config);
        let route = ConnectionRoute { group_index: 1, attempted_mask: 0 };

        policy.note_route_success(&config, dest, &route, Some("docs.example.test")).expect("persist success");

        let mut reloaded = RuntimePolicy::load(&config);
        let cached = reloaded.lookup_and_prune(&config, dest).expect("cached route");
        assert_eq!(cached.group_index, 1);
        assert!(reloaded.learned_hosts(&config).contains_key("docs.example.test"));
        let _ = fs::remove_file(cache_path);
    }
}
