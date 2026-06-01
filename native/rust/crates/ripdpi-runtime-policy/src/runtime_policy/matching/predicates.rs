use std::net::SocketAddr;

use ripdpi_config::{DesyncGroup, RuntimeConfig};
use ripdpi_packets::{IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP};

use super::GeoMatcher;
use super::facts::MatchFacts;
use crate::runtime_policy::TransportProtocol;

pub(super) fn group_requires_payload(group: &DesyncGroup) -> bool {
    let active_proto = group.matches.proto & !group.matches.payload_disable;
    !group.matches.filters.hosts.is_empty() || (active_proto & (IS_HTTP | IS_HTTPS)) != 0
}

pub(super) fn group_matches(
    config: &RuntimeConfig,
    group: &DesyncGroup,
    dest: SocketAddr,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    transport: TransportProtocol,
) -> bool {
    group_matches_with_geo(config, group, dest, payload, allow_unknown_payload, transport, None)
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
    if !matches_l34_with_geo(group, dest, transport, geo) {
        return false;
    }
    match payload {
        Some(payload) => matches_facts_with_geo(group, &MatchFacts::from_payload(config, payload), geo),
        None if allow_unknown_payload => true,
        None => group.matches.filters.host_filters_empty() && payload_proto_known(group),
    }
}

pub(super) fn payload_proto_known(group: &DesyncGroup) -> bool {
    group.matches.any_protocol || group.matches.proto == 0 || (group.matches.proto & (IS_HTTP | IS_HTTPS)) == 0
}

#[cfg(test)]
pub(super) fn matches_l34(group: &DesyncGroup, dest: SocketAddr, transport: TransportProtocol) -> bool {
    matches_l34_with_geo(group, dest, transport, None)
}

pub(super) fn matches_l34_with_geo(
    group: &DesyncGroup,
    dest: SocketAddr,
    transport: TransportProtocol,
    geo: Option<&dyn GeoMatcher>,
) -> bool {
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
    if !group.matches.filters.geoip_countries.is_empty()
        && !geo.is_some_and(|matcher| {
            group.matches.filters.geoip_countries.iter().any(|country| matcher.country_matches_ip(country, dest.ip()))
        })
    {
        return false;
    }
    true
}

#[cfg(test)]
pub(super) fn matches_facts(group: &DesyncGroup, facts: &MatchFacts) -> bool {
    matches_facts_with_geo(group, facts, None)
}

pub(super) fn matches_facts_with_geo(group: &DesyncGroup, facts: &MatchFacts, geo: Option<&dyn GeoMatcher>) -> bool {
    if group.matches.any_protocol {
        return matches_host_filter(group, facts, geo);
    }
    if group.matches.proto != 0 {
        let l7 = group.matches.proto & !(IS_TCP | IS_UDP | IS_IPV4);
        if l7 != 0 && !matches_l7(group, facts, l7) {
            return false;
        }
    }
    matches_host_filter(group, facts, geo)
}

fn matches_l7(group: &DesyncGroup, facts: &MatchFacts, l7: u32) -> bool {
    let disable = group.matches.payload_disable;
    let http = (disable & IS_HTTP) == 0 && facts.is_http;
    let tls = (disable & IS_HTTPS) == 0 && facts.is_tls_client_hello;
    ((l7 & IS_HTTP) != 0 && http) || ((l7 & IS_HTTPS) != 0 && tls)
}

fn matches_host_filter(group: &DesyncGroup, facts: &MatchFacts, geo: Option<&dyn GeoMatcher>) -> bool {
    if group.matches.filters.host_filters_empty() {
        return true;
    }
    facts.host.as_ref().is_some_and(|host| {
        group.matches.filters.hosts_match(host.host.as_str())
            || geo.is_some_and(|matcher| {
                group
                    .matches
                    .filters
                    .geosite_categories
                    .iter()
                    .any(|category| matcher.geosite_matches_host(category, host.host.as_str()))
            })
    })
}
