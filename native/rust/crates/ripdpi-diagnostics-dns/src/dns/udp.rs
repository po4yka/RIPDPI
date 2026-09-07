use std::thread;
use std::time::{Duration, Instant};

use crate::transport::{TransportConfig, relay_udp_direct, relay_udp_via_socks5, resolve_first_socket_addr};
use crate::util::{now_ms, ranged_probe_delay};

use ripdpi_ech_dns::{DNS_RECORD_TYPE_A, build_dns_query_with_type, parse_dns_response};

const UDP_DNS_ATTEMPTS: usize = 3;
const UDP_DNS_RETRY_JITTER_MIN_MS: u64 = 20;
const UDP_DNS_RETRY_JITTER_MAX_MS: u64 = 60;
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

/// Measure plain UDP DNS on the current path, without cross-scan result reuse.
pub fn resolve_via_udp_with_observations(domain: &str, server: &str, transport: &TransportConfig) -> UdpDnsResolution {
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
    UdpDnsResolution {
        result: Err(err),
        raw_response: None,
        latency_ms: started.elapsed().as_millis(),
        attempt_count,
        success_count: 0,
        error_kind: Some(kind),
        retry_recovered: false,
        cache_hit: false,
    }
}

fn execute_udp_query(
    server: &str,
    transport: &TransportConfig,
    packet: &[u8],
    query_id: u16,
) -> Result<(Vec<String>, Vec<u8>), String> {
    let raw = match transport {
        TransportConfig::Direct { .. } => {
            let server_addr = resolve_first_socket_addr(server).map_err(|err| err.to_string())?;
            relay_udp_direct(server_addr, packet).map_err(|err| err.to_string())
        }
        TransportConfig::Socks5 { host, port, credentials } => {
            let server_addr = resolve_first_socket_addr(server).map_err(|err| err.to_string())?;
            relay_udp_via_socks5(host, *port, server_addr, packet, credentials.as_ref()).map_err(|err| err.to_string())
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

#[cfg(test)]
mod measurement_regression {
    use super::*;
    use std::net::UdpSocket;

    #[test]
    fn repeated_measurement_observes_current_dns_answer() {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        socket.set_read_timeout(Some(Duration::from_millis(500))).unwrap();
        let address = socket.local_addr().unwrap().to_string();
        let server = thread::spawn(move || {
            for last in [1, 2] {
                let mut query = [0; 512];
                let Ok((len, peer)) = socket.recv_from(&mut query) else {
                    return;
                };
                let mut response = query[..len].to_vec();
                response[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
                response[6..8].copy_from_slice(&1u16.to_be_bytes());
                response.extend_from_slice(&[0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192, 0, 2, last]);
                socket.send_to(&response, peer).unwrap();
            }
        });
        let transport = TransportConfig::Direct { route_experiment: None };
        let first = resolve_via_udp_with_observations("fresh.example", &address, &transport);
        let second = resolve_via_udp_with_observations("fresh.example", &address, &transport);
        server.join().unwrap();
        assert_eq!(first.result.unwrap(), ["192.0.2.1"]);
        assert_eq!(second.result.unwrap(), ["192.0.2.2"]);
        assert!(!second.cache_hit);
    }
}
