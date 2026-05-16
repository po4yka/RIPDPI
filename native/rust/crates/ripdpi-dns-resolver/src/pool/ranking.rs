use std::sync::atomic::Ordering;

use super::fallback_order;
use super::{PoolInner, ResolverPool};
use crate::types::EncryptedDnsProtocol;

impl ResolverPool {
    pub(super) fn try_order(&self) -> Vec<usize> {
        let mut ranked = health_ranked_order(&self.inner);
        filter_demoted_doq_resolvers(&self.inner, &mut ranked);
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

/// Removes DoQ resolvers from the candidate order when the pool's network scope
/// has been demoted due to a live DoQ failure in this session.
fn filter_demoted_doq_resolvers(inner: &PoolInner, ranked: &mut Vec<usize>) {
    let suppressed = inner.doq_demoted_scopes.lock().map(|set| set.contains(&inner.network_scope)).unwrap_or(false);

    if suppressed {
        ranked.retain(|&idx| inner.resolvers[idx].endpoint().protocol != EncryptedDnsProtocol::Doq);
    }
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
