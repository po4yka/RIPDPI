use std::mem::DropGuard;
use std::time::Instant;

use super::ResolverPool;
use super::health_updates::elapsed_ms;
use crate::types::{EncryptedDnsError, EncryptedDnsExchangeSuccess};

impl ResolverPool {
    /// Tries each resolver in health-ranked order, returning the first successful response.
    ///
    /// Callers bound the total time with `tokio::time::timeout`.
    ///
    /// # Cancel safety:
    /// cancel-safe: the only `.await` is the per-resolver `exchange_with_metadata` call (itself cancel-safe).
    /// If the future is dropped there, the guard records exactly one failure for that resolver (no DoQ
    /// demotion, because the cause is unknown). Attempts that already finished keep their recorded outcome.
    /// A completed attempt disarms the guard before it records, with no `.await` in between, so a drop never
    /// adds a second record. Dropping `exchange` for any reason, including shutdown or a lost `select!` race,
    /// therefore counts as a failure of the in-flight resolver.
    pub async fn exchange(&self, query: &[u8]) -> Result<EncryptedDnsExchangeSuccess, EncryptedDnsError> {
        let order = self.eligible_order()?;

        let mut last_error = EncryptedDnsError::InvalidEndpoint("no resolvers tried".to_string());
        for idx in order {
            let started = Instant::now();
            let guard = DropGuard::new((idx, started), |(idx, started)| {
                self.record_attempt_failure(idx, elapsed_ms(started));
            });
            let result = self.inner.resolvers[idx].exchange_with_metadata(query).await;
            DropGuard::dismiss(guard);
            match result {
                Ok(success) => {
                    self.record_success(idx, &success);
                    return Ok(success);
                }
                Err(err) => {
                    self.record_failure(idx, &err, elapsed_ms(started));
                    last_error = err;
                }
            }
        }
        Err(last_error)
    }

    /// Blocking variant of `exchange`.
    pub fn exchange_blocking(&self, query: &[u8]) -> Result<EncryptedDnsExchangeSuccess, EncryptedDnsError> {
        let order = self.eligible_order()?;

        let mut last_error = EncryptedDnsError::InvalidEndpoint("no resolvers tried".to_string());
        for idx in order {
            let started = Instant::now();
            match self.inner.resolvers[idx].exchange_blocking_with_metadata(query) {
                Ok(success) => {
                    self.record_success(idx, &success);
                    return Ok(success);
                }
                Err(err) => {
                    self.record_failure(idx, &err, elapsed_ms(started));
                    last_error = err;
                }
            }
        }
        Err(last_error)
    }

    fn eligible_order(&self) -> Result<Vec<usize>, EncryptedDnsError> {
        if self.inner.resolvers.is_empty() {
            return Err(EncryptedDnsError::InvalidEndpoint("resolver pool is empty".to_string()));
        }
        let order = self.try_order();
        if order.is_empty() {
            return Err(EncryptedDnsError::InvalidEndpoint("no eligible resolver in pool".to_string()));
        }
        Ok(order)
    }
}
