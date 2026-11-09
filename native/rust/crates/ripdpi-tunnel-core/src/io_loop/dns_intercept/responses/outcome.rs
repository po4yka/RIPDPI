use ripdpi_dns_resolver::EncryptedDnsExchangeSuccess;

use crate::Stats;
use crate::dns_cache::DnsRewriteResult;
use crate::stats::SplitDnsDecisionKind;

// The resolver counters (queries, failures, last error, endpoint, latency) describe the
// encrypted resolver only: the encrypted-DNS failover reads them, so a direct (underlay)
// answer must not move them. Direct outcomes go to the direct and split-DNS counters.

pub(super) fn record_success(
    stats: &Stats,
    direct: bool,
    result: &DnsRewriteResult,
    upstream: &EncryptedDnsExchangeSuccess,
) {
    if !direct {
        stats.record_dns_success(
            &result.host,
            result.cache_hits,
            result.cache_misses,
            Some(&upstream.endpoint_label),
            Some(upstream.latency_ms),
        );
    }
}

pub(super) fn record_failure(stats: &Stats, direct: bool, host: Option<&str>, error: &str, endpoint: Option<&str>) {
    if direct {
        stats.record_split_dns_decision(SplitDnsDecisionKind::DirectFailClosed, Some("direct_transport_failed"));
    } else {
        stats.record_dns_failure(host, error, endpoint);
    }
}
