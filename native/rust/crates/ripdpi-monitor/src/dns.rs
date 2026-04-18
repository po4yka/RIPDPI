use std::net::{IpAddr, Ipv4Addr};

use hickory_proto::op::Message;
use hickory_proto::rr::rdata::svcb::SvcParamValue;
use hickory_proto::rr::RData;
use ripdpi_dns_resolver::{
    extract_ip_answers, EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport,
};

use crate::transport::{relay_udp_direct, relay_udp_via_socks5, resolve_first_socket_addr, TransportConfig};
use crate::types::DnsTarget;
use crate::util::{now_ms, DEFAULT_DOH_BOOTSTRAP_IPS, DEFAULT_DOH_HOST, DEFAULT_DOH_PORT, DEFAULT_DOH_URL};

const DNS_RECORD_TYPE_A: u16 = 1;
const DNS_RECORD_TYPE_HTTPS: u16 = 65;

/// Returns hardcoded bootstrap IPs for well-known DoH resolver identifiers.
///
/// These IPs allow the DoH bootstrap connection to bypass tampered DNS entirely,
/// eliminating the 4+ second delay caused by resolving the DoH host through
/// censored DNS infrastructure.
pub fn bootstrap_ips_for_resolver(resolver_id: &str) -> Vec<IpAddr> {
    match resolver_id {
        "cloudflare" => vec![IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), IpAddr::V4(Ipv4Addr::new(1, 0, 0, 1))],
        "adguard" => vec![IpAddr::V4(Ipv4Addr::new(94, 140, 14, 14)), IpAddr::V4(Ipv4Addr::new(94, 140, 15, 15))],
        "google" | "google_ip" => vec![IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), IpAddr::V4(Ipv4Addr::new(8, 8, 4, 4))],
        "quad9" => vec![IpAddr::V4(Ipv4Addr::new(9, 9, 9, 9)), IpAddr::V4(Ipv4Addr::new(149, 112, 112, 112))],
        "dnssb" => vec![IpAddr::V4(Ipv4Addr::new(185, 222, 222, 222)), IpAddr::V4(Ipv4Addr::new(45, 11, 45, 11))],
        "mullvad" => vec![IpAddr::V4(Ipv4Addr::new(194, 242, 2, 2))],
        _ => vec![],
    }
}

#[derive(Clone, Debug)]
pub(crate) enum EchResolutionOutcome {
    /// DoH succeeded and HTTPS record contained an EchConfigList.
    Available(Vec<u8>),
    /// DoH succeeded but the HTTPS response had no EchConfigList parameter.
    NotPublished,
    /// DoH query itself failed (network error, timeout, blocked, etc.).
    ResolutionFailed(String),
}

pub(crate) fn encrypted_dns_protocol(value: Option<&str>) -> EncryptedDnsProtocol {
    match value.unwrap_or_default().trim().to_ascii_lowercase().as_str() {
        "dot" => EncryptedDnsProtocol::Dot,
        "dnscrypt" => EncryptedDnsProtocol::DnsCrypt,
        "doq" => EncryptedDnsProtocol::Doq,
        _ => EncryptedDnsProtocol::Doh,
    }
}

pub(crate) fn parse_url_host(value: &str) -> Option<String> {
    let trimmed = value.trim();
    let (_, remainder) = trimmed.split_once("://")?;
    let authority = remainder.split('/').next()?;
    if authority.is_empty() {
        return None;
    }
    let host_port = authority.rsplit_once('@').map_or(authority, |(_, suffix)| suffix);
    if host_port.starts_with('[') {
        let end = host_port.find(']')?;
        return Some(host_port[1..end].to_string());
    }
    host_port.split(':').next().map(ToOwned::to_owned)
}

pub(crate) fn encrypted_dns_endpoint_for_target(
    target: &DnsTarget,
) -> Result<(EncryptedDnsEndpoint, Vec<String>), String> {
    let protocol = encrypted_dns_protocol(target.encrypted_protocol.as_deref());
    let bootstrap_strings = if !target.encrypted_bootstrap_ips.is_empty() {
        target.encrypted_bootstrap_ips.clone()
    } else if let Some(ref rid) = target.encrypted_resolver_id {
        let pinned = bootstrap_ips_for_resolver(rid);
        if !pinned.is_empty() {
            pinned.iter().map(ToString::to_string).collect::<Vec<_>>()
        } else {
            DEFAULT_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect::<Vec<_>>()
        }
    } else {
        DEFAULT_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect::<Vec<_>>()
    };
    let doh_url = target
        .encrypted_doh_url
        .clone()
        .or_else(|| (protocol == EncryptedDnsProtocol::Doh).then(|| DEFAULT_DOH_URL.to_string()));
    let host =
        target.encrypted_host.clone().or_else(|| doh_url.as_deref().and_then(parse_url_host)).unwrap_or_else(|| {
            if protocol == EncryptedDnsProtocol::Doh {
                DEFAULT_DOH_HOST.to_string()
            } else {
                String::new()
            }
        });
    let port = target.encrypted_port.unwrap_or(match protocol {
        EncryptedDnsProtocol::Doh => DEFAULT_DOH_PORT,
        EncryptedDnsProtocol::Dot => 853,
        EncryptedDnsProtocol::DnsCrypt => 443,
        EncryptedDnsProtocol::Doq => 853,
    });

    Ok((
        EncryptedDnsEndpoint {
            protocol,
            resolver_id: target.encrypted_resolver_id.clone().or_else(|| Some(protocol.as_str().to_string())),
            host,
            port,
            tls_server_name: target.encrypted_tls_server_name.clone(),
            bootstrap_ips: parse_bootstrap_ips(&bootstrap_strings)?,
            doh_url,
            dnscrypt_provider_name: target.encrypted_dnscrypt_provider_name.clone(),
            dnscrypt_public_key: target.encrypted_dnscrypt_public_key.clone(),
        },
        bootstrap_strings,
    ))
}

/// Resolve a domain via plain UDP DNS, returning both the parsed IP addresses
/// and the raw response bytes for protocol-level tampering analysis.
pub(crate) fn resolve_via_udp_with_raw(
    domain: &str,
    server: &str,
    transport: &TransportConfig,
) -> (Result<Vec<String>, String>, Option<Vec<u8>>) {
    let query_id = ((now_ms() & 0xffff) as u16).max(1);
    let packet = match build_dns_query_with_type(domain, query_id, DNS_RECORD_TYPE_A) {
        Ok(pkt) => pkt,
        Err(err) => return (Err(err), None),
    };
    let raw = match transport {
        TransportConfig::Direct => {
            let server_addr = match resolve_first_socket_addr(server) {
                Ok(addr) => addr,
                Err(err) => return (Err(err), None),
            };
            relay_udp_direct(server_addr, &packet)
        }
        TransportConfig::Socks5 { host, port } => {
            let server_addr = match resolve_first_socket_addr(server) {
                Ok(addr) => addr,
                Err(err) => return (Err(err), None),
            };
            relay_udp_via_socks5(host, *port, server_addr, &packet)
        }
    };
    match raw {
        Ok(response) => {
            let parsed = parse_dns_response(&response, query_id);
            (parsed, Some(response))
        }
        Err(err) => (Err(err), None),
    }
}

pub(crate) fn resolve_via_encrypted_dns(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> Result<Vec<String>, String> {
    let (result, _raw) = resolve_via_encrypted_dns_with_raw(domain, endpoint, transport);
    result
}

/// Like [`resolve_via_encrypted_dns`] but also returns the raw response bytes
/// for record-level comparison with the UDP response.
pub(crate) fn resolve_via_encrypted_dns_with_raw(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> (Result<Vec<String>, String>, Option<Vec<u8>>) {
    match exchange_encrypted_dns_query(domain, DNS_RECORD_TYPE_A, endpoint, transport) {
        Ok(raw) => {
            let parsed = extract_ip_answers(&raw).map_err(|err| err.to_string());
            (parsed, Some(raw))
        }
        Err(err) => (Err(err), None),
    }
}

pub(crate) fn resolve_https_ech_configs_via_encrypted_dns_with_endpoint(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> EchResolutionOutcome {
    match exchange_encrypted_dns_query(domain, DNS_RECORD_TYPE_HTTPS, endpoint, transport) {
        Err(err) => EchResolutionOutcome::ResolutionFailed(err),
        Ok(response) => match extract_ech_config_list_from_https_response(&response) {
            Err(err) => EchResolutionOutcome::ResolutionFailed(err),
            Ok(None) => EchResolutionOutcome::NotPublished,
            Ok(Some(bytes)) => EchResolutionOutcome::Available(bytes),
        },
    }
}

pub(crate) fn exchange_encrypted_dns_query(
    domain: &str,
    record_type: u16,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> Result<Vec<u8>, String> {
    let transport = match transport {
        TransportConfig::Direct => EncryptedDnsTransport::Direct,
        TransportConfig::Socks5 { host, port } => EncryptedDnsTransport::Socks5 { host: host.clone(), port: *port },
    };
    let resolver = EncryptedDnsResolver::new(endpoint, transport).map_err(|err| err.to_string())?;
    let query_id = ((now_ms() & 0xffff) as u16).max(1);
    let packet = build_dns_query_with_type(domain, query_id, record_type)?;
    resolver.exchange_blocking(&packet).map_err(|err| err.to_string())
}

pub(crate) fn parse_bootstrap_ips(values: &[String]) -> Result<Vec<IpAddr>, String> {
    values.iter().map(|value| value.parse::<IpAddr>().map_err(|err| err.to_string())).collect()
}

#[cfg(test)]
pub(crate) fn build_dns_query(domain: &str, query_id: u16) -> Result<Vec<u8>, String> {
    build_dns_query_with_type(domain, query_id, DNS_RECORD_TYPE_A)
}

pub(crate) fn build_dns_query_with_type(domain: &str, query_id: u16, record_type: u16) -> Result<Vec<u8>, String> {
    let mut packet = Vec::with_capacity(512);
    packet.extend(query_id.to_be_bytes());
    packet.extend(0x0100u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    for label in domain.split('.') {
        if label.is_empty() || label.len() > 63 {
            return Err("invalid_dns_name".to_string());
        }
        packet.push(label.len() as u8);
        packet.extend(label.as_bytes());
    }
    packet.push(0);
    packet.extend(record_type.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    Ok(packet)
}

pub(crate) fn extract_ech_config_list_from_https_response(packet: &[u8]) -> Result<Option<Vec<u8>>, String> {
    let message = Message::from_vec(packet).map_err(|err| err.to_string())?;
    for answer in message.answers() {
        let data = answer.data();
        let RData::HTTPS(https) = data else {
            continue;
        };
        for (_, param) in https.svc_params() {
            if let SvcParamValue::EchConfigList(configs) = param {
                if !configs.0.is_empty() {
                    return Ok(Some(configs.0.clone()));
                }
            }
        }
    }
    Ok(None)
}

pub(crate) fn encrypted_dns_endpoint_for_resolver_id(resolver_id: &str) -> EncryptedDnsEndpoint {
    let (resolver_id, host, port, doh_url, tls_server_name) = match resolver_id {
        "cloudflare" => (
            "cloudflare",
            "cloudflare-dns.com",
            443,
            "https://cloudflare-dns.com/dns-query",
            Some("cloudflare-dns.com"),
        ),
        "google" => ("google", "dns.google", 443, "https://dns.google/dns-query", Some("dns.google")),
        "google_ip" => ("google_ip", "8.8.8.8", 443, "https://8.8.8.8/dns-query", None),
        "quad9" => ("quad9", "dns.quad9.net", 443, "https://dns.quad9.net/dns-query", Some("dns.quad9.net")),
        "dnssb" => ("dnssb", "dns.sb", 443, "https://doh.dns.sb/dns-query", Some("dns.sb")),
        "mullvad" => ("mullvad", "dns.mullvad.net", 443, "https://dns.mullvad.net/dns-query", Some("dns.mullvad.net")),
        _ => ("adguard", DEFAULT_DOH_HOST, DEFAULT_DOH_PORT, DEFAULT_DOH_URL, Some(DEFAULT_DOH_HOST)),
    };

    let bootstrap_ips = {
        let pinned = bootstrap_ips_for_resolver(resolver_id);
        if pinned.is_empty() {
            DEFAULT_DOH_BOOTSTRAP_IPS.iter().filter_map(|value| value.parse::<IpAddr>().ok()).collect()
        } else {
            pinned
        }
    };

    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Doh,
        resolver_id: Some(resolver_id.to_string()),
        host: host.to_string(),
        port,
        tls_server_name: tls_server_name.map(ToString::to_string),
        bootstrap_ips,
        doh_url: Some(doh_url.to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    }
}

/// Returns a short list of fallback encrypted DNS endpoints to try when the
/// primary resolver is blocked.  The list intentionally uses providers from
/// different ASNs and geographies to maximise the chance that at least one
/// remains reachable on a censored network.
pub(crate) fn build_fallback_encrypted_dns_endpoints(primary_resolver_id: Option<&str>) -> Vec<EncryptedDnsEndpoint> {
    let candidates: Vec<(&str, &str, u16, &[&str], &str)> = vec![
        // (resolver_id, host, port, bootstrap_ips, doh_url)
        (
            "adguard",
            "dns.adguard-dns.com",
            443,
            &["94.140.14.14", "94.140.15.15"],
            "https://dns.adguard-dns.com/dns-query",
        ),
        ("dnssb", "dns.sb", 443, &["185.222.222.222", "45.11.45.11"], "https://doh.dns.sb/dns-query"),
        ("google_ip", "8.8.8.8", 443, &["8.8.8.8", "8.8.4.4"], "https://8.8.8.8/dns-query"),
        ("mullvad", "dns.mullvad.net", 443, &["194.242.2.2"], "https://dns.mullvad.net/dns-query"),
    ];

    candidates
        .into_iter()
        .filter(|(id, ..)| primary_resolver_id != Some(*id))
        .filter_map(|(id, host, port, bootstrap_strs, doh_url)| {
            let bootstrap_ips: Vec<IpAddr> = bootstrap_strs.iter().filter_map(|s| s.parse::<IpAddr>().ok()).collect();
            if bootstrap_ips.is_empty() {
                return None;
            }
            Some(EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Doh,
                resolver_id: Some(id.to_string()),
                host: host.to_string(),
                port,
                tls_server_name: None,
                bootstrap_ips,
                doh_url: Some(doh_url.to_string()),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            })
        })
        .collect()
}

pub(crate) fn parse_dns_response(packet: &[u8], expected_id: u16) -> Result<Vec<String>, String> {
    if packet.len() < 12 {
        return Err("dns_response_too_short".to_string());
    }
    let id = u16::from_be_bytes([packet[0], packet[1]]);
    if id != expected_id {
        return Err("dns_response_id_mismatch".to_string());
    }
    let rcode = packet[3] & 0x0F;
    if rcode == 3 {
        return Err("dns_nxdomain".to_string());
    }
    if rcode == 2 {
        return Err("dns_servfail".to_string());
    }
    if rcode == 5 {
        return Err("dns_refused".to_string());
    }
    let answer_count = u16::from_be_bytes([packet[6], packet[7]]) as usize;
    let question_count = u16::from_be_bytes([packet[4], packet[5]]) as usize;
    let mut offset = 12usize;
    for _ in 0..question_count {
        offset = skip_dns_name(packet, offset)?;
        offset += 4;
        if offset > packet.len() {
            return Err("dns_question_truncated".to_string());
        }
    }

    let mut answers = Vec::new();
    for _ in 0..answer_count {
        offset = skip_dns_name(packet, offset)?;
        if offset + 10 > packet.len() {
            return Err("dns_answer_truncated".to_string());
        }
        let record_type = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
        let data_len = u16::from_be_bytes([packet[offset + 8], packet[offset + 9]]) as usize;
        offset += 10;
        if offset + data_len > packet.len() {
            return Err("dns_rdata_truncated".to_string());
        }
        if record_type == 1 && data_len == 4 {
            answers.push(
                Ipv4Addr::new(packet[offset], packet[offset + 1], packet[offset + 2], packet[offset + 3]).to_string(),
            );
        }
        offset += data_len;
    }
    if answers.is_empty() {
        return Err("dns_empty".to_string());
    }
    Ok(answers)
}

pub(crate) fn skip_dns_name(packet: &[u8], mut offset: usize) -> Result<usize, String> {
    loop {
        let Some(length) = packet.get(offset).copied() else {
            return Err("dns_name_truncated".to_string());
        };
        if length & 0b1100_0000 == 0b1100_0000 {
            if offset + 1 >= packet.len() {
                return Err("dns_pointer_truncated".to_string());
            }
            return Ok(offset + 2);
        }
        offset += 1;
        if length == 0 {
            return Ok(offset);
        }
        offset += length as usize;
        if offset > packet.len() {
            return Err("dns_label_truncated".to_string());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_dns_query_valid_domain() {
        let result = build_dns_query("example.com", 0x1234).unwrap();
        // Header: 12 bytes, "example" label (1+7), "com" label (1+3), root (1), qtype (2), qclass (2)
        assert_eq!(result.len(), 12 + 8 + 4 + 1 + 4);
        // Query ID
        assert_eq!(result[0], 0x12);
        assert_eq!(result[1], 0x34);
        // Flags: standard query with recursion desired (0x0100)
        assert_eq!(result[2], 0x01);
        assert_eq!(result[3], 0x00);
        // QDCOUNT = 1
        assert_eq!(u16::from_be_bytes([result[4], result[5]]), 1);
    }

    #[test]
    fn build_dns_query_rejects_empty_label() {
        let result = build_dns_query("example..com", 1);
        assert!(result.is_err());
    }

    #[test]
    fn parse_dns_response_with_single_a_record() {
        // Build a minimal valid DNS response with one A record for 1.2.3.4
        let query_id: u16 = 0xABCD;
        let mut packet = Vec::new();
        // Header
        packet.extend(query_id.to_be_bytes()); // ID
        packet.extend(0x8180u16.to_be_bytes()); // Flags: response, recursion desired+available
        packet.extend(1u16.to_be_bytes()); // QDCOUNT
        packet.extend(1u16.to_be_bytes()); // ANCOUNT
        packet.extend(0u16.to_be_bytes()); // NSCOUNT
        packet.extend(0u16.to_be_bytes()); // ARCOUNT
                                           // Question: example.com, type A, class IN
        packet.push(7);
        packet.extend(b"example");
        packet.push(3);
        packet.extend(b"com");
        packet.push(0);
        packet.extend(1u16.to_be_bytes()); // QTYPE A
        packet.extend(1u16.to_be_bytes()); // QCLASS IN
                                           // Answer: pointer to name at offset 12, type A, class IN, TTL, rdlength=4, rdata=1.2.3.4
        packet.extend(0xC00Cu16.to_be_bytes()); // Name pointer to offset 12
        packet.extend(1u16.to_be_bytes()); // TYPE A
        packet.extend(1u16.to_be_bytes()); // CLASS IN
        packet.extend(300u32.to_be_bytes()); // TTL
        packet.extend(4u16.to_be_bytes()); // RDLENGTH
        packet.extend([1, 2, 3, 4]); // RDATA

        let answers = parse_dns_response(&packet, query_id).unwrap();
        assert_eq!(answers, vec!["1.2.3.4"]);
    }

    #[test]
    fn parse_dns_response_id_mismatch() {
        let mut packet = vec![0u8; 12];
        packet[0] = 0x00;
        packet[1] = 0x01;
        let result = parse_dns_response(&packet, 0x0002);
        assert_eq!(result.unwrap_err(), "dns_response_id_mismatch");
    }

    #[test]
    fn parse_dns_response_too_short() {
        let result = parse_dns_response(&[0u8; 5], 1);
        assert_eq!(result.unwrap_err(), "dns_response_too_short");
    }

    #[test]
    fn skip_dns_name_with_labels() {
        // "example.com" encoded as labels: 7 e x a m p l e 3 c o m 0
        let data = [7, b'e', b'x', b'a', b'm', b'p', b'l', b'e', 3, b'c', b'o', b'm', 0];
        let end = skip_dns_name(&data, 0).unwrap();
        assert_eq!(end, 13); // past the terminating zero
    }

    #[test]
    fn skip_dns_name_with_pointer() {
        // A pointer at offset 0: 0xC0 0x0C (points to offset 12)
        let data = [0xC0, 0x0C, 0x00];
        let end = skip_dns_name(&data, 0).unwrap();
        assert_eq!(end, 2); // pointer consumes exactly 2 bytes
    }

    #[test]
    fn skip_dns_name_truncated() {
        let data: [u8; 0] = [];
        let result = skip_dns_name(&data, 0);
        assert!(result.is_err());
    }

    #[test]
    fn encrypted_dns_protocol_defaults_to_doh() {
        assert_eq!(encrypted_dns_protocol(None), EncryptedDnsProtocol::Doh);
        assert_eq!(encrypted_dns_protocol(Some("")), EncryptedDnsProtocol::Doh);
        assert_eq!(encrypted_dns_protocol(Some("unknown")), EncryptedDnsProtocol::Doh);
    }

    #[test]
    fn encrypted_dns_protocol_recognizes_dot_and_dnscrypt() {
        assert_eq!(encrypted_dns_protocol(Some("dot")), EncryptedDnsProtocol::Dot);
        assert_eq!(encrypted_dns_protocol(Some("DOT")), EncryptedDnsProtocol::Dot);
        assert_eq!(encrypted_dns_protocol(Some("dnscrypt")), EncryptedDnsProtocol::DnsCrypt);
    }

    #[test]
    fn parse_url_host_extracts_hostname() {
        assert_eq!(parse_url_host("https://dns.google/dns-query"), Some("dns.google".to_string()));
        assert_eq!(parse_url_host("https://user@host.example:443/path"), Some("host.example".to_string()));
        assert_eq!(parse_url_host("https://[::1]:443/path"), Some("::1".to_string()));
    }

    #[test]
    fn parse_url_host_returns_none_for_invalid() {
        assert_eq!(parse_url_host("no-scheme"), None);
        assert_eq!(parse_url_host("https:///path"), None);
    }

    #[test]
    fn bootstrap_ips_for_known_resolvers() {
        let cf = bootstrap_ips_for_resolver("cloudflare");
        assert_eq!(cf.len(), 2);
        assert_eq!(cf[0], "1.1.1.1".parse::<IpAddr>().unwrap());
        assert_eq!(cf[1], "1.0.0.1".parse::<IpAddr>().unwrap());

        let ag = bootstrap_ips_for_resolver("adguard");
        assert_eq!(ag.len(), 2);
        assert_eq!(ag[0], "94.140.14.14".parse::<IpAddr>().unwrap());

        let g = bootstrap_ips_for_resolver("google");
        assert_eq!(g.len(), 2);
        assert_eq!(g[0], "8.8.8.8".parse::<IpAddr>().unwrap());

        let g_ip = bootstrap_ips_for_resolver("google_ip");
        assert_eq!(g_ip, g);

        let q9 = bootstrap_ips_for_resolver("quad9");
        assert_eq!(q9.len(), 2);
        assert_eq!(q9[0], "9.9.9.9".parse::<IpAddr>().unwrap());

        let dsb = bootstrap_ips_for_resolver("dnssb");
        assert_eq!(dsb.len(), 2);

        let mv = bootstrap_ips_for_resolver("mullvad");
        assert_eq!(mv.len(), 1);
        assert_eq!(mv[0], "194.242.2.2".parse::<IpAddr>().unwrap());
    }

    #[test]
    fn bootstrap_ips_for_unknown_resolver_is_empty() {
        assert!(bootstrap_ips_for_resolver("unknown-provider").is_empty());
    }

    #[test]
    fn endpoint_for_resolver_id_uses_known_doh_metadata() {
        let endpoint = encrypted_dns_endpoint_for_resolver_id("cloudflare");
        assert_eq!(endpoint.resolver_id.as_deref(), Some("cloudflare"));
        assert_eq!(endpoint.host, "cloudflare-dns.com");
        assert_eq!(endpoint.tls_server_name.as_deref(), Some("cloudflare-dns.com"));
        assert_eq!(endpoint.doh_url.as_deref(), Some("https://cloudflare-dns.com/dns-query"));
        assert_eq!(endpoint.bootstrap_ips.len(), 2);
    }

    #[test]
    fn endpoint_for_unknown_resolver_id_falls_back_to_adguard() {
        let endpoint = encrypted_dns_endpoint_for_resolver_id("unknown");
        assert_eq!(endpoint.resolver_id.as_deref(), Some("adguard"));
        assert_eq!(endpoint.host, DEFAULT_DOH_HOST);
        assert_eq!(endpoint.doh_url.as_deref(), Some(DEFAULT_DOH_URL));
    }

    #[test]
    fn endpoint_for_target_uses_pinned_ips_when_resolver_known() {
        let target = DnsTarget {
            domain: "example.com".to_string(),
            udp_server: None,
            encrypted_resolver_id: Some("cloudflare".to_string()),
            encrypted_protocol: Some("doh".to_string()),
            encrypted_host: Some("cloudflare-dns.com".to_string()),
            encrypted_port: Some(443),
            encrypted_tls_server_name: None,
            encrypted_bootstrap_ips: vec![], // empty -- should be filled from registry
            encrypted_doh_url: Some("https://cloudflare-dns.com/dns-query".to_string()),
            encrypted_dnscrypt_provider_name: None,
            encrypted_dnscrypt_public_key: None,
            expected_ips: vec![],
        };
        let (endpoint, bootstrap_strings) = encrypted_dns_endpoint_for_target(&target).unwrap();
        assert_eq!(
            endpoint.bootstrap_ips,
            vec!["1.1.1.1".parse::<IpAddr>().unwrap(), "1.0.0.1".parse::<IpAddr>().unwrap(),]
        );
        assert_eq!(bootstrap_strings, vec!["1.1.1.1", "1.0.0.1"]);
    }

    #[test]
    fn endpoint_for_target_respects_explicit_bootstrap_ips() {
        let target = DnsTarget {
            domain: "example.com".to_string(),
            udp_server: None,
            encrypted_resolver_id: Some("cloudflare".to_string()),
            encrypted_protocol: Some("doh".to_string()),
            encrypted_host: Some("cloudflare-dns.com".to_string()),
            encrypted_port: Some(443),
            encrypted_tls_server_name: None,
            encrypted_bootstrap_ips: vec!["10.0.0.1".to_string()], // explicit -- should NOT be overridden
            encrypted_doh_url: Some("https://cloudflare-dns.com/dns-query".to_string()),
            encrypted_dnscrypt_provider_name: None,
            encrypted_dnscrypt_public_key: None,
            expected_ips: vec![],
        };
        let (endpoint, _) = encrypted_dns_endpoint_for_target(&target).unwrap();
        assert_eq!(endpoint.bootstrap_ips, vec!["10.0.0.1".parse::<IpAddr>().unwrap()]);
    }

    #[test]
    fn parse_bootstrap_ips_valid() {
        let input = vec!["8.8.8.8".to_string(), "2001:4860:4860::8888".to_string()];
        let result = parse_bootstrap_ips(&input).unwrap();
        assert_eq!(result.len(), 2);
        assert_eq!(result[0], "8.8.8.8".parse::<IpAddr>().unwrap());
    }

    #[test]
    fn parse_bootstrap_ips_invalid() {
        let input = vec!["not-an-ip".to_string()];
        assert!(parse_bootstrap_ips(&input).is_err());
    }
}

// ---------------------------------------------------------------------------
// hickory-resolver probe (feature-gated)
// ---------------------------------------------------------------------------

#[cfg(feature = "hickory")]
mod hickory_probe {
    use std::net::SocketAddr;

    use hickory_resolver::config::{NameServerConfig, NameServerConfigGroup, ResolverConfig};
    use hickory_resolver::name_server::TokioConnectionProvider;
    use hickory_resolver::proto::xfer::Protocol;
    use hickory_resolver::Resolver;

    use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

    /// Build a [`ResolverConfig`] targeting the given encrypted-DNS endpoint.
    ///
    /// Only Direct transport is supported (no SOCKS5) because this is a
    /// diagnostic probe, not the production resolver path.
    fn resolver_config_for_endpoint(endpoint: &EncryptedDnsEndpoint) -> Result<ResolverConfig, String> {
        let protocol = match endpoint.protocol {
            EncryptedDnsProtocol::Doh => Protocol::Https,
            EncryptedDnsProtocol::Dot => Protocol::Tls,
            EncryptedDnsProtocol::DnsCrypt => {
                return Err("hickory-resolver does not support DNSCrypt".to_string());
            }
        };

        if endpoint.bootstrap_ips.is_empty() {
            return Err("at least one bootstrap IP is required".to_string());
        }

        let tls_name = endpoint.tls_server_name.clone().unwrap_or_else(|| endpoint.host.clone());

        let servers: Vec<NameServerConfig> = endpoint
            .bootstrap_ips
            .iter()
            .map(|ip| {
                let mut ns = NameServerConfig::new(SocketAddr::new(*ip, endpoint.port), protocol);
                ns.tls_dns_name = Some(tls_name.clone());
                if matches!(protocol, Protocol::Https) {
                    ns.http_endpoint = endpoint
                        .doh_url
                        .as_deref()
                        .and_then(|u| u.find("/dns-query").map(|idx| u[idx..].to_string()))
                        .or_else(|| Some("/dns-query".to_string()));
                }
                ns
            })
            .collect();

        let group = NameServerConfigGroup::from(servers);
        Ok(ResolverConfig::from_parts(None, vec![], group))
    }

    /// Resolve `domain` via `hickory-resolver` using the supplied encrypted-DNS
    /// endpoint.  This is a blocking wrapper around the async resolver, intended
    /// for diagnostic probing only.
    pub(crate) fn resolve_via_hickory_dns(domain: &str, endpoint: EncryptedDnsEndpoint) -> Result<Vec<String>, String> {
        let config = resolver_config_for_endpoint(&endpoint)?;
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|e| format!("tokio runtime: {e}"))?;

        rt.block_on(async {
            let resolver = Resolver::builder_with_config(config, TokioConnectionProvider::default()).build();
            let response = resolver.lookup_ip(domain).await.map_err(|e| e.to_string())?;
            let ips: Vec<String> = response.iter().map(|ip| ip.to_string()).collect();
            if ips.is_empty() {
                return Err("hickory: no addresses returned".to_string());
            }
            Ok(ips)
        })
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use std::net::IpAddr;

        fn make_doh_endpoint(bootstrap_ips: Vec<IpAddr>) -> EncryptedDnsEndpoint {
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Doh,
                resolver_id: Some("doh".to_string()),
                host: "dns.google".to_string(),
                port: 443,
                tls_server_name: Some("dns.google".to_string()),
                bootstrap_ips,
                doh_url: Some("https://dns.google/dns-query".to_string()),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            }
        }

        fn make_dot_endpoint(bootstrap_ips: Vec<IpAddr>) -> EncryptedDnsEndpoint {
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Dot,
                resolver_id: Some("dot".to_string()),
                host: "dns.google".to_string(),
                port: 853,
                tls_server_name: Some("dns.google".to_string()),
                bootstrap_ips,
                doh_url: None,
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            }
        }

        #[test]
        fn resolver_config_builds_for_doh() {
            let endpoint = make_doh_endpoint(vec!["8.8.8.8".parse().unwrap()]);
            let config = resolver_config_for_endpoint(&endpoint).unwrap();
            let ns = &config.name_servers()[0];
            assert_eq!(ns.socket_addr.port(), 443);
            assert_eq!(ns.tls_dns_name.as_deref(), Some("dns.google"));
            assert_eq!(ns.http_endpoint.as_deref(), Some("/dns-query"));
        }

        #[test]
        fn resolver_config_builds_for_dot() {
            let endpoint = make_dot_endpoint(vec!["8.8.8.8".parse().unwrap()]);
            let config = resolver_config_for_endpoint(&endpoint).unwrap();
            let ns = &config.name_servers()[0];
            assert_eq!(ns.socket_addr.port(), 853);
            assert_eq!(ns.tls_dns_name.as_deref(), Some("dns.google"));
            assert!(ns.http_endpoint.is_none());
        }

        #[test]
        fn resolver_config_rejects_dnscrypt() {
            let endpoint = EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::DnsCrypt,
                resolver_id: None,
                host: String::new(),
                port: 443,
                tls_server_name: None,
                bootstrap_ips: vec!["1.1.1.1".parse().unwrap()],
                doh_url: None,
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            };
            let err = resolver_config_for_endpoint(&endpoint).unwrap_err();
            assert!(err.contains("DNSCrypt"), "expected DNSCrypt error, got: {err}");
        }

        #[test]
        fn resolver_config_rejects_empty_bootstrap_ips() {
            let endpoint = make_doh_endpoint(vec![]);
            let err = resolver_config_for_endpoint(&endpoint).unwrap_err();
            assert!(err.contains("bootstrap IP"), "expected bootstrap IP error, got: {err}");
        }

        #[test]
        fn resolver_config_doh_url_without_dns_query_defaults_path() {
            let mut endpoint = make_doh_endpoint(vec!["8.8.8.8".parse().unwrap()]);
            endpoint.doh_url = Some("https://dns.example/custom".to_string());
            let config = resolver_config_for_endpoint(&endpoint).unwrap();
            let ns = &config.name_servers()[0];
            assert_eq!(ns.http_endpoint.as_deref(), Some("/dns-query"));
        }

        #[test]
        fn resolver_config_doh_url_none_defaults_path() {
            let mut endpoint = make_doh_endpoint(vec!["8.8.8.8".parse().unwrap()]);
            endpoint.doh_url = None;
            let config = resolver_config_for_endpoint(&endpoint).unwrap();
            let ns = &config.name_servers()[0];
            assert_eq!(ns.http_endpoint.as_deref(), Some("/dns-query"));
        }

        #[test]
        fn resolver_config_uses_host_as_tls_name_fallback() {
            let mut endpoint = make_dot_endpoint(vec!["8.8.8.8".parse().unwrap()]);
            endpoint.tls_server_name = None;
            endpoint.host = "my-resolver.example".to_string();
            let config = resolver_config_for_endpoint(&endpoint).unwrap();
            let ns = &config.name_servers()[0];
            assert_eq!(ns.tls_dns_name.as_deref(), Some("my-resolver.example"));
        }

        #[test]
        fn resolver_config_multiple_bootstrap_ips_creates_multiple_servers() {
            let endpoint = make_dot_endpoint(vec![
                "8.8.8.8".parse().unwrap(),
                "8.8.4.4".parse().unwrap(),
                "2001:4860:4860::8888".parse().unwrap(),
            ]);
            let config = resolver_config_for_endpoint(&endpoint).unwrap();
            assert_eq!(config.name_servers().len(), 3);
        }
    }
}

#[cfg(feature = "hickory")]
pub(crate) use hickory_probe::resolve_via_hickory_dns;
