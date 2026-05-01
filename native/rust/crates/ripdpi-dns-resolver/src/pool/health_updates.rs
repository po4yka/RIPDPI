use super::fallback_order;
use super::ResolverPool;
use crate::transport::DEFAULT_TIMEOUT;
use crate::types::{EncryptedDnsError, EncryptedDnsErrorKind, EncryptedDnsExchangeSuccess};

impl ResolverPool {
    pub(super) fn record_success(&self, idx: usize, success: &EncryptedDnsExchangeSuccess) {
        let label = &self.inner.labels[idx];
        self.inner.health.record_endpoint_outcome_in_scope(&self.inner.network_scope, label, true, success.latency_ms);
        fallback_order::remember_success(&self.inner, label);
    }

    pub(super) fn record_failure(&self, idx: usize, error: &EncryptedDnsError) {
        let label = &self.inner.labels[idx];
        if error.kind() == EncryptedDnsErrorKind::SniBlocked {
            self.inner.health.record_sni_blocked_in_scope(&self.inner.network_scope, label);
        } else {
            let timeout_ms = DEFAULT_TIMEOUT.as_millis().try_into().unwrap_or(u64::MAX);
            self.inner.health.record_endpoint_outcome_in_scope(&self.inner.network_scope, label, false, timeout_ms);
        }
    }
}
