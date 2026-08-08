use hickory_proto::op::Message;

use crate::resolver::EncryptedDnsResolver;
use crate::transport::{build_dns_query, extract_ip_answer_records};
use crate::types::EncryptedDnsError;

use super::{DohBatchLookup, DohBatchRecordResponse, DohBatchRecordType, DohResolverPipeline, DohResolverRole};

impl DohResolverPipeline {
    /// # Cancel safety
    ///
    /// cancel-safe: cancellation drops the current resolver exchange and any
    /// partial batch. The cache is updated only after a complete usable lookup.
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

/// # Cancel safety
///
/// cancel-safe: partial responses remain local and are discarded when the
/// in-flight exchange future is dropped; this function mutates no shared state.
async fn lookup_with_resolver(
    domain: &str,
    resolver_role: DohResolverRole,
    resolver: &EncryptedDnsResolver,
) -> Result<DohBatchLookup, EncryptedDnsError> {
    let mut endpoint_label: Option<String> = None;
    let mut records = Vec::with_capacity(DohBatchRecordType::ALL.len());

    let mut last_address_error = None;
    for record_type in DohBatchRecordType::ADDRESS {
        let result = match build_dns_query(domain, record_type.record_type()) {
            Ok(query) => resolver.exchange_with_metadata(&query).await,
            Err(error) => Err(error),
        };
        match result {
            Ok(success) => {
                endpoint_label.get_or_insert(success.endpoint_label.clone());
                records.push(DohBatchRecordResponse {
                    record_type,
                    min_ttl_secs: min_ttl_secs(&success.response_bytes),
                    response_bytes: success.response_bytes,
                });
            }
            Err(error) => last_address_error = Some(error),
        }
    }
    require_usable_address(&records, last_address_error)?;

    for record_type in DohBatchRecordType::OPTIONAL {
        let result = match build_dns_query(domain, record_type.record_type()) {
            Ok(query) => resolver.exchange_with_metadata(&query).await,
            Err(error) => Err(error),
        };
        if let Ok(success) = result {
            endpoint_label.get_or_insert(success.endpoint_label.clone());
            records.push(DohBatchRecordResponse {
                record_type,
                min_ttl_secs: min_ttl_secs(&success.response_bytes),
                response_bytes: success.response_bytes,
            });
        }
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

    let mut last_address_error = None;
    for record_type in DohBatchRecordType::ADDRESS {
        let result = build_dns_query(domain, record_type.record_type())
            .and_then(|query| resolver.exchange_blocking_with_metadata(&query));
        match result {
            Ok(success) => {
                endpoint_label.get_or_insert(success.endpoint_label.clone());
                records.push(DohBatchRecordResponse {
                    record_type,
                    min_ttl_secs: min_ttl_secs(&success.response_bytes),
                    response_bytes: success.response_bytes,
                });
            }
            Err(error) => last_address_error = Some(error),
        }
    }
    require_usable_address(&records, last_address_error)?;

    for record_type in DohBatchRecordType::OPTIONAL {
        let result = build_dns_query(domain, record_type.record_type())
            .and_then(|query| resolver.exchange_blocking_with_metadata(&query));
        if let Ok(success) = result {
            endpoint_label.get_or_insert(success.endpoint_label.clone());
            records.push(DohBatchRecordResponse {
                record_type,
                min_ttl_secs: min_ttl_secs(&success.response_bytes),
                response_bytes: success.response_bytes,
            });
        }
    }

    Ok(batch_lookup(domain, resolver_role, resolver, endpoint_label, records))
}

fn require_usable_address(
    records: &[DohBatchRecordResponse],
    last_address_error: Option<EncryptedDnsError>,
) -> Result<(), EncryptedDnsError> {
    let has_usable_address = records.iter().any(|record| {
        matches!(record.record_type, DohBatchRecordType::A | DohBatchRecordType::Aaaa)
            && extract_ip_answer_records(&record.response_bytes).is_ok_and(|answers| !answers.is_empty())
    });
    if has_usable_address { Ok(()) } else { Err(last_address_error.unwrap_or(EncryptedDnsError::NoAnswer)) }
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
