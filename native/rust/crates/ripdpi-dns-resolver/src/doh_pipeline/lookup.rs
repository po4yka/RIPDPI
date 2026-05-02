use hickory_proto::op::Message;

use crate::resolver::EncryptedDnsResolver;
use crate::transport::build_dns_query;
use crate::types::EncryptedDnsError;

use super::{DohBatchLookup, DohBatchRecordResponse, DohBatchRecordType, DohResolverPipeline, DohResolverRole};

impl DohResolverPipeline {
    pub async fn resolve(&self, domain: &str) -> Result<DohBatchLookup, EncryptedDnsError> {
        if let Some(cached) = self.inner.cache.fresh_lookup(domain) {
            return Ok(cached);
        }

        if let Ok(lookup) = lookup_with_resolver(domain, DohResolverRole::Primary, &self.inner.primary).await {
            self.inner.cache.store_lookup(domain, &lookup);
            return Ok(lookup);
        }

        match lookup_with_resolver(domain, DohResolverRole::Secondary, &self.inner.secondary).await {
            Ok(lookup) => {
                self.inner.cache.store_lookup(domain, &lookup);
                Ok(lookup)
            }
            Err(err) => Err(err),
        }
    }

    pub fn resolve_blocking(&self, domain: &str) -> Result<DohBatchLookup, EncryptedDnsError> {
        if let Some(cached) = self.inner.cache.fresh_lookup(domain) {
            return Ok(cached);
        }

        if let Ok(lookup) = lookup_with_resolver_blocking(domain, DohResolverRole::Primary, &self.inner.primary) {
            self.inner.cache.store_lookup(domain, &lookup);
            return Ok(lookup);
        }

        match lookup_with_resolver_blocking(domain, DohResolverRole::Secondary, &self.inner.secondary) {
            Ok(lookup) => {
                self.inner.cache.store_lookup(domain, &lookup);
                Ok(lookup)
            }
            Err(err) => Err(err),
        }
    }
}

async fn lookup_with_resolver(
    domain: &str,
    resolver_role: DohResolverRole,
    resolver: &EncryptedDnsResolver,
) -> Result<DohBatchLookup, EncryptedDnsError> {
    let mut endpoint_label: Option<String> = None;
    let mut records = Vec::with_capacity(DohBatchRecordType::ALL.len());

    for record_type in DohBatchRecordType::ALL {
        let query = build_dns_query(domain, record_type.record_type())?;
        let success = resolver.exchange_with_metadata(&query).await?;
        endpoint_label.get_or_insert(success.endpoint_label.clone());
        records.push(DohBatchRecordResponse {
            record_type,
            min_ttl_secs: min_ttl_secs(&success.response_bytes),
            response_bytes: success.response_bytes,
        });
    }

    Ok(batch_lookup(domain, resolver_role, resolver, endpoint_label, records))
}

fn lookup_with_resolver_blocking(
    domain: &str,
    resolver_role: DohResolverRole,
    resolver: &EncryptedDnsResolver,
) -> Result<DohBatchLookup, EncryptedDnsError> {
    let mut endpoint_label: Option<String> = None;
    let mut records = Vec::with_capacity(DohBatchRecordType::ALL.len());

    for record_type in DohBatchRecordType::ALL {
        let query = build_dns_query(domain, record_type.record_type())?;
        let success = resolver.exchange_blocking_with_metadata(&query)?;
        endpoint_label.get_or_insert(success.endpoint_label.clone());
        records.push(DohBatchRecordResponse {
            record_type,
            min_ttl_secs: min_ttl_secs(&success.response_bytes),
            response_bytes: success.response_bytes,
        });
    }

    Ok(batch_lookup(domain, resolver_role, resolver, endpoint_label, records))
}

fn batch_lookup(
    domain: &str,
    resolver_role: DohResolverRole,
    resolver: &EncryptedDnsResolver,
    endpoint_label: Option<String>,
    records: Vec<DohBatchRecordResponse>,
) -> DohBatchLookup {
    DohBatchLookup {
        domain: domain.to_string(),
        resolver_role,
        endpoint_label: endpoint_label.unwrap_or_else(|| resolver.endpoint_label()),
        cache_ttl_secs: records.iter().filter_map(|record| record.min_ttl_secs).filter(|ttl| *ttl > 0).min(),
        records,
    }
}

fn min_ttl_secs(response_bytes: &[u8]) -> Option<u32> {
    let message = Message::from_vec(response_bytes).ok()?;
    message
        .answers
        .iter()
        .chain(message.authorities.iter())
        .chain(message.additionals.iter())
        .map(|record| record.ttl)
        .min()
}
