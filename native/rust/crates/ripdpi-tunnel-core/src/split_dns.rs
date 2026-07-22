use ripdpi_tunnel_config::{SplitDnsAction, SplitDnsMatcherKind, SplitDnsNetwork, SplitDnsPolicyConfig, SplitDnsRule};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum DnsPolicyPlane {
    Proxy,
    Block,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct DnsPolicyDecision<'a> {
    pub(crate) plane: DnsPolicyPlane,
    pub(crate) reason: Option<&'a str>,
}

#[derive(Debug, Clone)]
pub(crate) struct SplitDnsPolicy {
    rules: Box<[SplitDnsRule]>,
    coverage_reason: Option<Box<str>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RuleMatch {
    Match,
    NoMatch,
    Inconclusive,
}

impl SplitDnsPolicy {
    pub(crate) fn compile(config: &SplitDnsPolicyConfig) -> Result<Self, &'static str> {
        if config.default_action != SplitDnsAction::Tunneled {
            return Err("split DNS default action must be tunneled");
        }
        Ok(Self {
            rules: config.rules.clone().into_boxed_slice(),
            coverage_reason: config.coverage_reason.clone().map(String::into_boxed_str),
        })
    }

    pub(crate) fn evaluate<'a>(&'a self, raw_qname: &str) -> DnsPolicyDecision<'a> {
        let Some(qname) = canonical_qname(raw_qname) else {
            return proxy(Some("invalid_qname"));
        };

        for rule in &self.rules {
            match evaluate_rule(rule, qname) {
                RuleMatch::NoMatch => continue,
                RuleMatch::Inconclusive => return proxy(Some("unsupported_domain_matcher")),
                RuleMatch::Match if rule.network != SplitDnsNetwork::Both || rule.has_ip_ranges || rule.has_ports => {
                    return proxy(Some("mixed_route_constraints"));
                }
                RuleMatch::Match => {
                    return match rule.action {
                        SplitDnsAction::Tunneled => proxy(None),
                        SplitDnsAction::Direct => proxy(Some("direct_plane_unbound")),
                        SplitDnsAction::Block => DnsPolicyDecision { plane: DnsPolicyPlane::Block, reason: None },
                    };
                }
            }
        }

        proxy(self.coverage_reason.as_deref())
    }
}

fn proxy(reason: Option<&str>) -> DnsPolicyDecision<'_> {
    DnsPolicyDecision { plane: DnsPolicyPlane::Proxy, reason }
}

fn evaluate_rule(rule: &SplitDnsRule, qname: &str) -> RuleMatch {
    if rule.domains.is_empty() {
        return RuleMatch::Match;
    }

    let mut inconclusive = false;
    for matcher in &rule.domains {
        let matches = match matcher.kind {
            SplitDnsMatcherKind::Exact => qname.eq_ignore_ascii_case(&matcher.value),
            SplitDnsMatcherKind::Suffix => suffix_matches(qname, &matcher.value),
            SplitDnsMatcherKind::Geosite => {
                inconclusive = true;
                false
            }
        };
        if matches {
            return RuleMatch::Match;
        }
    }
    if inconclusive { RuleMatch::Inconclusive } else { RuleMatch::NoMatch }
}

fn suffix_matches(qname: &str, suffix: &str) -> bool {
    qname.eq_ignore_ascii_case(suffix)
        || qname.get(..qname.len().saturating_sub(suffix.len())).is_some_and(|prefix| {
            prefix.ends_with('.') && qname[qname.len() - suffix.len()..].eq_ignore_ascii_case(suffix)
        })
}

fn canonical_qname(raw: &str) -> Option<&str> {
    if raw.is_empty() || raw.len() > 1_024 || !raw.is_ascii() {
        return None;
    }
    let qname = raw.strip_suffix('.').unwrap_or(raw);
    if qname.is_empty() || qname.len() > 253 {
        return None;
    }
    if qname.split('.').any(|label| {
        label.is_empty()
            || label.len() > 63
            || label.starts_with('-')
            || label.ends_with('-')
            || !label.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    }) {
        return None;
    }
    Some(qname)
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr};

    use ripdpi_tunnel_config::{
        SplitDnsAction, SplitDnsDomainMatcher, SplitDnsMatcherKind, SplitDnsNetwork, SplitDnsPolicyConfig, SplitDnsRule,
    };

    use super::{DnsPolicyDecision, DnsPolicyPlane, SplitDnsPolicy};

    fn matcher(kind: SplitDnsMatcherKind, value: &str) -> SplitDnsDomainMatcher {
        SplitDnsDomainMatcher { kind, value: value.to_string() }
    }

    fn rule(
        action: SplitDnsAction,
        domains: Vec<SplitDnsDomainMatcher>,
        network: SplitDnsNetwork,
        has_ip_ranges: bool,
        has_ports: bool,
    ) -> SplitDnsRule {
        SplitDnsRule { action, network, domains, has_ip_ranges, has_ports }
    }

    fn compile(rules: Vec<SplitDnsRule>, coverage_reason: Option<&str>) -> SplitDnsPolicy {
        SplitDnsPolicy::compile(&SplitDnsPolicyConfig {
            canonical_digest: "plan".to_string(),
            destination_routing_digest: "routing".to_string(),
            default_action: SplitDnsAction::Tunneled,
            rules,
            direct_resolver_candidates: vec![IpAddr::V4(Ipv4Addr::new(192, 0, 2, 53))],
            bootstrap_pins: vec![IpAddr::V4(Ipv4Addr::new(94, 140, 14, 14))],
            coverage_reason: coverage_reason.map(str::to_string),
        })
        .expect("compile policy")
    }

    fn proxy(reason: Option<&'static str>) -> DnsPolicyDecision<'static> {
        DnsPolicyDecision { plane: DnsPolicyPlane::Proxy, reason }
    }

    #[test]
    fn evaluator_preserves_first_match_and_match_outranks_geosite_in_same_rule() {
        let policy = compile(
            vec![
                rule(
                    SplitDnsAction::Direct,
                    vec![
                        matcher(SplitDnsMatcherKind::Geosite, "private"),
                        matcher(SplitDnsMatcherKind::Exact, "api.example"),
                    ],
                    SplitDnsNetwork::Both,
                    false,
                    false,
                ),
                rule(
                    SplitDnsAction::Block,
                    vec![matcher(SplitDnsMatcherKind::Suffix, "example")],
                    SplitDnsNetwork::Both,
                    false,
                    false,
                ),
            ],
            None,
        );

        assert_eq!(policy.evaluate("API.EXAMPLE."), proxy(Some("direct_plane_unbound")));
        assert_eq!(policy.evaluate("www.example"), proxy(Some("unsupported_domain_matcher")));
        assert_eq!(policy.evaluate("other.test"), proxy(Some("unsupported_domain_matcher")));
    }

    #[test]
    fn evaluator_stops_on_mixed_constraints_and_blocks_without_falling_through() {
        let policy = compile(
            vec![
                rule(
                    SplitDnsAction::Direct,
                    vec![matcher(SplitDnsMatcherKind::Exact, "mixed.example")],
                    SplitDnsNetwork::Tcp,
                    true,
                    true,
                ),
                rule(
                    SplitDnsAction::Block,
                    vec![matcher(SplitDnsMatcherKind::Suffix, "example")],
                    SplitDnsNetwork::Both,
                    false,
                    false,
                ),
            ],
            None,
        );

        assert_eq!(policy.evaluate("mixed.example"), proxy(Some("mixed_route_constraints")));
        assert_eq!(
            policy.evaluate("blocked.example"),
            DnsPolicyDecision { plane: DnsPolicyPlane::Block, reason: None }
        );
    }

    #[test]
    fn evaluator_fails_network_scoped_rules_to_proxy() {
        for (network, action) in
            [(SplitDnsNetwork::Tcp, SplitDnsAction::Block), (SplitDnsNetwork::Udp, SplitDnsAction::Direct)]
        {
            let policy = compile(
                vec![rule(action, vec![matcher(SplitDnsMatcherKind::Exact, "scoped.example")], network, false, false)],
                None,
            );

            assert_eq!(policy.evaluate("scoped.example"), proxy(Some("mixed_route_constraints")));
        }
    }

    #[test]
    fn evaluator_fails_invalid_and_empty_policy_to_proxy() {
        let policy = compile(Vec::new(), Some("route_policy_unavailable"));

        assert_eq!(policy.evaluate("bad..example"), proxy(Some("invalid_qname")));
        assert_eq!(policy.evaluate(&"a".repeat(1_025)), proxy(Some("invalid_qname")));
        assert_eq!(policy.evaluate("unmatched.example"), proxy(Some("route_policy_unavailable")));
    }
}
