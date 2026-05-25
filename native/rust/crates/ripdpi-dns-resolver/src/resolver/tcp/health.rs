use std::net::IpAddr;
use std::time::Duration;

use super::super::state::ResolverInner;

pub(super) fn ranked_bootstrap_ips(inner: &ResolverInner) -> Vec<IpAddr> {
    if let Some(health) = &inner.health {
        health.rank_bootstrap_ips_in_scope(&inner.network_scope, &inner.endpoint.bootstrap_ips)
    } else {
        inner.endpoint.bootstrap_ips.clone()
    }
}

pub(super) fn record_bootstrap_outcome(inner: &ResolverInner, ip: IpAddr, success: bool, latency: Duration) {
    if let Some(health) = &inner.health {
        let latency_ms = latency.as_millis().try_into().unwrap_or(u64::MAX);
        health.record_bootstrap_outcome_in_scope(&inner.network_scope, ip, success, latency_ms);
    }
}

pub(super) fn record_bootstrap_timeout(inner: &ResolverInner, ip: IpAddr) {
    if let Some(health) = &inner.health {
        let latency_ms = inner.timeout.as_millis().try_into().unwrap_or(u64::MAX);
        health.record_bootstrap_outcome_in_scope(&inner.network_scope, ip, false, latency_ms);
    }
}
