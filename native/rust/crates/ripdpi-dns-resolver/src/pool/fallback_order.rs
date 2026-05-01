use std::time::Instant;

use super::PoolInner;
use crate::types::ResolverNetworkScope;

pub(super) struct FallbackEntry {
    pub(super) last_success: Instant,
}

pub(super) fn promote_cached_success_if_cold(inner: &PoolInner, ranked: &mut Vec<usize>) {
    let Some(&rank_zero) = ranked.first() else {
        return;
    };
    if inner.health.observation_count_in_scope(&inner.network_scope, &inner.labels[rank_zero]) != 0 {
        return;
    }

    let Some(cached_idx) = most_recent_cached_success(inner) else {
        return;
    };
    if rank_zero != cached_idx {
        ranked.retain(|&i| i != cached_idx);
        ranked.insert(0, cached_idx);
    }
}

pub(super) fn remember_success(inner: &PoolInner, label: &str) {
    if let Ok(mut cache) = inner.fallback_cache.lock() {
        cache.put(fallback_key(&inner.network_scope, label), FallbackEntry { last_success: Instant::now() });
    }
}

fn most_recent_cached_success(inner: &PoolInner) -> Option<usize> {
    let Ok(cache) = inner.fallback_cache.lock() else {
        return None;
    };

    inner
        .labels
        .iter()
        .enumerate()
        .filter_map(|(i, label)| {
            cache.peek(&fallback_key(&inner.network_scope, label)).map(|entry| (i, entry.last_success))
        })
        .max_by_key(|(_, timestamp)| *timestamp)
        .map(|(i, _)| i)
}

pub(super) fn fallback_key(scope: &ResolverNetworkScope, label: &str) -> String {
    format!("{}::{label}", scope.as_str())
}
