use std::net::IpAddr;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SplitDnsAction {
    Tunneled,
    Direct,
    Block,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SplitDnsNetwork {
    Tcp,
    Udp,
    Both,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SplitDnsMatcherKind {
    Exact,
    Suffix,
    Geosite,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SplitDnsDomainMatcher {
    pub kind: SplitDnsMatcherKind,
    pub value: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SplitDnsRule {
    pub action: SplitDnsAction,
    pub network: SplitDnsNetwork,
    pub domains: Vec<SplitDnsDomainMatcher>,
    pub has_ip_ranges: bool,
    pub has_ports: bool,
}

/// Validated Android JNI-only split DNS policy. The standalone YAML parser
/// never constructs this field and remains on its independent v2 envelope.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SplitDnsPolicyConfig {
    pub canonical_digest: String,
    pub destination_routing_digest: String,
    pub default_action: SplitDnsAction,
    pub rules: Vec<SplitDnsRule>,
    pub direct_resolver_candidates: Vec<IpAddr>,
    pub bootstrap_pins: Vec<IpAddr>,
    pub coverage_reason: Option<String>,
}
