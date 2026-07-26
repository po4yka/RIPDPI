use tracing::debug;

use crate::dns_cache::DnsCache;

pub(super) fn servfail_payload(dns_cache: &DnsCache, query: &[u8], reason: &str) -> Option<Vec<u8>> {
    match dns_cache.servfail_response(query) {
        Ok(servfail) => Some(servfail),
        Err(servfail_err) => {
            debug!("failed to synthesize SERVFAIL after {reason}: {servfail_err}");
            None
        }
    }
}
