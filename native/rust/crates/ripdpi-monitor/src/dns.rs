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
    let bootstrap_strings = if target.encrypted_bootstrap_ips.is_empty() {
        if target.doh_bootstrap_ips.is_empty() && target.doh_url.is_none() {
            DEFAULT_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect::<Vec<_>>()
        } else {
            target.doh_bootstrap_ips.clone()
        }
    } else {
        target.encrypted_bootstrap_ips.clone()
    };
    let doh_url = target
        .encrypted_doh_url
        .clone()
        .or_else(|| target.doh_url.clone())
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

pub(crate) fn resolve_via_udp(domain: &str, server: &str, transport: &TransportConfig) -> Result<Vec<String>, String> {
    let query_id = ((now_ms() & 0xffff) as u16).max(1);
    let packet = build_dns_query_with_type(domain, query_id, DNS_RECORD_TYPE_A)?;
    let response = match transport {
        TransportConfig::Direct => {
            let server_addr = resolve_first_socket_addr(server)?;
            relay_udp_direct(server_addr, &packet)?
        }
        TransportConfig::Socks5 { host, port } => {
            let server_addr = resolve_first_socket_addr(server)?;
            relay_udp_via_socks5(host, *port, server_addr, &packet)?
        }
    };
    parse_dns_response(&response, query_id)
}

pub(crate) fn resolve_via_encrypted_dns(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> Result<Vec<String>, String> {
    let response = exchange_encrypted_dns_query(domain, DNS_RECORD_TYPE_A, endpoint, transport)?;
    extract_ip_answers(&response).map_err(|err| err.to_string())
}

pub(crate) fn resolve_https_ech_configs_via_encrypted_dns(
    domain: &str,
    transport: &TransportConfig,
) -> Result<Option<Vec<u8>>, String> {
    let endpoint = default_encrypted_dns_endpoint();
    let response = exchange_encrypted_dns_query(domain, DNS_RECORD_TYPE_HTTPS, endpoint, transport)?;
    extract_ech_config_list_from_https_response(&response)
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

fn default_encrypted_dns_endpoint() -> EncryptedDnsEndpoint {
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Doh,
        resolver_id: Some("cloudflare".to_string()),
        host: DEFAULT_DOH_HOST.to_string(),
        port: DEFAULT_DOH_PORT,
        tls_server_name: None,
        bootstrap_ips: DEFAULT_DOH_BOOTSTRAP_IPS.iter().filter_map(|value| value.parse::<IpAddr>().ok()).collect(),
        doh_url: Some(DEFAULT_DOH_URL.to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    }
}

pub(crate) fn parse_dns_response(packet: &[u8], expected_id: u16) -> Result<Vec<String>, String> {
    if packet.len() < 12 {
        return Err("dns_response_too_short".to_string());
    }
    let id = u16::from_be_bytes([packet[0], packet[1]]);
    if id != expected_id {
        return Err("dns_response_id_mismatch".to_string());
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
