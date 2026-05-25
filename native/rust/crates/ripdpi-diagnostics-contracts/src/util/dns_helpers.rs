use std::collections::BTreeSet;
use std::net::{IpAddr, SocketAddr};

pub fn ip_set(values: &[String]) -> BTreeSet<String> {
    values.iter().cloned().collect()
}

pub fn ipv4_prefix_24(value: &str) -> Option<[u8; 3]> {
    match value.parse::<IpAddr>().ok()? {
        IpAddr::V4(addr) => {
            let octets = addr.octets();
            Some([octets[0], octets[1], octets[2]])
        }
        IpAddr::V6(_) => None,
    }
}

pub fn ipv6_prefix_48(value: &str) -> Option<[u16; 3]> {
    match value.parse::<IpAddr>().ok()? {
        IpAddr::V4(_) => None,
        IpAddr::V6(addr) => {
            let segments = addr.segments();
            Some([segments[0], segments[1], segments[2]])
        }
    }
}

/// True when an IP belongs to a bogon / sinkhole / private space that cannot
/// legitimately answer for a public domain.
pub fn looks_like_sinkhole(value: &str) -> bool {
    match value.parse::<IpAddr>() {
        Ok(IpAddr::V4(addr)) => {
            addr.is_unspecified()
                || addr.is_loopback()
                || addr.is_link_local()
                || addr.is_broadcast()
                || addr.is_private()
                || addr.is_documentation()
                || addr.is_multicast()
        }
        Ok(IpAddr::V6(addr)) => {
            let first_segment = addr.segments()[0];
            let is_unique_local = (first_segment & 0xfe00) == 0xfc00;
            let is_link_local = (first_segment & 0xffc0) == 0xfe80;
            addr.is_unspecified() || addr.is_loopback() || addr.is_multicast() || is_unique_local || is_link_local
        }
        Err(_) => true,
    }
}

/// Outcome of comparing UDP and encrypted DNS answer sets. Distinguishes
/// legitimate anycast divergence (separate public IPs from the same CDN) from
/// real poisoning (disjoint sets where one side returns a sinkhole/private IP).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DnsAnswerOverlap {
    /// Any shared IP or shared /24 (v4) / /48 (v6) prefix.
    Match,
    /// Disjoint answers but both sides are plausibly public.
    CompatibleDivergence,
    /// Disjoint answers and at least one side returns a sinkhole / private IP.
    SinkholeSubstitution,
}

pub fn classify_dns_answer_overlap(udp: &[String], encrypted: &[String]) -> DnsAnswerOverlap {
    let udp_set = ip_set(udp);
    let enc_set = ip_set(encrypted);
    if !udp_set.is_disjoint(&enc_set) {
        return DnsAnswerOverlap::Match;
    }
    let udp_v4: BTreeSet<[u8; 3]> = udp_set.iter().filter_map(|ip| ipv4_prefix_24(ip)).collect();
    let enc_v4: BTreeSet<[u8; 3]> = enc_set.iter().filter_map(|ip| ipv4_prefix_24(ip)).collect();
    if !udp_v4.is_disjoint(&enc_v4) {
        return DnsAnswerOverlap::Match;
    }
    let udp_v6: BTreeSet<[u16; 3]> = udp_set.iter().filter_map(|ip| ipv6_prefix_48(ip)).collect();
    let enc_v6: BTreeSet<[u16; 3]> = enc_set.iter().filter_map(|ip| ipv6_prefix_48(ip)).collect();
    if !udp_v6.is_disjoint(&enc_v6) {
        return DnsAnswerOverlap::Match;
    }
    if udp_set.iter().any(|ip| looks_like_sinkhole(ip)) || enc_set.iter().any(|ip| looks_like_sinkhole(ip)) {
        return DnsAnswerOverlap::SinkholeSubstitution;
    }
    DnsAnswerOverlap::CompatibleDivergence
}

pub fn is_suspected_dns_tampering_outcome(outcome: &str) -> bool {
    matches!(outcome, "dns_suspicious_divergence" | "dns_sinkhole_substitution" | "dns_nxdomain_mismatch")
}

pub fn format_result_set(result: &Result<Vec<String>, String>) -> String {
    match result {
        Ok(values) => values.join("|"),
        Err(err) => format!("error:{err}"),
    }
}

pub fn format_socket_result(result: &Result<Vec<SocketAddr>, String>) -> String {
    match result {
        Ok(values) => values.iter().map(SocketAddr::to_string).collect::<Vec<_>>().join("|"),
        Err(err) => format!("error:{err}"),
    }
}
