use ripdpi_config::{
    DestinationDomainMatcher, DestinationDomainMatcherKind, DestinationIpMatcher, DestinationIpMatcherKind,
    DestinationPortRange, DestinationRoutingAction, DestinationRoutingNetwork, DestinationRoutingPolicy,
    DestinationRoutingRule,
};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::str::FromStr;

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
    if policy.default_action != ProxyUiDestinationRoutingAction::Tunneled {
        return Err(invalid("destinationRouting.defaultAction must be tunneled"));
    }
    if policy.rules.len() > MAX_RULES {
        return Err(invalid(format!("destinationRouting.rules exceeds {MAX_RULES} entries")));
    }
    let mut total_matchers = 0usize;
    let mut canonical_length = 0usize;
    for (index, rule) in policy.rules.iter().enumerate() {
        validate_rule(index, rule)?;
        let matcher_count = rule.domains.len() + rule.ip_ranges.len() + rule.destination_ports.len();
        total_matchers += matcher_count;
        if total_matchers > MAX_TOTAL_MATCHERS {
            return Err(invalid(format!("destinationRouting matcher count exceeds {MAX_TOTAL_MATCHERS}")));
        }
        canonical_length += rule.domains.iter().map(|matcher| matcher.value.len()).sum::<usize>()
            + rule.ip_ranges.iter().map(|matcher| matcher.value.len()).sum::<usize>()
            + rule
                .destination_ports
                .iter()
                .map(|range| decimal_length(range.start) + decimal_length(range.end_inclusive))
                .sum::<usize>()
            + matcher_count;
        if canonical_length > MAX_CANONICAL_LENGTH {
            return Err(invalid(format!(
                "destinationRouting canonical matcher data exceeds {MAX_CANONICAL_LENGTH} characters"
            )));
        }
    }
    if policy.rules.is_empty() {
        if !policy.canonical_digest.is_empty() {
            return Err(invalid("destinationRouting.canonicalDigest must be empty when rules are absent"));
        }
    } else {
        if !is_lowercase_sha256(&policy.canonical_digest) {
            return Err(invalid("destinationRouting.canonicalDigest must be 64 lowercase hexadecimal characters"));
        }
        if policy.canonical_digest != compute_canonical_digest(&policy.rules) {
            return Err(invalid("destinationRouting.canonicalDigest does not match the canonical rules"));
        }
    }
    Ok(())
}

fn validate_rule(index: usize, rule: &ProxyUiDestinationRoutingRule) -> Result<(), ProxyConfigError> {
    validate_field_count(index, "domains", rule.domains.len())?;
    validate_field_count(index, "ipRanges", rule.ip_ranges.len())?;
    validate_field_count(index, "destinationPorts", rule.destination_ports.len())?;
    if rule.domains.is_empty() && rule.ip_ranges.is_empty() && rule.destination_ports.is_empty() {
        return Err(invalid(format!("destinationRouting.rules[{index}] has no destination matchers")));
    }
    if !all_unique(rule.domains.iter().map(|matcher| (matcher.kind, matcher.value.as_str()))) {
        return Err(invalid(format!("destinationRouting.rules[{index}] has duplicate domain matchers")));
    }
    if !all_unique(rule.ip_ranges.iter().map(|matcher| (matcher.kind, matcher.value.as_str()))) {
        return Err(invalid(format!("destinationRouting.rules[{index}] has duplicate IP matchers")));
    }
    if !all_unique(rule.destination_ports.iter().map(|range| (range.start, range.end_inclusive))) {
        return Err(invalid(format!("destinationRouting.rules[{index}] has duplicate destination port ranges")));
    }
    for matcher in &rule.domains {
        if matcher.value.len() > MAX_RAW_ENTRY_LENGTH {
            return Err(invalid(format!("destinationRouting.rules[{index}] has an oversized domain matcher")));
        }
        let valid = match matcher.kind {
            ProxyUiDestinationDomainMatcherKind::Exact | ProxyUiDestinationDomainMatcherKind::Suffix => {
                is_canonical_host(&matcher.value)
            }
            ProxyUiDestinationDomainMatcherKind::Geosite => is_canonical_geo_token(&matcher.value),
        };
        if !valid {
            return Err(invalid(format!("destinationRouting.rules[{index}] has a non-canonical domain matcher")));
        }
    }
    for matcher in &rule.ip_ranges {
        if matcher.value.len() > MAX_RAW_ENTRY_LENGTH {
            return Err(invalid(format!("destinationRouting.rules[{index}] has an oversized IP matcher")));
        }
        let valid = match matcher.kind {
            ProxyUiDestinationIpMatcherKind::Cidr => canonical_cidr(&matcher.value).as_deref() == Some(&matcher.value),
            ProxyUiDestinationIpMatcherKind::GeoIp => is_canonical_geo_token(&matcher.value),
        };
        if !valid {
            return Err(invalid(format!("destinationRouting.rules[{index}] has a non-canonical IP matcher")));
        }
    }
    if rule.destination_ports.iter().any(|range| range.start == 0 || range.start > range.end_inclusive) {
        return Err(invalid(format!("destinationRouting.rules[{index}] has an invalid destination port range")));
    }
    Ok(())
}

fn validate_field_count(index: usize, field: &str, count: usize) -> Result<(), ProxyConfigError> {
    if count > MAX_ENTRIES_PER_FIELD {
        return Err(invalid(format!(
            "destinationRouting.rules[{index}].{field} exceeds {MAX_ENTRIES_PER_FIELD} entries"
        )));
    }
    Ok(())
}

fn all_unique<T: Eq>(values: impl IntoIterator<Item = T>) -> bool {
    let mut seen = Vec::new();
    values.into_iter().all(|value| {
        if seen.contains(&value) {
            false
        } else {
            seen.push(value);
            true
        }
    })
}

fn is_canonical_host(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= MAX_TOKEN_LENGTH
        && value.bytes().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-' || byte == b'.')
        && !value.starts_with('.')
        && !value.ends_with('.')
        && !value.contains("..")
        && value.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= MAX_HOST_LABEL_LENGTH
                && label.as_bytes().first().is_some_and(u8::is_ascii_alphanumeric)
                && label.as_bytes().last().is_some_and(u8::is_ascii_alphanumeric)
        })
}

fn is_canonical_geo_token(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= MAX_TOKEN_LENGTH
        && value.bytes().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'-' | b'_'))
}

fn canonical_cidr(value: &str) -> Option<String> {
    let (address, prefix) = value.split_once('/')?;
    if prefix.contains('/') {
        return None;
    }
    let prefix = prefix.parse::<u8>().ok()?;
    match IpAddr::from_str(address).ok()? {
        IpAddr::V4(address) if prefix <= 32 => {
            let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
            Some(format!("{}/{}", Ipv4Addr::from(u32::from(address) & mask), prefix))
        }
        IpAddr::V6(address) if prefix <= 128 => {
            let mask = if prefix == 0 { 0 } else { u128::MAX << (128 - prefix) };
            let network = Ipv6Addr::from(u128::from(address) & mask);
            let canonical =
                network.segments().iter().map(|segment| format!("{segment:x}")).collect::<Vec<_>>().join(":");
            Some(format!("{canonical}/{prefix}"))
        }
        _ => None,
    }
}

pub(crate) fn compute_canonical_digest(rules: &[ProxyUiDestinationRoutingRule]) -> String {
    let mut canonical = Vec::new();
    digest_put(&mut canonical, "destination-routing-policy-v1");
    digest_put(&mut canonical, "TUNNELED");
    for rule in rules {
        digest_put(&mut canonical, action_name(rule.action));
        digest_put(&mut canonical, network_name(rule.network));

        let mut domains = rule.domains.iter().collect::<Vec<_>>();
        domains.sort_by_key(|matcher| (domain_kind_rank(matcher.kind), matcher.value.as_str()));
        for matcher in domains {
            digest_put(&mut canonical, &format!("d:{}:{}", domain_kind_name(matcher.kind), matcher.value));
        }

        let mut ip_ranges = rule.ip_ranges.iter().collect::<Vec<_>>();
        ip_ranges.sort_by_key(|matcher| (ip_kind_rank(matcher.kind), matcher.value.as_str()));
        for matcher in ip_ranges {
            digest_put(&mut canonical, &format!("i:{}:{}", ip_kind_name(matcher.kind), matcher.value));
        }

        let mut ports = rule.destination_ports.iter().collect::<Vec<_>>();
        ports.sort_by_key(|range| (range.start, range.end_inclusive));
        for range in ports {
            digest_put(&mut canonical, &format!("p:{}:{}", range.start, range.end_inclusive));
        }
        canonical.push(0);
    }
    sha256(&canonical).iter().map(|byte| format!("{byte:02x}")).collect()
}

fn digest_put(canonical: &mut Vec<u8>, value: &str) {
    canonical.extend_from_slice(value.len().to_string().as_bytes());
    canonical.push(b':');
    canonical.extend_from_slice(value.as_bytes());
}

fn sha256(input: &[u8]) -> [u8; 32] {
    let bit_length = (input.len() as u64).wrapping_mul(8);
    let mut padded = input.to_vec();
    padded.push(0x80);
    while padded.len() % 64 != 56 {
        padded.push(0);
    }
    padded.extend_from_slice(&bit_length.to_be_bytes());

    let mut state = SHA256_INITIAL_STATE;
    for chunk in padded.as_chunks::<64>().0 {
        let mut words = [0u32; 64];
        for (index, bytes) in chunk.as_chunks::<4>().0.iter().enumerate() {
            words[index] = u32::from_be_bytes(*bytes);
        }
        for index in 16..64 {
            let s0 = words[index - 15].rotate_right(7) ^ words[index - 15].rotate_right(18) ^ (words[index - 15] >> 3);
            let s1 = words[index - 2].rotate_right(17) ^ words[index - 2].rotate_right(19) ^ (words[index - 2] >> 10);
            words[index] = words[index - 16].wrapping_add(s0).wrapping_add(words[index - 7]).wrapping_add(s1);
        }

        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = state;
        for index in 0..64 {
            let sum1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let choice = (e & f) ^ ((!e) & g);
            let temp1 = h
                .wrapping_add(sum1)
                .wrapping_add(choice)
                .wrapping_add(SHA256_ROUND_CONSTANTS[index])
                .wrapping_add(words[index]);
            let sum0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let majority = (a & b) ^ (a & c) ^ (b & c);
            let temp2 = sum0.wrapping_add(majority);

            h = g;
            g = f;
            f = e;
            e = d.wrapping_add(temp1);
            d = c;
            c = b;
            b = a;
            a = temp1.wrapping_add(temp2);
        }

        for (slot, value) in state.iter_mut().zip([a, b, c, d, e, f, g, h]) {
            *slot = slot.wrapping_add(value);
        }
    }

    let mut output = [0u8; 32];
    for (chunk, value) in output.as_chunks_mut::<4>().0.iter_mut().zip(state) {
        chunk.copy_from_slice(&value.to_be_bytes());
    }
    output
}

const fn action_name(action: ProxyUiDestinationRoutingAction) -> &'static str {
    match action {
        ProxyUiDestinationRoutingAction::Tunneled => "TUNNELED",
        ProxyUiDestinationRoutingAction::Direct => "DIRECT",
        ProxyUiDestinationRoutingAction::Block => "BLOCK",
    }
}

const fn network_name(network: ProxyUiDestinationRoutingNetwork) -> &'static str {
    match network {
        ProxyUiDestinationRoutingNetwork::Tcp => "TCP",
        ProxyUiDestinationRoutingNetwork::Udp => "UDP",
        ProxyUiDestinationRoutingNetwork::Both => "BOTH",
    }
}

const fn domain_kind_name(kind: ProxyUiDestinationDomainMatcherKind) -> &'static str {
    match kind {
        ProxyUiDestinationDomainMatcherKind::Exact => "EXACT",
        ProxyUiDestinationDomainMatcherKind::Suffix => "SUFFIX",
        ProxyUiDestinationDomainMatcherKind::Geosite => "GEOSITE",
    }
}

const fn domain_kind_rank(kind: ProxyUiDestinationDomainMatcherKind) -> u8 {
    match kind {
        ProxyUiDestinationDomainMatcherKind::Exact => 0,
        ProxyUiDestinationDomainMatcherKind::Suffix => 1,
        ProxyUiDestinationDomainMatcherKind::Geosite => 2,
    }
}

const fn ip_kind_name(kind: ProxyUiDestinationIpMatcherKind) -> &'static str {
    match kind {
        ProxyUiDestinationIpMatcherKind::Cidr => "CIDR",
        ProxyUiDestinationIpMatcherKind::GeoIp => "GEO_IP",
    }
}

const fn ip_kind_rank(kind: ProxyUiDestinationIpMatcherKind) -> u8 {
    match kind {
        ProxyUiDestinationIpMatcherKind::Cidr => 0,
        ProxyUiDestinationIpMatcherKind::GeoIp => 1,
    }
}

fn is_lowercase_sha256(value: &str) -> bool {
    value.len() == SHA256_HEX_LENGTH && value.bytes().all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn decimal_length(value: u16) -> usize {
    value.to_string().len()
}

const MAX_RULES: usize = 256;
const MAX_ENTRIES_PER_FIELD: usize = 256;
const MAX_TOTAL_MATCHERS: usize = 1_024;
const MAX_TOKEN_LENGTH: usize = 253;
const MAX_RAW_ENTRY_LENGTH: usize = 512;
const MAX_CANONICAL_LENGTH: usize = 65_536;
const MAX_HOST_LABEL_LENGTH: usize = 63;
const SHA256_HEX_LENGTH: usize = 64;
const SHA256_INITIAL_STATE: [u32; 8] =
    [0x6a09_e667, 0xbb67_ae85, 0x3c6e_f372, 0xa54f_f53a, 0x510e_527f, 0x9b05_688c, 0x1f83_d9ab, 0x5be0_cd19];
const SHA256_ROUND_CONSTANTS: [u32; 64] = [
    0x428a_2f98,
    0x7137_4491,
    0xb5c0_fbcf,
    0xe9b5_dba5,
    0x3956_c25b,
    0x59f1_11f1,
    0x923f_82a4,
    0xab1c_5ed5,
    0xd807_aa98,
    0x1283_5b01,
    0x2431_85be,
    0x550c_7dc3,
    0x72be_5d74,
    0x80de_b1fe,
    0x9bdc_06a7,
    0xc19b_f174,
    0xe49b_69c1,
    0xefbe_4786,
    0x0fc1_9dc6,
    0x240c_a1cc,
    0x2de9_2c6f,
    0x4a74_84aa,
    0x5cb0_a9dc,
    0x76f9_88da,
    0x983e_5152,
    0xa831_c66d,
    0xb003_27c8,
    0xbf59_7fc7,
    0xc6e0_0bf3,
    0xd5a7_9147,
    0x06ca_6351,
    0x1429_2967,
    0x27b7_0a85,
    0x2e1b_2138,
    0x4d2c_6dfc,
    0x5338_0d13,
    0x650a_7354,
    0x766a_0abb,
    0x81c2_c92e,
    0x9272_2c85,
    0xa2bf_e8a1,
    0xa81a_664b,
    0xc24b_8b70,
    0xc76c_51a3,
    0xd192_e819,
    0xd699_0624,
    0xf40e_3585,
    0x106a_a070,
    0x19a4_c116,
    0x1e37_6c08,
    0x2748_774c,
    0x34b0_bcb5,
    0x391c_0cb3,
    0x4ed8_aa4a,
    0x5b9c_ca4f,
    0x682e_6ff3,
    0x748f_82ee,
    0x78a5_636f,
    0x84c8_7814,
    0x8cc7_0208,
    0x90be_fffa,
    0xa450_6ceb,
    0xbef9_a3f7,
    0xc671_78f2,
];

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
