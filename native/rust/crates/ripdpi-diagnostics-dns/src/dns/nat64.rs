use std::collections::BTreeSet;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV6};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use ripdpi_diagnostics_contracts::types::DnsResponseOutcome;
use ripdpi_diagnostics_contracts::util::{active_scan_io_deadline, now_ms};
use ripdpi_ech_dns::build_dns_query_with_type;

use super::parse_dns_response_semantics;
use crate::transport::relay_udp_direct;
use ripdpi_diagnostics_transport::transport::{TransportError, interface_index};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Nat64Prefix {
    pub address: Ipv6Addr,
    pub length: u8,
}
impl Nat64Prefix {
    /// RFC 6052 places the reserved u octet at byte 8 for non-/96 prefixes.
    pub fn synthesize(self, address: Ipv4Addr) -> Option<Ipv6Addr> {
        let offset = usize::from(self.length / 8);
        if ![32, 40, 48, 56, 64, 96].contains(&self.length) {
            return None;
        }
        let mut bytes = self.address.octets();
        if bytes[offset..].iter().any(|byte| *byte != 0) {
            return None;
        }
        let mut cursor = offset;
        for byte in address.octets() {
            if cursor == 8 {
                cursor += 1;
            }
            bytes[cursor] = byte;
            cursor += 1;
        }
        Some(Ipv6Addr::from(bytes))
    }
}

/// A bounded ordered prefix interpretation of query-bound ipv4only.arpa AAAA data.
/// Ambiguous individual embeddings and non-sentinel addresses do not authorize a TCP probe.
pub fn discover_nat64_prefixes(addresses: &[String]) -> Result<Vec<Nat64Prefix>, &'static str> {
    if addresses.len() > 16 {
        return Err("invalid_dns64_response");
    }
    let mut prefixes = Vec::new();
    let mut ambiguous = false;
    for raw in addresses {
        let address = raw.parse::<Ipv6Addr>().map_err(|_| "invalid_dns64_response")?;
        if address.to_ipv4_mapped().is_some() || address.is_multicast() || address.is_unspecified() {
            return Err("invalid_dns64_response");
        }
        let mut matches = BTreeSet::new();
        let mut address_ambiguous = false;
        for length in [32, 40, 48, 56, 64, 96] {
            let mut bytes = address.octets();
            bytes[usize::from(length / 8)..].fill(0);
            let prefix = Nat64Prefix { address: Ipv6Addr::from(bytes), length };
            for sentinel in [Ipv4Addr::new(192, 0, 0, 170), Ipv4Addr::new(192, 0, 0, 171)] {
                if prefix.synthesize(sentinel) == Some(address) {
                    let mut embedded = address.octets().to_vec();
                    if length != 96 {
                        embedded.remove(8);
                    }
                    if embedded.windows(4).filter(|window| *window == sentinel.octets()).count() != 1 {
                        ambiguous = true;
                        address_ambiguous = true;
                    } else {
                        matches.insert(prefix);
                    }
                }
            }
        }
        if matches.is_empty() {
            if address_ambiguous {
                continue;
            }
            return Err("invalid_dns64_response");
        }
        if matches.len() != 1 {
            return Err("ambiguous_prefix");
        }
        for prefix in matches {
            if !prefixes.contains(&prefix) {
                prefixes.push(prefix);
            }
        }
        if prefixes.len() > 4 {
            return Err("invalid_dns64_response");
        }
    }
    if prefixes.is_empty() && ambiguous {
        return Err("ambiguous_prefix");
    }
    Ok(prefixes)
}

#[derive(Debug)]
pub struct Nat64Discovery {
    pub prefixes: Vec<Nat64Prefix>,
    pub status: &'static str,
    pub reason: Option<&'static str>,
    pub attempts: u32,
}

/// Uses captured network resolver literals only, with at most three requests.
/// Blocking I/O inherits the caller's scan deadline; cancellation is checked before and after each request.
pub fn discover_network_nat64(servers: &[String], cancel: &AtomicBool) -> Nat64Discovery {
    discover_with(servers, cancel, |server, query| {
        relay_udp_direct(server, query).map(|(reply, _)| reply).map_err(|error| match error {
            TransportError::UdpRecvTimeout
            | TransportError::UdpRecvWouldBlock
            | TransportError::ScanDeadlineExceeded => "dns_timeout",
            _ => "dns_error",
        })
    })
}
fn discover_with(
    servers: &[String],
    cancel: &AtomicBool,
    mut exchange: impl FnMut(SocketAddr, &[u8]) -> Result<Vec<u8>, &'static str>,
) -> Nat64Discovery {
    let mut attempts = 0;
    let mut failure = None;
    let mut clean_negative = false;
    for server in resolver_targets(servers) {
        if let Some(reason) = interrupted(cancel) {
            return Nat64Discovery { prefixes: vec![], status: "TIMEOUT", reason: Some(reason), attempts };
        }
        let Ok(query) = build_dns_query_with_type("ipv4only.arpa", (now_ms() as u16).max(1), 28) else {
            return Nat64Discovery {
                prefixes: vec![],
                status: "INVALID",
                reason: Some("invalid_dns64_response"),
                attempts,
            };
        };
        attempts += 1;
        let response = exchange(server, &query);
        if let Some(reason) = interrupted(cancel) {
            return Nat64Discovery { prefixes: vec![], status: "TIMEOUT", reason: Some(reason), attempts };
        }
        let response = match response {
            Ok(response) => response,
            Err(reason) => {
                failure = Some(reason);
                continue;
            }
        };
        let outcome = match parse_dns_response_semantics(&query, &response) {
            Ok((_, facts)) => facts.outcome,
            Err(_) => {
                failure = Some("dns_error");
                continue;
            }
        };
        match outcome {
            DnsResponseOutcome::Answer => {
                // The general semantics parser sorts addresses. Preserve DNS response order here.
                let Ok(message) = hickory_proto::op::Message::from_vec(&response) else {
                    failure = Some("dns_error");
                    continue;
                };
                let addresses = message
                    .answers
                    .iter()
                    .filter_map(|record| match &record.data {
                        hickory_proto::rr::RData::AAAA(address) => Some(address.0.to_string()),
                        _ => None,
                    })
                    .collect::<Vec<_>>();
                match discover_nat64_prefixes(&addresses) {
                    Ok(prefixes) if !prefixes.is_empty() => {
                        return Nat64Discovery { prefixes, status: "DISCOVERED", reason: None, attempts };
                    }
                    Ok(_) => clean_negative = true,
                    // A well-formed answer that fails the Pref64 heuristic is a verdict, not a resolver fault.
                    Err(reason) => {
                        return Nat64Discovery { prefixes: vec![], status: "INVALID", reason: Some(reason), attempts };
                    }
                }
            }
            DnsResponseOutcome::Nodata | DnsResponseOutcome::Nxdomain => clean_negative = true,
            // SERVFAIL, REFUSED, truncation, and unobserved replies are one resolver's fault: try the next.
            _ => failure = Some("dns_error"),
        }
    }
    Nat64Discovery {
        prefixes: vec![],
        // A clean negative answer from any resolver outranks another resolver's transport fault.
        status: if failure.is_some() && !clean_negative { "FAILED" } else { "NO_PREFIX" },
        reason: Some(match (clean_negative, failure) {
            (false, Some(reason)) => reason,
            _ if attempts == 0 => "no_network_dns",
            _ => "no_prefix",
        }),
        attempts,
    }
}
/// Usable resolver endpoints in input order, deduplicated, limited to three.
/// Unusable literals are skipped before the limit so they do not use the budget.
fn resolver_targets(servers: &[String]) -> Vec<SocketAddr> {
    let mut targets = Vec::new();
    for address in servers.iter().filter_map(|server| resolver_endpoint(server)) {
        if !targets.contains(&address) {
            targets.push(address);
        }
    }
    targets.truncate(3);
    targets
}
/// Accepts IP literals and IPv6 literals with a numeric or interface-name zone. A link-local IPv6
/// address needs a zone because the kernel cannot pick an interface without one.
fn resolver_endpoint(literal: &str) -> Option<SocketAddr> {
    let (host, zone) = match literal.split_once('%') {
        Some((host, zone)) => (host, Some(zone)),
        None => (literal, None),
    };
    let ip = host.parse::<std::net::IpAddr>().ok()?;
    if ip.is_unspecified() || ip.is_multicast() {
        return None;
    }
    let std::net::IpAddr::V6(v6) = ip else {
        return zone.is_none().then(|| SocketAddr::new(ip, 53));
    };
    let scope_id = match zone {
        Some(zone) => zone.parse::<u32>().ok().filter(|id| *id != 0).or_else(|| interface_index(zone))?,
        None if v6.is_unicast_link_local() => return None,
        None => 0,
    };
    Some(SocketAddr::V6(SocketAddrV6::new(v6, 53, 0, scope_id)))
}
fn interrupted(cancel: &AtomicBool) -> Option<&'static str> {
    if cancel.load(Ordering::Acquire) {
        Some("cancelled")
    } else if active_scan_io_deadline().is_some_and(|deadline| Instant::now() >= deadline) {
        Some("deadline_exceeded")
    } else {
        None
    }
}

#[cfg(test)]
mod tests;
