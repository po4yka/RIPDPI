#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DestinationRoutingPolicy {
    pub rules: Vec<DestinationRoutingRule>,
    pub default_action: DestinationRoutingAction,
    pub canonical_digest: String,
}

impl Default for DestinationRoutingPolicy {
    fn default() -> Self {
        Self { rules: Vec::new(), default_action: DestinationRoutingAction::Tunneled, canonical_digest: String::new() }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DestinationRoutingRule {
    pub action: DestinationRoutingAction,
    pub network: DestinationRoutingNetwork,
    pub domains: Vec<DestinationDomainMatcher>,
    pub ip_ranges: Vec<DestinationIpMatcher>,
    pub destination_ports: Vec<DestinationPortRange>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DestinationRoutingAction {
    Tunneled,
    Direct,
    Block,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DestinationRoutingNetwork {
    Tcp,
    Udp,
    Both,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DestinationDomainMatcher {
    pub kind: DestinationDomainMatcherKind,
    pub value: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DestinationDomainMatcherKind {
    Exact,
    Suffix,
    Geosite,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DestinationIpMatcher {
    pub kind: DestinationIpMatcherKind,
    pub value: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DestinationIpMatcherKind {
    Cidr,
    GeoIp,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DestinationPortRange {
    pub start: u16,
    pub end_inclusive: u16,
}
