use std::net::SocketAddr;

use ripdpi_config::{DesyncGroup, QuicInitialMode, RuntimeConfig};
use ripdpi_packets::classify::{default_registry, ProtocolId};
use ripdpi_packets::{is_http, is_tls_client_hello, parse_quic_initial, IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP};

use super::{ExtractedHost, HostSource, TransportProtocol};

pub(crate) fn extract_host_info(config: &RuntimeConfig, payload: &[u8]) -> Option<ExtractedHost> {
    if let Some(result) = default_registry().classify(payload) {
        let source = match result.protocol {
            ProtocolId::Http => HostSource::Http,
            _ => HostSource::Tls,
        };
        if let Some(host) = result.host {
            return Some(ExtractedHost { host, source });
        }
    }
    extract_quic_host(config, payload)
}

pub(crate) fn extract_host(config: &RuntimeConfig, payload: &[u8]) -> Option<String> {
    extract_host_info(config, payload).map(|host| host.host)
}

pub(crate) fn group_requires_payload(group: &DesyncGroup) -> bool {
    let active_proto = group.matches.proto & !group.matches.payload_disable;
    !group.matches.filters.hosts.is_empty() || (active_proto & (IS_HTTP | IS_HTTPS)) != 0
}

pub(crate) fn route_matches_payload(
    config: &RuntimeConfig,
    group_index: usize,
    dest: SocketAddr,
    payload: &[u8],
    transport: TransportProtocol,
) -> bool {
    config
        .groups
        .get(group_index)
        .is_some_and(|group| group_matches(config, group, dest, Some(payload), false, transport))
}

pub(super) fn group_matches(
    config: &RuntimeConfig,
    group: &DesyncGroup,
    dest: SocketAddr,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    transport: TransportProtocol,
) -> bool {
    if !matches_l34(group, dest, transport) {
        return false;
    }
    match payload {
        Some(payload) => matches_payload(config, group, payload),
        None if allow_unknown_payload => true,
        None => group.matches.filters.hosts.is_empty() && payload_proto_known(group),
    }
}

fn payload_proto_known(group: &DesyncGroup) -> bool {
    group.matches.any_protocol || group.matches.proto == 0 || (group.matches.proto & (IS_HTTP | IS_HTTPS)) == 0
}

fn matches_l34(group: &DesyncGroup, dest: SocketAddr, transport: TransportProtocol) -> bool {
    if (group.matches.proto & IS_UDP) != 0 && transport != TransportProtocol::Udp {
        return false;
    }
    if (group.matches.proto & IS_TCP) != 0 && transport != TransportProtocol::Tcp {
        return false;
    }
    if (group.matches.proto & IS_IPV4) != 0 && !dest.is_ipv4() {
        return false;
    }
    if let Some((start, end)) = group.matches.port_filter {
        let port = dest.port();
        if port < start || port > end {
            return false;
        }
    }
    if !group.matches.filters.ipset.is_empty() && !group.matches.filters.ipset_match(dest.ip()) {
        return false;
    }
    true
}

fn matches_payload(config: &RuntimeConfig, group: &DesyncGroup, payload: &[u8]) -> bool {
    if group.matches.any_protocol {
        if group.matches.filters.hosts.is_empty() {
            return true;
        }
        return extract_host(config, payload).as_deref().is_some_and(|host| group.matches.filters.hosts_match(host));
    }
    if group.matches.proto != 0 {
        let l7 = group.matches.proto & !(IS_TCP | IS_UDP | IS_IPV4);
        if l7 != 0 {
            let disable = group.matches.payload_disable;
            let http = (disable & IS_HTTP) == 0 && is_http(payload);
            let tls = (disable & IS_HTTPS) == 0 && is_tls_client_hello(payload);
            if ((l7 & IS_HTTP) != 0 && http) || ((l7 & IS_HTTPS) != 0 && tls) {
            } else {
                return false;
            }
        }
    }
    if group.matches.filters.hosts.is_empty() {
        return true;
    }
    extract_host(config, payload).as_deref().is_some_and(|host| group.matches.filters.hosts_match(host))
}

fn extract_quic_host(config: &RuntimeConfig, payload: &[u8]) -> Option<ExtractedHost> {
    if matches!(config.quic.initial_mode, QuicInitialMode::Disabled)
        || (!config.quic.support_v1 && !config.quic.support_v2)
    {
        return None;
    }
    let info = parse_quic_initial(payload)?;
    let allowed = (info.version == 0x0000_0001 && config.quic.support_v1)
        || (info.version == 0x6b33_43cf && config.quic.support_v2);
    allowed.then(|| ExtractedHost { host: String::from_utf8_lossy(info.host()).into_owned(), source: HostSource::Quic })
}

#[cfg(test)]
mod tests {
    use std::net::SocketAddr;

    use super::*;
    use crate::runtime_policy::test_support::{config_with_groups, rust_packet_seeds, sample_dest};

    #[test]
    fn matches_l34_rejects_udp_proto() {
        let mut group = DesyncGroup::new(0);
        group.matches.proto = IS_UDP;
        assert!(!matches_l34(&group, sample_dest(443), TransportProtocol::Tcp));
        assert!(matches_l34(&group, sample_dest(443), TransportProtocol::Udp));
    }

    #[test]
    fn matches_l34_port_filter_boundaries() {
        let mut group = DesyncGroup::new(0);
        group.matches.port_filter = Some((80, 443));
        assert!(matches_l34(&group, sample_dest(80), TransportProtocol::Tcp));
        assert!(matches_l34(&group, sample_dest(443), TransportProtocol::Tcp));
        assert!(!matches_l34(&group, sample_dest(79), TransportProtocol::Tcp));
        assert!(!matches_l34(&group, sample_dest(444), TransportProtocol::Tcp));
    }

    #[test]
    fn matches_l34_ipv4_only_filter() {
        let mut group = DesyncGroup::new(0);
        group.matches.proto = IS_IPV4;
        assert!(matches_l34(&group, sample_dest(443), TransportProtocol::Tcp));
        let ipv6_dest = SocketAddr::from(([0, 0, 0, 0, 0, 0, 0, 1], 443));
        assert!(!matches_l34(&group, ipv6_dest, TransportProtocol::Tcp));
    }

    #[test]
    fn payload_proto_known_values() {
        let mut group = DesyncGroup::new(0);
        assert!(payload_proto_known(&group));
        group.matches.proto = IS_TCP;
        assert!(payload_proto_known(&group));
        group.matches.proto = IS_HTTP;
        assert!(!payload_proto_known(&group));
        group.matches.any_protocol = true;
        assert!(payload_proto_known(&group));
    }

    #[test]
    fn group_matches_no_payload_allow_unknown_false() {
        let mut group = DesyncGroup::new(0);
        let config = RuntimeConfig::default();
        assert!(group_matches(&config, &group, sample_dest(80), None, false, TransportProtocol::Tcp));
        group.matches.filters.hosts.push("example.com".to_string());
        assert!(!group_matches(&config, &group, sample_dest(80), None, false, TransportProtocol::Tcp));
    }

    #[test]
    fn matches_payload_l7_proto_filtering() {
        let mut group = DesyncGroup::new(0);
        group.matches.proto = IS_TCP | IS_HTTP;
        let config = RuntimeConfig::default();
        let http_payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        let tls_payload = ripdpi_packets::DEFAULT_FAKE_TLS;
        assert!(matches_payload(&config, &group, http_payload));
        assert!(!matches_payload(&config, &group, tls_payload));
    }

    #[test]
    fn matches_payload_allows_any_protocol_groups() {
        let mut group = DesyncGroup::new(0);
        group.matches.any_protocol = true;
        group.matches.proto = IS_TCP | IS_HTTP;
        let config = RuntimeConfig::default();

        assert!(matches_payload(&config, &group, b"opaque payload"));
    }

    #[test]
    fn route_matches_payload_checks_host_filters() {
        let mut group = DesyncGroup::new(0);
        group.matches.filters.hosts.push("example.com".to_string());
        let config = config_with_groups(vec![group]);
        let matching = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        let non_matching = b"GET / HTTP/1.1\r\nHost: other.example\r\n\r\n";

        assert!(route_matches_payload(&config, 0, sample_dest(80), matching, TransportProtocol::Tcp));
        assert!(!route_matches_payload(&config, 0, sample_dest(80), non_matching, TransportProtocol::Tcp));
    }

    #[test]
    fn extract_host_reads_quic_initial_sni() {
        let packet = rust_packet_seeds::quic_initial_v1();

        assert_eq!(extract_host(&RuntimeConfig::default(), &packet).as_deref(), Some("docs.example.test"));
    }

    #[test]
    fn udp_host_filters_match_quic_initial_payloads() {
        let mut group = DesyncGroup::new(0);
        group.matches.proto = IS_UDP;
        group.matches.filters.hosts.push("docs.example.test".to_string());
        let config = config_with_groups(vec![group]);
        let packet = rust_packet_seeds::quic_initial_v1();

        assert!(route_matches_payload(&config, 0, sample_dest(443), &packet, TransportProtocol::Udp));
    }

    #[test]
    fn group_requires_payload_only_for_l7_or_host_filters() {
        let mut group = DesyncGroup::new(0);
        assert!(!group_requires_payload(&group));

        group.matches.proto = IS_TCP;
        assert!(!group_requires_payload(&group));

        group.matches.proto = IS_UDP;
        assert!(!group_requires_payload(&group));

        group.matches.proto = IS_HTTP;
        assert!(group_requires_payload(&group));

        group.matches.proto = IS_HTTPS | IS_TCP;
        assert!(group_requires_payload(&group));

        group.matches.proto = 0;
        group.matches.filters.hosts.push("example.com".to_string());
        assert!(group_requires_payload(&group));
    }

    #[test]
    fn extract_host_skips_quic_when_disabled() {
        let packet = rust_packet_seeds::quic_initial_v1();
        let mut config = RuntimeConfig::default();
        config.quic.initial_mode = QuicInitialMode::Disabled;

        assert_eq!(extract_host(&config, &packet), None);
    }

    #[test]
    fn extract_host_respects_quic_version_toggles() {
        let packet = rust_packet_seeds::quic_initial_v2();
        let mut config = RuntimeConfig::default();
        config.quic.support_v2 = false;

        assert_eq!(extract_host(&config, &packet), None);
    }
}
