use std::net::IpAddr;
use std::str::FromStr;

use ripdpi_tunnel_config::{
    MapDnsConfig, SplitDnsAction, SplitDnsDomainMatcher, SplitDnsMatcherKind, SplitDnsNetwork, SplitDnsPolicyConfig,
    SplitDnsRule,
};

use super::payload::{SplitDnsDomainMatcherPayload, SplitDnsPolicyPayload, SplitDnsRulePayload, TunnelConfigPayload};

const MAX_RULES: usize = 256;
const MAX_MATCHERS_PER_RULE: usize = 256;
const MAX_TOTAL_MATCHERS: usize = 1_024;
const MAX_RESOLVER_CANDIDATES: usize = 16;
const SHA256_HEX_LENGTH: usize = 64;
const MAX_COVERAGE_REASON_LENGTH: usize = 128;
const MAX_DOMAIN_LENGTH: usize = 253;
const MAX_LABEL_LENGTH: usize = 63;

pub(crate) fn split_dns_config_from_payload(
    payload: &TunnelConfigPayload,
) -> Result<Option<SplitDnsPolicyConfig>, String> {
    payload.split_dns_policy.as_ref().map(convert_policy).transpose()
}

pub(crate) fn validate_runtime_binding(
    policy: Option<&SplitDnsPolicyConfig>,
    mapdns: Option<&MapDnsConfig>,
) -> Result<(), String> {
    let Some(policy) = policy else {
        return Ok(());
    };
    let mapdns = mapdns.ok_or_else(|| "splitDnsPolicy requires MapDNS".to_string())?;
    let protocol = mapdns
        .encrypted_dns_protocol
        .as_deref()
        .or_else(|| mapdns.encrypted_dns_doh_url.as_deref().map(|_| "doh"))
        .ok_or_else(|| "splitDnsPolicy requires an encrypted DNS resolver".to_string())?;
    let protocol = protocol.trim().to_ascii_lowercase();
    if !matches!(protocol.as_str(), "dot" | "doh" | "doq" | "dnscrypt" | "odoh") {
        return Err("splitDnsPolicy requires a supported encrypted DNS protocol".to_string());
    }
    let non_blank = |value: Option<&str>| value.is_some_and(|value| !value.trim().is_empty());
    let endpoint_complete = match protocol.as_str() {
        "dot" | "doq" => non_blank(mapdns.encrypted_dns_host.as_deref()) && mapdns.encrypted_dns_port.is_some(),
        "doh" => non_blank(mapdns.encrypted_dns_doh_url.as_deref()),
        "dnscrypt" => {
            non_blank(mapdns.encrypted_dns_host.as_deref())
                && mapdns.encrypted_dns_port.is_some()
                && non_blank(mapdns.encrypted_dns_dnscrypt_provider_name.as_deref())
                && non_blank(mapdns.encrypted_dns_dnscrypt_public_key.as_deref())
        }
        "odoh" => {
            non_blank(mapdns.encrypted_dns_odoh_proxy_url.as_deref())
                && non_blank(mapdns.encrypted_dns_odoh_proxy_operator_id.as_deref())
                && non_blank(mapdns.encrypted_dns_odoh_target_host.as_deref())
                && non_blank(mapdns.encrypted_dns_odoh_target_path.as_deref())
                && non_blank(mapdns.encrypted_dns_odoh_target_operator_id.as_deref())
                && matches!(
                    mapdns.encrypted_dns_odoh_config_source.as_deref().map(str::trim),
                    Some("custom" | "custom_bytes" | "bundled")
                )
                && mapdns.encrypted_dns_odoh_configs_hex.as_deref().is_some_and(|value| {
                    !value.is_empty()
                        && value.len().is_multiple_of(2)
                        && value.bytes().all(|byte| byte.is_ascii_hexdigit())
                })
                && mapdns.encrypted_dns_odoh_configs_retrieved_at_secs.is_some_and(|value| value > 0)
                && mapdns.encrypted_dns_odoh_configs_ttl_secs.is_some_and(|value| value > 0)
        }
        _ => false,
    };
    if !endpoint_complete {
        return Err("splitDnsPolicy requires a complete encrypted DNS endpoint".to_string());
    }
    let configured_pins = parse_addresses(&mapdns.encrypted_dns_bootstrap_ips, "encryptedDnsBootstrapIps")?;
    if policy.bootstrap_pins != configured_pins {
        return Err("splitDnsPolicy bootstrapPins must exactly match encryptedDnsBootstrapIps".to_string());
    }
    Ok(())
}

fn convert_policy(payload: &SplitDnsPolicyPayload) -> Result<SplitDnsPolicyConfig, String> {
    if payload.rules.len() > MAX_RULES {
        return Err(format!("splitDnsPolicy rules exceed {MAX_RULES}"));
    }
    let empty_policy = payload.rules.is_empty();
    validate_digest(&payload.canonical_digest, empty_policy && payload.coverage_reason.is_none(), "canonicalDigest")?;
    validate_digest(&payload.destination_routing_digest, empty_policy, "destinationRoutingDigest")?;
    if payload.coverage_reason.as_ref().is_some_and(|value| {
        value.is_empty()
            || value.len() > MAX_COVERAGE_REASON_LENGTH
            || !value
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'_' | b':' | b'-'))
    }) {
        return Err("Invalid splitDnsPolicy coverageReason".to_string());
    }
    if payload.default_action != "tunneled" {
        return Err("splitDnsPolicy defaultAction must be tunneled".to_string());
    }
    let total_matchers = payload.rules.iter().try_fold(0usize, |total, rule| {
        if rule.domains.len() > MAX_MATCHERS_PER_RULE {
            return Err(format!("splitDnsPolicy rule domain matchers exceed {MAX_MATCHERS_PER_RULE}"));
        }
        total.checked_add(rule.domains.len()).ok_or_else(|| "splitDnsPolicy matcher count overflow".to_string())
    })?;
    if total_matchers > MAX_TOTAL_MATCHERS {
        return Err(format!("splitDnsPolicy domain matchers exceed {MAX_TOTAL_MATCHERS}"));
    }
    Ok(SplitDnsPolicyConfig {
        canonical_digest: payload.canonical_digest.clone(),
        destination_routing_digest: payload.destination_routing_digest.clone(),
        default_action: parse_action(&payload.default_action)?,
        rules: payload.rules.iter().map(convert_rule).collect::<Result<Vec<_>, _>>()?,
        direct_resolver_candidates: parse_addresses(&payload.direct_resolver_candidates, "directResolverCandidates")?,
        bootstrap_pins: parse_addresses(&payload.bootstrap_pins, "bootstrapPins")?,
        coverage_reason: payload.coverage_reason.clone(),
    })
}

fn convert_rule(payload: &SplitDnsRulePayload) -> Result<SplitDnsRule, String> {
    Ok(SplitDnsRule {
        action: parse_action(&payload.action)?,
        network: match payload.network.as_str() {
            "tcp" => SplitDnsNetwork::Tcp,
            "udp" => SplitDnsNetwork::Udp,
            "both" => SplitDnsNetwork::Both,
            value => return Err(format!("Unsupported splitDnsPolicy network: {value}")),
        },
        domains: payload.domains.iter().map(convert_matcher).collect::<Result<Vec<_>, _>>()?,
        has_ip_ranges: payload.has_ip_ranges,
        has_ports: payload.has_ports,
    })
}

fn convert_matcher(payload: &SplitDnsDomainMatcherPayload) -> Result<SplitDnsDomainMatcher, String> {
    let kind = match payload.kind.as_str() {
        "exact" => SplitDnsMatcherKind::Exact,
        "suffix" => SplitDnsMatcherKind::Suffix,
        "geosite" => SplitDnsMatcherKind::Geosite,
        value => return Err(format!("Unsupported splitDnsPolicy matcher kind: {value}")),
    };
    if !is_canonical_matcher_value(&payload.value, kind) {
        return Err(format!("Invalid splitDnsPolicy domain matcher: {}", payload.value));
    }
    Ok(SplitDnsDomainMatcher { kind, value: payload.value.clone() })
}

fn parse_action(value: &str) -> Result<SplitDnsAction, String> {
    match value {
        "tunneled" => Ok(SplitDnsAction::Tunneled),
        "direct" => Ok(SplitDnsAction::Direct),
        "block" => Ok(SplitDnsAction::Block),
        value => Err(format!("Unsupported splitDnsPolicy action: {value}")),
    }
}

fn parse_addresses(values: &[String], field: &str) -> Result<Vec<IpAddr>, String> {
    if values.len() > MAX_RESOLVER_CANDIDATES {
        return Err(format!("splitDnsPolicy {field} exceed {MAX_RESOLVER_CANDIDATES}"));
    }
    if values.iter().enumerate().any(|(index, value)| values[..index].contains(value)) {
        return Err(format!("splitDnsPolicy {field} contains duplicates"));
    }
    values
        .iter()
        .map(|value| {
            if value.is_empty() || value.len() > 64 || value.trim() != value || value.contains('%') {
                return Err(format!("Invalid splitDnsPolicy {field} entry: {value}"));
            }
            IpAddr::from_str(value).map_err(|_| format!("Invalid splitDnsPolicy {field} entry: {value}"))
        })
        .collect()
}

fn validate_digest(value: &str, allow_empty: bool, field: &str) -> Result<(), String> {
    let valid_hex = value.len() == SHA256_HEX_LENGTH
        && value.bytes().all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte));
    if valid_hex || (allow_empty && value.is_empty()) { Ok(()) } else { Err(format!("Invalid splitDnsPolicy {field}")) }
}

fn is_canonical_matcher_value(value: &str, kind: SplitDnsMatcherKind) -> bool {
    if value.is_empty() || value.len() > MAX_DOMAIN_LENGTH || value.trim() != value || !value.is_ascii() {
        return false;
    }
    match kind {
        SplitDnsMatcherKind::Exact | SplitDnsMatcherKind::Suffix => {
            value == value.to_ascii_lowercase()
                && value.split('.').all(|label| {
                    !label.is_empty()
                        && label.len() <= MAX_LABEL_LENGTH
                        && !label.starts_with('-')
                        && !label.ends_with('-')
                        && label.bytes().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
                })
        }
        SplitDnsMatcherKind::Geosite => {
            value.bytes().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'-' | b'_'))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn matcher() -> SplitDnsDomainMatcherPayload {
        SplitDnsDomainMatcherPayload { kind: "exact".to_string(), value: "example.test".to_string() }
    }

    fn rule() -> SplitDnsRulePayload {
        SplitDnsRulePayload {
            action: "direct".to_string(),
            network: "both".to_string(),
            domains: vec![matcher()],
            has_ip_ranges: false,
            has_ports: false,
        }
    }

    fn policy() -> SplitDnsPolicyPayload {
        SplitDnsPolicyPayload {
            canonical_digest: "a".repeat(64),
            destination_routing_digest: "b".repeat(64),
            default_action: "tunneled".to_string(),
            rules: vec![rule()],
            direct_resolver_candidates: vec!["192.0.2.53".to_string()],
            bootstrap_pins: vec!["94.140.14.14".to_string()],
            coverage_reason: None,
        }
    }

    #[test]
    fn split_dns_policy_rejects_unbounded_or_noncanonical_fields() {
        let mut too_many_rules = policy();
        too_many_rules.rules = vec![rule(); 257];
        assert_eq!(convert_policy(&too_many_rules).expect_err("rule bound"), "splitDnsPolicy rules exceed 256",);

        let mut too_many_matchers = policy();
        too_many_matchers.rules = vec![rule(); 5];
        for rule in &mut too_many_matchers.rules {
            rule.domains = vec![matcher(); 205];
        }
        assert_eq!(
            convert_policy(&too_many_matchers).expect_err("matcher bound"),
            "splitDnsPolicy domain matchers exceed 1024",
        );

        let mut too_many_candidates = policy();
        too_many_candidates.direct_resolver_candidates = vec!["192.0.2.53".to_string(); 17];
        assert_eq!(
            convert_policy(&too_many_candidates).expect_err("candidate bound"),
            "splitDnsPolicy directResolverCandidates exceed 16",
        );

        let mut invalid_default = policy();
        invalid_default.default_action = "direct".to_string();
        assert_eq!(
            convert_policy(&invalid_default).expect_err("tunneled default"),
            "splitDnsPolicy defaultAction must be tunneled",
        );

        let mut invalid_domain = policy();
        invalid_domain.rules[0].domains[0].value = "Upper.EXAMPLE".to_string();
        assert_eq!(
            convert_policy(&invalid_domain).expect_err("canonical domain"),
            "Invalid splitDnsPolicy domain matcher: Upper.EXAMPLE",
        );

        for digest in ["short".to_string(), "A".repeat(64), "g".repeat(64)] {
            let mut invalid_digest = policy();
            invalid_digest.canonical_digest = digest;
            assert_eq!(
                convert_policy(&invalid_digest).expect_err("canonical digest"),
                "Invalid splitDnsPolicy canonicalDigest",
            );
        }

        let mut empty_with_rules = policy();
        empty_with_rules.destination_routing_digest.clear();
        assert_eq!(
            convert_policy(&empty_with_rules).expect_err("empty digest with rules"),
            "Invalid splitDnsPolicy destinationRoutingDigest",
        );

        for reason in ["has space".to_string(), "path/value".to_string(), "юникод".to_string(), "a".repeat(129)] {
            let mut invalid_reason = policy();
            invalid_reason.coverage_reason = Some(reason);
            assert_eq!(
                convert_policy(&invalid_reason).expect_err("safe coverage reason"),
                "Invalid splitDnsPolicy coverageReason",
            );
        }
    }

    #[test]
    fn split_dns_policy_allows_empty_digests_only_for_empty_policy() {
        let mut empty = policy();
        empty.rules.clear();
        empty.canonical_digest.clear();
        empty.destination_routing_digest.clear();
        empty.direct_resolver_candidates.clear();
        empty.bootstrap_pins.clear();

        assert!(convert_policy(&empty).is_ok());
    }
}
