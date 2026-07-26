use std::net::{IpAddr, SocketAddr};
use std::str::FromStr;

use ripdpi_proxy_runtime_adapter::model::config::{
    DestinationDomainMatcher, DestinationDomainMatcherKind, DestinationIpMatcher, DestinationIpMatcherKind,
    DestinationRoutingAction, DestinationRoutingNetwork, DestinationRoutingPolicy, DestinationRoutingRule,
};
use ripdpi_proxy_runtime_adapter::model::decision::TransportProtocol;
use ripdpi_proxy_runtime_adapter::model::services::GeoMatcher;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum DestinationEgress {
    Tunneled,
    Direct,
    Block,
}

#[derive(Clone)]
pub(super) struct DestinationRoutingEvaluator {
    rules: Box<[CompiledRule]>,
}

#[derive(Clone)]
struct CompiledRule {
    action: DestinationEgress,
    network: DestinationRoutingNetwork,
    domains: Box<[DestinationDomainMatcher]>,
    ip_ranges: Box<[CompiledIpMatcher]>,
    destination_ports: Box<[std::ops::RangeInclusive<u16>]>,
}

#[derive(Clone)]
enum CompiledIpMatcher {
    Cidr { network: IpAddr, prefix: u8 },
    Private,
    GeoIp(Box<str>),
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum MatchState {
    Match,
    NoMatch,
    Inconclusive,
}

impl DestinationRoutingEvaluator {
    pub(super) fn compile(policy: &DestinationRoutingPolicy) -> Self {
        let rules = policy.rules.iter().filter_map(CompiledRule::compile).collect::<Vec<_>>().into_boxed_slice();
        Self { rules }
    }

    pub(super) fn is_active(&self) -> bool {
        !self.rules.is_empty()
    }

    pub(super) fn may_need_host(&self) -> bool {
        self.rules.iter().any(|rule| !rule.domains.is_empty())
    }

    pub(super) fn evaluate(
        &self,
        target: SocketAddr,
        host: Option<&str>,
        transport: TransportProtocol,
        geo: Option<&dyn GeoMatcher>,
    ) -> DestinationEgress {
        let canonical_host = host.and_then(canonical_host);
        for rule in &self.rules {
            match rule.match_state(target, canonical_host.as_deref(), transport, geo) {
                MatchState::Match => return rule.action,
                MatchState::Inconclusive => {
                    return if rule.action == DestinationEgress::Block {
                        DestinationEgress::Block
                    } else {
                        DestinationEgress::Tunneled
                    };
                }
                MatchState::NoMatch => {}
            }
        }
        DestinationEgress::Tunneled
    }
}

impl CompiledRule {
    fn compile(rule: &DestinationRoutingRule) -> Option<Self> {
        let ip_ranges = rule.ip_ranges.iter().map(CompiledIpMatcher::compile).collect::<Option<Vec<_>>>()?;
        Some(Self {
            action: DestinationEgress::from_config(rule.action),
            network: rule.network,
            domains: rule.domains.clone().into_boxed_slice(),
            ip_ranges: ip_ranges.into_boxed_slice(),
            destination_ports: rule
                .destination_ports
                .iter()
                .map(|range| range.start..=range.end_inclusive)
                .collect::<Vec<_>>()
                .into_boxed_slice(),
        })
    }

    fn match_state(
        &self,
        target: SocketAddr,
        host: Option<&str>,
        transport: TransportProtocol,
        geo: Option<&dyn GeoMatcher>,
    ) -> MatchState {
        if !network_matches(self.network, transport)
            || (!self.destination_ports.is_empty()
                && !self.destination_ports.iter().any(|range| range.contains(&target.port())))
        {
            return MatchState::NoMatch;
        }
        let domains = match host {
            Some(host) if !self.domains.is_empty() => {
                any_match(self.domains.iter().map(|m| domain_match_state(m, host, geo)))
            }
            None if !self.domains.is_empty() => MatchState::NoMatch,
            _ => MatchState::Match,
        };
        let ips = if self.ip_ranges.is_empty() {
            MatchState::Match
        } else {
            any_match(self.ip_ranges.iter().map(|m| m.match_state(target.ip(), geo)))
        };
        match (domains, ips) {
            (MatchState::NoMatch, _) | (_, MatchState::NoMatch) => MatchState::NoMatch,
            (MatchState::Inconclusive, _) | (_, MatchState::Inconclusive) => MatchState::Inconclusive,
            _ => MatchState::Match,
        }
    }
}

impl CompiledIpMatcher {
    fn compile(matcher: &DestinationIpMatcher) -> Option<Self> {
        match matcher.kind {
            DestinationIpMatcherKind::Cidr => {
                let (address, prefix) = matcher.value.split_once('/')?;
                let network = IpAddr::from_str(address).ok()?;
                let prefix = prefix.parse::<u8>().ok()?;
                matches!(network, IpAddr::V4(_))
                    .then_some(32)
                    .or_else(|| matches!(network, IpAddr::V6(_)).then_some(128))
                    .filter(|max| prefix <= *max)?;
                Some(Self::Cidr { network, prefix })
            }
            DestinationIpMatcherKind::GeoIp if matcher.value == "private" => Some(Self::Private),
            DestinationIpMatcherKind::GeoIp => Some(Self::GeoIp(matcher.value.clone().into_boxed_str())),
            _ => None,
        }
    }

    fn match_state(&self, target: IpAddr, geo: Option<&dyn GeoMatcher>) -> MatchState {
        match self {
            Self::Cidr { network, prefix } => bool_match(cidr_contains(*network, *prefix, target)),
            Self::Private => bool_match(is_private_destination(target)),
            Self::GeoIp(country) => geo
                .and_then(|matcher| matcher.country_match(country, target))
                .map_or(MatchState::Inconclusive, bool_match),
        }
    }
}

impl DestinationEgress {
    fn from_config(action: DestinationRoutingAction) -> Self {
        match action {
            DestinationRoutingAction::Tunneled => Self::Tunneled,
            DestinationRoutingAction::Direct => Self::Direct,
            DestinationRoutingAction::Block => Self::Block,
            _ => Self::Tunneled,
        }
    }
}

fn network_matches(network: DestinationRoutingNetwork, transport: TransportProtocol) -> bool {
    matches!(
        (network, transport),
        (DestinationRoutingNetwork::Both, _)
            | (DestinationRoutingNetwork::Tcp, TransportProtocol::Tcp)
            | (DestinationRoutingNetwork::Udp, TransportProtocol::Udp)
    )
}

fn canonical_host(raw: &str) -> Option<String> {
    let host = raw.strip_suffix('.').unwrap_or(raw);
    if host.is_empty() || !host.is_ascii() {
        return None;
    }
    Some(host.to_ascii_lowercase())
}

fn domain_match_state(matcher: &DestinationDomainMatcher, host: &str, geo: Option<&dyn GeoMatcher>) -> MatchState {
    match matcher.kind {
        DestinationDomainMatcherKind::Exact => bool_match(host == matcher.value),
        DestinationDomainMatcherKind::Suffix => bool_match(
            host == matcher.value || host.strip_suffix(&matcher.value).is_some_and(|prefix| prefix.ends_with('.')),
        ),
        DestinationDomainMatcherKind::Geosite => {
            geo.and_then(|geo| geo.geosite_match(&matcher.value, host)).map_or(MatchState::Inconclusive, bool_match)
        }
        _ => MatchState::NoMatch,
    }
}

fn bool_match(value: bool) -> MatchState {
    if value { MatchState::Match } else { MatchState::NoMatch }
}

fn any_match(states: impl Iterator<Item = MatchState>) -> MatchState {
    let mut inconclusive = false;
    for state in states {
        match state {
            MatchState::Match => return MatchState::Match,
            MatchState::Inconclusive => inconclusive = true,
            MatchState::NoMatch => {}
        }
    }
    if inconclusive { MatchState::Inconclusive } else { MatchState::NoMatch }
}

fn cidr_contains(network: IpAddr, prefix: u8, target: IpAddr) -> bool {
    match (network, target) {
        (IpAddr::V4(network), IpAddr::V4(target)) => {
            let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
            u32::from(network) & mask == u32::from(target) & mask
        }
        (IpAddr::V6(network), IpAddr::V6(target)) => {
            let mask = if prefix == 0 { 0 } else { u128::MAX << (128 - prefix) };
            u128::from(network) & mask == u128::from(target) & mask
        }
        _ => false,
    }
}

fn is_private_destination(target: IpAddr) -> bool {
    match target {
        IpAddr::V4(address) => {
            address.is_private()
                || address.is_loopback()
                || address.is_link_local()
                || cidr_contains(IpAddr::V4(std::net::Ipv4Addr::new(100, 64, 0, 0)), 10, target)
        }
        IpAddr::V6(address) => address.is_loopback() || address.is_unique_local() || address.is_unicast_link_local(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_proxy_runtime_adapter::model::config::{
        DestinationPortRange, DestinationRoutingPolicy, DestinationRoutingRule,
    };
    use std::net::{Ipv4Addr, Ipv6Addr};

    #[derive(Default)]
    struct FakeGeo;

    impl GeoMatcher for FakeGeo {
        fn country_matches_ip(&self, country_code: &str, ip: IpAddr) -> bool {
            country_code == "ru" && ip == IpAddr::V4(Ipv4Addr::new(203, 0, 113, 7))
        }

        fn geosite_matches_host(&self, category: &str, host: &str) -> bool {
            category == "ru" && host == "regional.example"
        }
    }

    fn rule(
        action: DestinationRoutingAction,
        network: DestinationRoutingNetwork,
        domains: Vec<DestinationDomainMatcher>,
        ip_ranges: Vec<DestinationIpMatcher>,
        destination_ports: Vec<DestinationPortRange>,
    ) -> DestinationRoutingRule {
        DestinationRoutingRule { action, network, domains, ip_ranges, destination_ports }
    }

    fn evaluate(
        rules: Vec<DestinationRoutingRule>,
        target: SocketAddr,
        host: Option<&str>,
        transport: TransportProtocol,
        geo: Option<&dyn GeoMatcher>,
    ) -> DestinationEgress {
        DestinationRoutingEvaluator::compile(&DestinationRoutingPolicy {
            rules,
            canonical_digest: "digest".to_string(),
            ..DestinationRoutingPolicy::default()
        })
        .evaluate(target, host, transport, geo)
    }

    #[test]
    fn first_match_is_host_specific_even_when_hosts_share_an_ip() {
        let rules = vec![
            rule(
                DestinationRoutingAction::Direct,
                DestinationRoutingNetwork::Both,
                vec![DestinationDomainMatcher {
                    kind: DestinationDomainMatcherKind::Exact,
                    value: "direct.example".to_string(),
                }],
                vec![],
                vec![],
            ),
            rule(
                DestinationRoutingAction::Block,
                DestinationRoutingNetwork::Both,
                vec![DestinationDomainMatcher {
                    kind: DestinationDomainMatcherKind::Suffix,
                    value: "example".to_string(),
                }],
                vec![],
                vec![],
            ),
        ];
        let target = SocketAddr::from(([192, 0, 2, 9], 443));

        assert_eq!(
            evaluate(rules.clone(), target, Some("DIRECT.EXAMPLE."), TransportProtocol::Tcp, None),
            DestinationEgress::Direct
        );
        assert_eq!(
            evaluate(rules, target, Some("blocked.example"), TransportProtocol::Tcp, None),
            DestinationEgress::Block
        );
    }

    #[test]
    fn cidr_port_transport_and_ipv6_match_without_route_hints() {
        let v4 = rule(
            DestinationRoutingAction::Direct,
            DestinationRoutingNetwork::Tcp,
            vec![],
            vec![DestinationIpMatcher { kind: DestinationIpMatcherKind::Cidr, value: "192.0.2.0/24".to_string() }],
            vec![DestinationPortRange { start: 443, end_inclusive: 443 }],
        );
        let v6 = rule(
            DestinationRoutingAction::Block,
            DestinationRoutingNetwork::Udp,
            vec![],
            vec![DestinationIpMatcher { kind: DestinationIpMatcherKind::Cidr, value: "2001:db8::/32".to_string() }],
            vec![],
        );

        assert_eq!(
            evaluate(
                vec![v4.clone(), v6.clone()],
                SocketAddr::from(([192, 0, 2, 77], 443)),
                None,
                TransportProtocol::Tcp,
                None
            ),
            DestinationEgress::Direct
        );
        assert_eq!(
            evaluate(
                vec![v4.clone(), v6.clone()],
                SocketAddr::new(IpAddr::V6(Ipv6Addr::from(0x20010db8000000000000000000000001u128)), 53),
                None,
                TransportProtocol::Udp,
                None
            ),
            DestinationEgress::Block
        );
        assert_eq!(
            evaluate(vec![v4, v6], SocketAddr::from(([192, 0, 2, 77], 80)), None, TransportProtocol::Tcp, None),
            DestinationEgress::Tunneled
        );
    }

    #[test]
    fn geo_rules_fail_closed_when_databases_are_missing() {
        let rules = vec![
            rule(
                DestinationRoutingAction::Direct,
                DestinationRoutingNetwork::Both,
                vec![DestinationDomainMatcher { kind: DestinationDomainMatcherKind::Geosite, value: "ru".to_string() }],
                vec![],
                vec![],
            ),
            rule(
                DestinationRoutingAction::Direct,
                DestinationRoutingNetwork::Both,
                vec![],
                vec![DestinationIpMatcher { kind: DestinationIpMatcherKind::GeoIp, value: "ru".to_string() }],
                vec![],
            ),
        ];
        let target = SocketAddr::from(([203, 0, 113, 7], 443));

        assert_eq!(
            evaluate(rules.clone(), target, Some("regional.example"), TransportProtocol::Tcp, None),
            DestinationEgress::Tunneled
        );
        assert_eq!(
            evaluate(rules, target, Some("regional.example"), TransportProtocol::Tcp, Some(&FakeGeo)),
            DestinationEgress::Direct
        );
    }

    #[test]
    fn geo_block_rules_deny_when_databases_are_missing() {
        let target = SocketAddr::from(([203, 0, 113, 7], 443));
        for blocked in [
            rule(
                DestinationRoutingAction::Block,
                DestinationRoutingNetwork::Both,
                vec![DestinationDomainMatcher { kind: DestinationDomainMatcherKind::Geosite, value: "ru".to_string() }],
                vec![],
                vec![],
            ),
            rule(
                DestinationRoutingAction::Block,
                DestinationRoutingNetwork::Both,
                vec![],
                vec![DestinationIpMatcher { kind: DestinationIpMatcherKind::GeoIp, value: "ru".to_string() }],
                vec![],
            ),
        ] {
            assert_eq!(
                evaluate(vec![blocked], target, Some("regional.example"), TransportProtocol::Tcp, None),
                DestinationEgress::Block
            );
        }
    }

    #[test]
    fn geoip_private_is_built_in_for_ipv4_and_ipv6() {
        let private = rule(
            DestinationRoutingAction::Direct,
            DestinationRoutingNetwork::Both,
            vec![],
            vec![DestinationIpMatcher { kind: DestinationIpMatcherKind::GeoIp, value: "private".to_string() }],
            vec![],
        );

        for target in [
            SocketAddr::from(([10, 1, 2, 3], 443)),
            SocketAddr::from(([100, 64, 1, 2], 443)),
            SocketAddr::new(IpAddr::V6(Ipv6Addr::from(0xfc000000000000000000000000000001u128)), 443),
            SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443),
        ] {
            assert_eq!(
                evaluate(vec![private.clone()], target, None, TransportProtocol::Tcp, None),
                DestinationEgress::Direct
            );
        }
        assert_eq!(
            evaluate(vec![private], SocketAddr::from(([8, 8, 8, 8], 443)), None, TransportProtocol::Tcp, None),
            DestinationEgress::Tunneled
        );
    }
}
