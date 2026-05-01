use super::ResolverPool;
use crate::types::{EncryptedDnsError, EncryptedDnsExchangeSuccess};

impl ResolverPool {
    /// Tries each resolver in health-ranked order, returning the first successful response.
    pub async fn exchange(&self, query: &[u8]) -> Result<EncryptedDnsExchangeSuccess, EncryptedDnsError> {
        let order = self.try_order();
        if order.is_empty() {
            return Err(EncryptedDnsError::InvalidEndpoint("resolver pool is empty".to_string()));
        }

        let mut last_error = EncryptedDnsError::InvalidEndpoint("no resolvers tried".to_string());
        for idx in order {
            match self.inner.resolvers[idx].exchange_with_metadata(query).await {
                Ok(success) => {
                    self.record_success(idx, &success);
                    return Ok(success);
                }
                Err(err) => {
                    self.record_failure(idx, &err);
                    last_error = err;
                }
            }
        }
        Err(last_error)
    }

    /// Blocking variant of `exchange`.
    pub fn exchange_blocking(&self, query: &[u8]) -> Result<EncryptedDnsExchangeSuccess, EncryptedDnsError> {
        let order = self.try_order();
        if order.is_empty() {
            return Err(EncryptedDnsError::InvalidEndpoint("resolver pool is empty".to_string()));
        }

        let mut last_error = EncryptedDnsError::InvalidEndpoint("no resolvers tried".to_string());
        for idx in order {
            match self.inner.resolvers[idx].exchange_blocking_with_metadata(query) {
                Ok(success) => {
                    self.record_success(idx, &success);
                    return Ok(success);
                }
                Err(err) => {
                    self.record_failure(idx, &err);
                    last_error = err;
                }
            }
        }
        Err(last_error)
    }
}
