use std::sync::atomic::Ordering;

use super::fallback_order;
use super::{PoolInner, ResolverPool};

impl ResolverPool {
    pub(super) fn try_order(&self) -> Vec<usize> {
        let mut ranked = health_ranked_order(&self.inner);
        fallback_order::promote_cached_success_if_cold(&self.inner, &mut ranked);
        inject_round_robin_exploration(&self.inner, &mut ranked);
        ranked
    }
}

fn health_ranked_order(inner: &PoolInner) -> Vec<usize> {
    if inner.resolvers.is_empty() {
        return Vec::new();
    }

    let label_refs: Vec<&str> = inner.labels.iter().map(String::as_str).collect();
    inner.health.rank_indices_in_scope(&inner.network_scope, &label_refs)
}

fn inject_round_robin_exploration(inner: &PoolInner, ranked: &mut Vec<usize>) {
    let n = inner.resolvers.len();
    if n <= 1 {
        return;
    }

    let counter = inner.rotation_counter.fetch_add(1, Ordering::Relaxed);
    let rr = counter % n;
    let already_top2 = ranked.first().copied() == Some(rr) || ranked.get(1).copied() == Some(rr);
    if !already_top2 {
        ranked.retain(|&i| i != rr);
        ranked.insert(1.min(ranked.len()), rr);
    }
}
