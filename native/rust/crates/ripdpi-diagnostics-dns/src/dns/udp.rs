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
const UDP_DNS_CACHE_MAX_ENTRIES: usize = 128;
const UDP_DNS_CACHE_MAX_OWNED_BYTES: usize = 1024 * 1024;

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
    owned_bytes: usize,
    last_access: u64,
}

struct UdpDnsCache {
    entries: BTreeMap<UdpDnsCacheKey, UdpDnsCacheEntry>,
    ttl: Duration,
    max_entries: usize,
    max_owned_bytes: usize,
    total_owned_bytes: usize,
    access_sequence: u64,
}

impl UdpDnsCache {
    fn new(ttl: Duration, max_entries: usize, max_owned_bytes: usize) -> Self {
        Self { entries: BTreeMap::new(), ttl, max_entries, max_owned_bytes, total_owned_bytes: 0, access_sequence: 0 }
    }

    fn get(&mut self, cache_key: &UdpDnsCacheKey, now: Instant) -> Option<UdpDnsResolution> {
        self.prune_expired(now);
        if !self.entries.contains_key(cache_key) {
            return None;
        }
        let last_access = self.next_access_sequence();
        let entry = self.entries.get_mut(cache_key)?;
        entry.last_access = last_access;
        let mut resolution = entry.resolution.clone();
        resolution.cache_hit = true;
        Some(resolution)
    }

    fn insert(&mut self, cache_key: UdpDnsCacheKey, resolution: UdpDnsResolution, now: Instant) {
        self.prune_expired(now);
        if let Some(previous) = self.entries.remove(&cache_key) {
            self.total_owned_bytes = self.total_owned_bytes.saturating_sub(previous.owned_bytes);
        }

        let owned_bytes = Self::owned_bytes(&cache_key, &resolution);
        if self.max_entries == 0 || self.max_owned_bytes == 0 || owned_bytes > self.max_owned_bytes {
            return;
        }

        let last_access = self.next_access_sequence();
        self.entries.insert(cache_key, UdpDnsCacheEntry { captured_at: now, resolution, owned_bytes, last_access });
        self.total_owned_bytes = self.total_owned_bytes.saturating_add(owned_bytes);
        self.evict_lru_to_limits();
    }

    fn prune_expired(&mut self, now: Instant) {
        let ttl = self.ttl;
        let total_owned_bytes = &mut self.total_owned_bytes;
        self.entries.retain(|_, entry| {
            let retain = now.saturating_duration_since(entry.captured_at) <= ttl;
            if !retain {
                *total_owned_bytes = total_owned_bytes.saturating_sub(entry.owned_bytes);
            }
            retain
        });
    }

    fn evict_lru_to_limits(&mut self) {
        while self.entries.len() > self.max_entries || self.total_owned_bytes > self.max_owned_bytes {
            let Some(cache_key) = self
                .entries
                .iter()
                .min_by(|(left_key, left), (right_key, right)| {
                    left.last_access.cmp(&right.last_access).then_with(|| left_key.cmp(right_key))
                })
                .map(|(cache_key, _)| cache_key.clone())
            else {
                break;
            };
            if let Some(entry) = self.entries.remove(&cache_key) {
                self.total_owned_bytes = self.total_owned_bytes.saturating_sub(entry.owned_bytes);
            }
        }
    }

    fn next_access_sequence(&mut self) -> u64 {
        self.access_sequence = self.access_sequence.saturating_add(1);
        self.access_sequence
    }

    /// Counts variable data owned by an entry; the entry limit separately bounds fixed map/node overhead.
    fn owned_bytes(cache_key: &UdpDnsCacheKey, resolution: &UdpDnsResolution) -> usize {
        let key_bytes =
            cache_key.domain.len().saturating_add(cache_key.server.len()).saturating_add(cache_key.transport.len());
        let result_bytes = match &resolution.result {
            Ok(addresses) => addresses.iter().fold(0usize, |total, address| total.saturating_add(address.len())),
            Err(error) => error.len(),
        };
        let raw_response_bytes = resolution.raw_response.as_ref().map_or(0, Vec::len);
        let error_kind_bytes = resolution.error_kind.as_ref().map_or(0, String::len);
        key_bytes.saturating_add(result_bytes).saturating_add(raw_response_bytes).saturating_add(error_kind_bytes)
    }
}

static UDP_DNS_CACHE: OnceLock<Mutex<UdpDnsCache>> = OnceLock::new();

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
        TransportConfig::Socks5 { host, port, credentials } => {
            let server_addr = resolve_first_socket_addr(server)?;
            relay_udp_via_socks5(host, *port, server_addr, packet, credentials.as_ref())
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
    let now = Instant::now();
    let cache = UDP_DNS_CACHE.get_or_init(|| {
        Mutex::new(UdpDnsCache::new(UDP_DNS_CACHE_TTL, UDP_DNS_CACHE_MAX_ENTRIES, UDP_DNS_CACHE_MAX_OWNED_BYTES))
    });
    let mut guard = cache.lock().ok()?;
    guard.get(cache_key, now)
}

fn cache_udp_dns_resolution(cache_key: UdpDnsCacheKey, resolution: &UdpDnsResolution) {
    let cached_resolution = resolution.clone();
    let now = Instant::now();
    let cache = UDP_DNS_CACHE.get_or_init(|| {
        Mutex::new(UdpDnsCache::new(UDP_DNS_CACHE_TTL, UDP_DNS_CACHE_MAX_ENTRIES, UDP_DNS_CACHE_MAX_OWNED_BYTES))
    });
    let Ok(mut guard) = cache.lock() else {
        return;
    };
    guard.insert(cache_key, cached_resolution, now);
}

fn transport_cache_key(transport: &TransportConfig) -> String {
    match transport {
        TransportConfig::Direct { .. } => "direct".to_string(),
        TransportConfig::Socks5 { host, port, .. } => format!("socks5:{host}:{port}"),
    }
}

#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};

    use super::{UdpDnsCache, UdpDnsCacheKey, UdpDnsResolution, classify_udp_dns_error, is_retryable_udp_dns_error};

    fn udp_dns_cache_key(domain: &str) -> UdpDnsCacheKey {
        UdpDnsCacheKey { domain: domain.to_string(), server: "1.1.1.1:53".to_string(), transport: "direct".to_string() }
    }

    fn udp_dns_cache_resolution(address: &str, raw_response_bytes: usize) -> UdpDnsResolution {
        UdpDnsResolution {
            result: Ok(vec![address.to_string()]),
            raw_response: Some(vec![0; raw_response_bytes]),
            latency_ms: 5,
            attempt_count: 1,
            success_count: 1,
            error_kind: None,
            retry_recovered: false,
            cache_hit: false,
        }
    }

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

    #[test]
    fn udp_dns_cache_fresh_hit_marks_only_returned_clone() {
        let now = Instant::now();
        let key = udp_dns_cache_key("fresh.example");
        let mut cache = UdpDnsCache::new(Duration::from_secs(30), 2, 1024);
        cache.insert(key.clone(), udp_dns_cache_resolution("192.0.2.1", 8), now);

        let hit = cache.get(&key, now + Duration::from_secs(30)).expect("fresh cache entry");

        assert!(hit.cache_hit);
        assert!(!cache.entries.get(&key).expect("stored cache entry").resolution.cache_hit);
    }

    #[test]
    fn udp_dns_cache_operation_globally_prunes_expired_entries() {
        let now = Instant::now();
        let expired = udp_dns_cache_key("expired.example");
        let fresh = udp_dns_cache_key("fresh.example");
        let fresh_resolution = udp_dns_cache_resolution("192.0.2.2", 8);
        let fresh_owned_bytes = UdpDnsCache::owned_bytes(&fresh, &fresh_resolution);
        let mut cache = UdpDnsCache::new(Duration::from_secs(30), 4, 4096);
        cache.insert(expired.clone(), udp_dns_cache_resolution("192.0.2.1", 8), now);
        cache.insert(fresh.clone(), fresh_resolution, now + Duration::from_secs(10));

        assert!(cache.get(&fresh, now + Duration::from_secs(31)).is_some());
        assert!(!cache.entries.contains_key(&expired));
        assert!(cache.entries.contains_key(&fresh));
        assert_eq!(cache.total_owned_bytes, fresh_owned_bytes);
    }

    #[test]
    fn udp_dns_cache_hit_refreshes_lru_without_extending_ttl() {
        let now = Instant::now();
        let first = udp_dns_cache_key("first.example");
        let second = udp_dns_cache_key("second.example");
        let third = udp_dns_cache_key("third.example");
        let mut cache = UdpDnsCache::new(Duration::from_secs(30), 2, 4096);
        cache.insert(first.clone(), udp_dns_cache_resolution("192.0.2.1", 8), now);
        cache.insert(second.clone(), udp_dns_cache_resolution("192.0.2.2", 8), now);
        assert!(cache.get(&first, now + Duration::from_secs(5)).is_some());

        cache.insert(third.clone(), udp_dns_cache_resolution("192.0.2.3", 8), now + Duration::from_secs(5));

        assert!(cache.entries.contains_key(&first));
        assert!(!cache.entries.contains_key(&second));
        assert!(cache.entries.contains_key(&third));
        assert!(cache.get(&first, now + Duration::from_secs(31)).is_none());
    }

    #[test]
    fn udp_dns_cache_enforces_entry_limit() {
        let now = Instant::now();
        let mut cache = UdpDnsCache::new(Duration::from_secs(30), 1, 4096);
        cache.insert(udp_dns_cache_key("first.example"), udp_dns_cache_resolution("192.0.2.1", 8), now);
        cache.insert(udp_dns_cache_key("second.example"), udp_dns_cache_resolution("192.0.2.2", 8), now);

        assert_eq!(cache.entries.len(), 1);
    }

    #[test]
    fn udp_dns_cache_enforces_owned_byte_limit_with_lru_eviction() {
        let now = Instant::now();
        let first = udp_dns_cache_key("a.example");
        let second = udp_dns_cache_key("b.example");
        let first_resolution = udp_dns_cache_resolution("192.0.2.1", 8);
        let second_resolution = udp_dns_cache_resolution("192.0.2.2", 8);
        let byte_limit = UdpDnsCache::owned_bytes(&first, &first_resolution)
            + UdpDnsCache::owned_bytes(&second, &second_resolution)
            - 1;
        let mut cache = UdpDnsCache::new(Duration::from_secs(30), 4, byte_limit);
        cache.insert(first.clone(), first_resolution, now);
        cache.insert(second.clone(), second_resolution, now);

        assert!(!cache.entries.contains_key(&first));
        assert!(cache.entries.contains_key(&second));
        assert!(cache.total_owned_bytes <= byte_limit);
    }

    #[test]
    fn udp_dns_cache_rejects_oversize_replacement_and_removes_old_value() {
        let now = Instant::now();
        let key = udp_dns_cache_key("replacement.example");
        let small = udp_dns_cache_resolution("192.0.2.1", 8);
        let byte_limit = UdpDnsCache::owned_bytes(&key, &small);
        let mut cache = UdpDnsCache::new(Duration::from_secs(30), 2, byte_limit);
        cache.insert(key.clone(), small, now);
        cache.insert(key.clone(), udp_dns_cache_resolution("192.0.2.2", byte_limit), now);

        assert!(!cache.entries.contains_key(&key));
        assert_eq!(cache.total_owned_bytes, 0);
    }

    #[test]
    fn udp_dns_cache_replacement_keeps_count_and_bytes_exact() {
        let now = Instant::now();
        let key = udp_dns_cache_key("replacement.example");
        let original = udp_dns_cache_resolution("192.0.2.1", 64);
        let replacement = udp_dns_cache_resolution("192.0.2.100", 4);
        let replacement_bytes = UdpDnsCache::owned_bytes(&key, &replacement);
        let mut cache = UdpDnsCache::new(Duration::from_secs(30), 2, 4096);
        cache.insert(key.clone(), original, now);
        cache.insert(key.clone(), replacement, now);

        assert_eq!(cache.entries.len(), 1);
        assert_eq!(cache.total_owned_bytes, replacement_bytes);
        assert_eq!(cache.entries.get(&key).expect("replacement").owned_bytes, replacement_bytes);
    }
}
