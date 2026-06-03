use std::collections::BTreeMap;
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use crate::transport::{TransportConfig, relay_udp_direct, relay_udp_via_socks5, resolve_first_socket_addr};
use crate::util::{now_ms, ranged_probe_delay};

use ripdpi_ech_dns::{DNS_RECORD_TYPE_A, build_dns_query_with_type, parse_dns_response};

const UDP_DNS_ATTEMPTS: usize = 3;
const UDP_DNS_RETRY_JITTER_MIN_MS: u64 = 20;
const UDP_DNS_RETRY_JITTER_MAX_MS: u64 = 60;
const UDP_DNS_CACHE_TTL: Duration = Duration::from_secs(30);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UdpDnsResolution {
    pub result: Result<Vec<String>, String>,
    pub raw_response: Option<Vec<u8>>,
    pub latency_ms: u128,
    pub attempt_count: usize,
    pub success_count: usize,
    pub error_kind: Option<String>,
    pub retry_recovered: bool,
    pub cache_hit: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct UdpDnsCacheKey {
    domain: String,
    server: String,
    transport: String,
}

#[derive(Debug, Clone)]
struct UdpDnsCacheEntry {
    captured_at: Instant,
    resolution: UdpDnsResolution,
}

static UDP_DNS_CACHE: OnceLock<Mutex<BTreeMap<UdpDnsCacheKey, UdpDnsCacheEntry>>> = OnceLock::new();

/// Resolve a domain via plain UDP DNS, returning both the parsed IP addresses
/// and the raw response bytes for protocol-level tampering analysis.
pub fn resolve_via_udp_with_raw(
    domain: &str,
    server: &str,
    transport: &TransportConfig,
) -> (Result<Vec<String>, String>, Option<Vec<u8>>) {
    let resolution = resolve_via_udp_with_observations(domain, server, transport);
    (resolution.result, resolution.raw_response)
}

/// Resolve a domain via plain UDP DNS with retry metadata for diagnostics.
pub fn resolve_via_udp_with_observations(domain: &str, server: &str, transport: &TransportConfig) -> UdpDnsResolution {
    let cache_key = UdpDnsCacheKey {
        domain: domain.to_ascii_lowercase(),
        server: server.to_string(),
        transport: transport_cache_key(transport),
    };
    if let Some(cached) = cached_udp_dns_resolution(&cache_key) {
        return cached;
    }
    let started = Instant::now();
    let mut last_error = None;
    let mut attempt_count = 0;
    let mut had_retryable_error = false;

    for attempt in 1..=UDP_DNS_ATTEMPTS {
        attempt_count = attempt;
        let query_id = ((now_ms() & 0xffff) as u16).max(1);
        let packet = match build_dns_query_with_type(domain, query_id, DNS_RECORD_TYPE_A) {
            Ok(pkt) => pkt,
            Err(err) => {
                let resolution = UdpDnsResolution {
                    result: Err(err.clone()),
                    raw_response: None,
                    latency_ms: started.elapsed().as_millis(),
                    attempt_count,
                    success_count: 0,
                    error_kind: Some(classify_udp_dns_error(&err).to_string()),
                    retry_recovered: false,
                    cache_hit: false,
                };
                cache_udp_dns_resolution(cache_key, &resolution);
                return resolution;
            }
        };
        match execute_udp_query(server, transport, &packet, query_id) {
            Ok((addresses, response)) => {
                let error_kind = last_error.as_ref().map(|(_, kind): &(String, String)| kind.clone());
                let resolution = UdpDnsResolution {
                    result: Ok(addresses),
                    raw_response: Some(response),
                    latency_ms: started.elapsed().as_millis(),
                    attempt_count,
                    success_count: 1,
                    error_kind,
                    retry_recovered: had_retryable_error,
                    cache_hit: false,
                };
                cache_udp_dns_resolution(cache_key, &resolution);
                return resolution;
            }
            Err(err) => {
                let kind = classify_udp_dns_error(&err);
                let retryable = is_retryable_udp_dns_error(kind);
                had_retryable_error |= retryable;
                last_error = Some((err, kind.to_string()));
                if !retryable || attempt == UDP_DNS_ATTEMPTS {
                    break;
                }
                thread::sleep(udp_retry_jitter(domain, server, attempt));
            }
        }
    }

    let (err, kind) = last_error.unwrap_or_else(|| ("udp_dns_unavailable".to_string(), "unknown".to_string()));
    let resolution = UdpDnsResolution {
        result: Err(err),
        raw_response: None,
        latency_ms: started.elapsed().as_millis(),
        attempt_count,
        success_count: 0,
        error_kind: Some(kind),
        retry_recovered: false,
        cache_hit: false,
    };
    cache_udp_dns_resolution(cache_key, &resolution);
    resolution
}

fn execute_udp_query(
    server: &str,
    transport: &TransportConfig,
    packet: &[u8],
    query_id: u16,
) -> Result<(Vec<String>, Vec<u8>), String> {
    let raw = match transport {
        TransportConfig::Direct { .. } => {
            let server_addr = resolve_first_socket_addr(server)?;
            relay_udp_direct(server_addr, packet)
        }
        TransportConfig::Socks5 { host, port } => {
            let server_addr = resolve_first_socket_addr(server)?;
            relay_udp_via_socks5(host, *port, server_addr, packet)
        }
    }?;
    let (response, _local_addr) = raw;
    let parsed = parse_dns_response(&response, query_id)?;
    Ok((parsed, response))
}

pub fn classify_udp_dns_error(error: &str) -> &'static str {
    let normalized = error.to_ascii_lowercase();
    if normalized.contains("timed out") || normalized.contains("timeout") {
        return "timeout";
    }
    if normalized.contains("try again")
        || normalized.contains("would block")
        || normalized.contains("would_block")
        || normalized.contains("os error 11")
    {
        return "would_block";
    }
    if normalized.contains("connection refused") || normalized.contains("os error 111") {
        return "refused";
    }
    if normalized.contains("network is unreachable") || normalized.contains("no route") {
        return "unreachable";
    }
    if normalized.contains("nxdomain") {
        return "nxdomain";
    }
    "other"
}

pub fn is_retryable_udp_dns_error(kind: &str) -> bool {
    matches!(kind, "timeout" | "would_block")
}

fn udp_retry_jitter(domain: &str, server: &str, attempt: usize) -> Duration {
    let delay_ms = ranged_probe_delay(
        now_ms(),
        domain,
        &format!("{server}:{attempt}"),
        UDP_DNS_RETRY_JITTER_MIN_MS,
        UDP_DNS_RETRY_JITTER_MAX_MS,
    );
    Duration::from_millis(delay_ms)
}

fn cached_udp_dns_resolution(cache_key: &UdpDnsCacheKey) -> Option<UdpDnsResolution> {
    let cache = UDP_DNS_CACHE.get_or_init(|| Mutex::new(BTreeMap::new()));
    let mut guard = cache.lock().ok()?;
    let entry = guard.get(cache_key)?;
    if entry.captured_at.elapsed() <= UDP_DNS_CACHE_TTL {
        let mut resolution = entry.resolution.clone();
        resolution.cache_hit = true;
        return Some(resolution);
    }
    guard.remove(cache_key);
    None
}

fn cache_udp_dns_resolution(cache_key: UdpDnsCacheKey, resolution: &UdpDnsResolution) {
    let cache = UDP_DNS_CACHE.get_or_init(|| Mutex::new(BTreeMap::new()));
    let Ok(mut guard) = cache.lock() else {
        return;
    };
    guard.insert(cache_key, UdpDnsCacheEntry { captured_at: Instant::now(), resolution: resolution.clone() });
}

fn transport_cache_key(transport: &TransportConfig) -> String {
    match transport {
        TransportConfig::Direct { .. } => "direct".to_string(),
        TransportConfig::Socks5 { host, port } => format!("socks5:{host}:{port}"),
    }
}

#[cfg(test)]
mod tests {
    use super::{classify_udp_dns_error, is_retryable_udp_dns_error};

    #[test]
    fn udp_dns_error_classifier_distinguishes_timeout_and_would_block() {
        assert_eq!(classify_udp_dns_error("udp_recv_timeout"), "timeout");
        assert_eq!(classify_udp_dns_error("udp_recv_would_block"), "would_block");
        assert_eq!(classify_udp_dns_error("Try again (os error 11)"), "would_block");
    }

    #[test]
    fn udp_dns_retry_policy_is_limited_to_timeout_like_errors() {
        assert!(is_retryable_udp_dns_error("timeout"));
        assert!(is_retryable_udp_dns_error("would_block"));
        assert!(!is_retryable_udp_dns_error("unreachable"));
        assert!(!is_retryable_udp_dns_error("refused"));
    }
}
