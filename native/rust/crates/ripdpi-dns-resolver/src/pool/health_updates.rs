use std::time::Instant;

use super::fallback_order;
use super::{DOQ_DEMOTION_TTL, ResolverPool};
use crate::types::{DoqErrorKind, EncryptedDnsError, EncryptedDnsExchangeSuccess, EncryptedDnsProtocol};

/// Whether a failed DoQ attempt shows that the server cannot be reached: a missing
/// bootstrap address or a connection failure. Local setup and stream failures do not demote.
fn is_doq_unreachable(error: &EncryptedDnsError) -> bool {
    matches!(
        error,
        EncryptedDnsError::MissingBootstrapIps
            | EncryptedDnsError::Doq {
                kind: DoqErrorKind::Connect | DoqErrorKind::ConnectTimeout | DoqErrorKind::Tls | DoqErrorKind::Alpn,
                ..
            }
    )
}

pub(super) fn elapsed_ms(started: Instant) -> u64 {
    started.elapsed().as_millis().try_into().unwrap_or(u64::MAX)
}

impl ResolverPool {
    pub(super) fn record_success(&self, idx: usize, success: &EncryptedDnsExchangeSuccess) {
        let key = &self.inner.keys[idx];
        self.inner.health.record_endpoint_outcome_in_scope(&self.inner.network_scope, key, true, success.latency_ms);
        fallback_order::remember_success(&self.inner, key);
    }

    /// Records a failed attempt with its real elapsed time. A DoQ resolver that failed to
    /// reach the server is demoted for `DOQ_DEMOTION_TTL`.
    pub(super) fn record_failure(&self, idx: usize, error: &EncryptedDnsError, latency_ms: u64) {
        self.record_attempt_failure(idx, latency_ms);
        if self.inner.resolvers[idx].endpoint().protocol == EncryptedDnsProtocol::Doq
            && is_doq_unreachable(error)
            && let Ok(mut map) = self.inner.doq_demoted_until.lock()
        {
            map.insert(self.inner.keys[idx].clone(), Instant::now() + DOQ_DEMOTION_TTL);
        }
    }

    /// Records a failure without judging the cause (a dropped attempt).
    pub(super) fn record_attempt_failure(&self, idx: usize, latency_ms: u64) {
        self.inner.health.record_endpoint_outcome_in_scope(
            &self.inner.network_scope,
            &self.inner.keys[idx],
            false,
            latency_ms,
        );
    }
}
