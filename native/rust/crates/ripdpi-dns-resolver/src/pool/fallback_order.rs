use std::time::Instant;

use super::PoolInner;
use crate::types::ResolverNetworkScope;

pub(super) struct FallbackEntry {
    pub(super) last_success: Instant,
}

/// Moves the most recent cached success to the front on a cold start. Only an
/// index that is still in `ranked` (not filtered out) and not quarantined qualifies.
pub(super) fn promote_cached_success_if_cold(inner: &PoolInner, ranked: &mut Vec<usize>) {
    let Some(&rank_zero) = ranked.first() else {
        return;
    };
    if inner.health.observation_count_in_scope(&inner.network_scope, &inner.keys[rank_zero]) != 0 {
        return;
    }

    let Some(cached_idx) = most_recent_cached_success(inner, ranked) else {
        return;
    };
    if rank_zero != cached_idx {
        ranked.retain(|&i| i != cached_idx);
        ranked.insert(0, cached_idx);
    }
}

pub(super) fn remember_success(inner: &PoolInner, key: &str) {
    if let Ok(mut cache) = inner.fallback_cache.lock() {
        cache.put(fallback_key(&inner.network_scope, key), FallbackEntry { last_success: Instant::now() });
    }
}

fn most_recent_cached_success(inner: &PoolInner, ranked: &[usize]) -> Option<usize> {
    // Ask the health registry before taking the cache lock: never hold both.
    let eligible: Vec<usize> = ranked
        .iter()
        .copied()
        .filter(|&i| !inner.health.is_quarantined_in_scope(&inner.network_scope, &inner.keys[i]))
        .collect();
    let Ok(cache) = inner.fallback_cache.lock() else {
        return None;
    };

    eligible
        .into_iter()
        .filter_map(|i| {
            cache.peek(&fallback_key(&inner.network_scope, &inner.keys[i])).map(|entry| (i, entry.last_success))
        })
        .max_by_key(|(_, timestamp)| *timestamp)
        .map(|(i, _)| i)
}

pub(super) fn fallback_key(scope: &ResolverNetworkScope, key: &str) -> String {
    format!("{}::{key}", scope.as_str())
}
