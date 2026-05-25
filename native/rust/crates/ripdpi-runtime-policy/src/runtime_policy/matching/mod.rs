use std::net::SocketAddr;

use ripdpi_config::{DesyncGroup, RuntimeConfig};
pub use ripdpi_runtime_decision_ports::policy_ports::GeoMatcher;

use super::{ExtractedHost, TransportProtocol};
use facts::{extract_host as extract_host_from_payload, extract_host_info as extract_host_info_from_payload};
use predicates::{
    group_matches as group_matches_predicate, group_requires_payload as group_requires_payload_predicate,
};

mod facts;
mod predicates;

pub fn extract_host_info(config: &RuntimeConfig, payload: &[u8]) -> Option<ExtractedHost> {
    extract_host_info_from_payload(config, payload)
}

pub fn extract_host(config: &RuntimeConfig, payload: &[u8]) -> Option<String> {
    extract_host_from_payload(config, payload)
}

pub fn is_tls_client_hello_payload(payload: &[u8]) -> bool {
    facts::is_tls_client_hello_payload(payload)
}

pub fn group_requires_payload(group: &DesyncGroup) -> bool {
    group_requires_payload_predicate(group)
}

pub fn route_matches_payload(
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

pub fn route_matches_payload_with_geo(
    config: &RuntimeConfig,
    group_index: usize,
    dest: SocketAddr,
    payload: &[u8],
    transport: TransportProtocol,
    geo: &dyn GeoMatcher,
) -> bool {
    config.groups.get(group_index).is_some_and(|group| {
        predicates::group_matches_with_geo(config, group, dest, Some(payload), false, transport, Some(geo))
    })
}

pub(super) fn group_matches(
    config: &RuntimeConfig,
    group: &DesyncGroup,
    dest: SocketAddr,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    transport: TransportProtocol,
) -> bool {
    group_matches_predicate(config, group, dest, payload, allow_unknown_payload, transport)
}

pub(super) fn group_matches_with_geo(
    config: &RuntimeConfig,
    group: &DesyncGroup,
    dest: SocketAddr,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    transport: TransportProtocol,
    geo: Option<&dyn GeoMatcher>,
) -> bool {
    predicates::group_matches_with_geo(config, group, dest, payload, allow_unknown_payload, transport, geo)
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, SocketAddr};

    use super::*;
    use crate::runtime_policy::test_support::{config_with_groups, rust_packet_seeds, sample_dest};
    use facts::MatchFacts;
    use predicates::{matches_facts, matches_l34, payload_proto_known};
    use ripdpi_config::QuicInitialMode;
    use ripdpi_packets::{IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP};

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
        assert!(matches_facts(&group, &MatchFacts::from_payload(&config, http_payload)));
        assert!(!matches_facts(&group, &MatchFacts::from_payload(&config, tls_payload)));
    }

    #[test]
    fn matches_payload_allows_any_protocol_groups() {
        let mut group = DesyncGroup::new(0);
        group.matches.any_protocol = true;
        group.matches.proto = IS_TCP | IS_HTTP;
        let config = RuntimeConfig::default();

        assert!(matches_facts(&group, &MatchFacts::from_payload(&config, b"opaque payload")));
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
    fn route_matches_payload_with_geo_checks_geosite_filters() {
        let mut group = DesyncGroup::new(0);
        group.matches.filters.geosite_categories.push("video".to_string());
        let config = config_with_groups(vec![group]);
        let payload = b"GET / HTTP/1.1\r\nHost: cdn.example.test\r\n\r\n";
        let geo = FakeGeoMatcher::default().with_geosite("video", "example.test");

        assert!(route_matches_payload_with_geo(&config, 0, sample_dest(443), payload, TransportProtocol::Tcp, &geo));
        assert!(!route_matches_payload(&config, 0, sample_dest(443), payload, TransportProtocol::Tcp));
    }

    #[test]
    fn route_matches_payload_with_geo_checks_geoip_filters() {
        let mut group = DesyncGroup::new(0);
        group.matches.filters.geoip_countries.push("ru".to_string());
        let config = config_with_groups(vec![group]);
        let payload = b"opaque";
        let geo = FakeGeoMatcher::default().with_country(sample_dest(443).ip(), "ru");

        assert!(route_matches_payload_with_geo(&config, 0, sample_dest(443), payload, TransportProtocol::Tcp, &geo));
        assert!(!route_matches_payload(&config, 0, sample_dest(443), payload, TransportProtocol::Tcp));
    }

    #[test]
    fn extract_host_reads_quic_initial_sni() {
        let packet = rust_packet_seeds::quic_initial_v1();

        assert_eq!(extract_host(&RuntimeConfig::default(), &packet).as_deref(), Some("docs.example.test"));
    }

    #[test]
    fn multi_record_tls_client_hello_still_matches_https_and_extracts_host() {
        let packet = build_multi_record_tls_client_hello();
        let mut group = DesyncGroup::new(0);
        group.matches.proto = IS_TCP | IS_HTTPS;
        group.matches.filters.hosts.push("www.wikipedia.org".to_string());
        let config = config_with_groups(vec![group]);

        assert!(is_tls_client_hello_payload(&packet));
        assert_eq!(extract_host(&config, &packet).as_deref(), Some("www.wikipedia.org"));
        assert!(route_matches_payload(&config, 0, sample_dest(443), &packet, TransportProtocol::Tcp));
    }

    #[test]
    fn tls_like_prefix_without_client_hello_structure_does_not_match_https_policy() {
        let mut group = DesyncGroup::new(0);
        group.matches.proto = IS_TCP | IS_HTTPS;
        let config = config_with_groups(vec![group]);
        let tls_like_prefix = [0x16, 0x03, 0x03, 0x00, 0x01, 0x01];

        assert!(!is_tls_client_hello_payload(&tls_like_prefix));
        assert!(!route_matches_payload(&config, 0, sample_dest(443), &tls_like_prefix, TransportProtocol::Tcp));
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

    fn build_multi_record_tls_client_hello() -> Vec<u8> {
        let client_hello = rust_packet_seeds::tls_client_hello();
        let record_header = &client_hello[..3];
        let handshake = &client_hello[5..];
        let split = 96usize.min(handshake.len().saturating_sub(1));
        let mut payload = Vec::with_capacity(client_hello.len() + 5);
        payload.extend_from_slice(record_header);
        payload.extend_from_slice(&(split as u16).to_be_bytes());
        payload.extend_from_slice(&handshake[..split]);
        payload.extend_from_slice(record_header);
        payload.extend_from_slice(&((handshake.len() - split) as u16).to_be_bytes());
        payload.extend_from_slice(&handshake[split..]);
        payload
    }

    #[derive(Default)]
    struct FakeGeoMatcher {
        countries: Vec<(IpAddr, String)>,
        geosites: Vec<(String, String)>,
    }

    impl FakeGeoMatcher {
        fn with_country(mut self, ip: IpAddr, country: &str) -> Self {
            self.countries.push((ip, country.to_string()));
            self
        }

        fn with_geosite(mut self, category: &str, root_domain: &str) -> Self {
            self.geosites.push((category.to_string(), root_domain.to_string()));
            self
        }
    }

    impl GeoMatcher for FakeGeoMatcher {
        fn country_matches_ip(&self, country_code: &str, ip: IpAddr) -> bool {
            self.countries.iter().any(|(candidate_ip, country)| *candidate_ip == ip && country == country_code)
        }

        fn geosite_matches_host(&self, category: &str, host: &str) -> bool {
            self.geosites.iter().any(|(candidate_category, root_domain)| {
                candidate_category == category && (host == root_domain || host.ends_with(&format!(".{root_domain}")))
            })
        }
    }
}
