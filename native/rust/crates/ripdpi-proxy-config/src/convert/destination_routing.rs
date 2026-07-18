use ripdpi_config::{
    DestinationDomainMatcher, DestinationDomainMatcherKind, DestinationIpMatcher, DestinationIpMatcherKind,
    DestinationPortRange, DestinationRoutingAction, DestinationRoutingNetwork, DestinationRoutingPolicy,
    DestinationRoutingRule,
};

use crate::types::{
    ProxyConfigError, ProxyUiDestinationDomainMatcherKind, ProxyUiDestinationIpMatcherKind,
    ProxyUiDestinationRoutingAction, ProxyUiDestinationRoutingConfig, ProxyUiDestinationRoutingNetwork,
    ProxyUiDestinationRoutingRule,
};

pub(super) fn convert(policy: ProxyUiDestinationRoutingConfig) -> Result<DestinationRoutingPolicy, ProxyConfigError> {
    validate_policy(&policy)?;
    Ok(DestinationRoutingPolicy {
        rules: policy.rules.into_iter().map(convert_rule).collect(),
        default_action: convert_action(policy.default_action),
        canonical_digest: policy.canonical_digest,
    })
}

fn validate_policy(policy: &ProxyUiDestinationRoutingConfig) -> Result<(), ProxyConfigError> {
    if !policy.rules.is_empty() && policy.canonical_digest.trim().is_empty() {
        return Err(invalid("destinationRouting.canonicalDigest is required when rules are present"));
    }
    for (index, rule) in policy.rules.iter().enumerate() {
        validate_rule(index, rule)?;
    }
    Ok(())
}

fn validate_rule(index: usize, rule: &ProxyUiDestinationRoutingRule) -> Result<(), ProxyConfigError> {
    if rule.domains.is_empty() && rule.ip_ranges.is_empty() && rule.destination_ports.is_empty() {
        return Err(invalid(format!("destinationRouting.rules[{index}] has no destination matchers")));
    }
    if rule.domains.iter().any(|matcher| matcher.value.trim().is_empty()) {
        return Err(invalid(format!("destinationRouting.rules[{index}] has an empty domain matcher")));
    }
    if rule.ip_ranges.iter().any(|matcher| matcher.value.trim().is_empty()) {
        return Err(invalid(format!("destinationRouting.rules[{index}] has an empty IP matcher")));
    }
    if rule.destination_ports.iter().any(|range| range.start == 0 || range.start > range.end_inclusive) {
        return Err(invalid(format!("destinationRouting.rules[{index}] has an invalid destination port range")));
    }
    Ok(())
}

fn convert_rule(rule: ProxyUiDestinationRoutingRule) -> DestinationRoutingRule {
    DestinationRoutingRule {
        action: convert_action(rule.action),
        network: match rule.network {
            ProxyUiDestinationRoutingNetwork::Tcp => DestinationRoutingNetwork::Tcp,
            ProxyUiDestinationRoutingNetwork::Udp => DestinationRoutingNetwork::Udp,
            ProxyUiDestinationRoutingNetwork::Both => DestinationRoutingNetwork::Both,
        },
        domains: rule
            .domains
            .into_iter()
            .map(|matcher| DestinationDomainMatcher {
                kind: match matcher.kind {
                    ProxyUiDestinationDomainMatcherKind::Exact => DestinationDomainMatcherKind::Exact,
                    ProxyUiDestinationDomainMatcherKind::Suffix => DestinationDomainMatcherKind::Suffix,
                    ProxyUiDestinationDomainMatcherKind::Geosite => DestinationDomainMatcherKind::Geosite,
                },
                value: matcher.value,
            })
            .collect(),
        ip_ranges: rule
            .ip_ranges
            .into_iter()
            .map(|matcher| DestinationIpMatcher {
                kind: match matcher.kind {
                    ProxyUiDestinationIpMatcherKind::Cidr => DestinationIpMatcherKind::Cidr,
                    ProxyUiDestinationIpMatcherKind::GeoIp => DestinationIpMatcherKind::GeoIp,
                },
                value: matcher.value,
            })
            .collect(),
        destination_ports: rule
            .destination_ports
            .into_iter()
            .map(|range| DestinationPortRange { start: range.start, end_inclusive: range.end_inclusive })
            .collect(),
    }
}

fn convert_action(action: ProxyUiDestinationRoutingAction) -> DestinationRoutingAction {
    match action {
        ProxyUiDestinationRoutingAction::Tunneled => DestinationRoutingAction::Tunneled,
        ProxyUiDestinationRoutingAction::Direct => DestinationRoutingAction::Direct,
        ProxyUiDestinationRoutingAction::Block => DestinationRoutingAction::Block,
    }
}

fn invalid(message: impl Into<String>) -> ProxyConfigError {
    ProxyConfigError::InvalidConfig(message.into())
}
