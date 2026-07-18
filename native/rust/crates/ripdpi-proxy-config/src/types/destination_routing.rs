use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProxyUiDestinationRoutingConfig {
    #[serde(default)]
    pub rules: Vec<ProxyUiDestinationRoutingRule>,
    #[serde(default)]
    pub default_action: ProxyUiDestinationRoutingAction,
    #[serde(default)]
    pub canonical_digest: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProxyUiDestinationRoutingRule {
    pub action: ProxyUiDestinationRoutingAction,
    pub network: ProxyUiDestinationRoutingNetwork,
    #[serde(default)]
    pub domains: Vec<ProxyUiDestinationDomainMatcher>,
    #[serde(default)]
    pub ip_ranges: Vec<ProxyUiDestinationIpMatcher>,
    #[serde(default)]
    pub destination_ports: Vec<ProxyUiDestinationPortRange>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
#[non_exhaustive]
pub enum ProxyUiDestinationRoutingAction {
    #[default]
    Tunneled,
    Direct,
    Block,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
#[non_exhaustive]
pub enum ProxyUiDestinationRoutingNetwork {
    Tcp,
    Udp,
    Both,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProxyUiDestinationDomainMatcher {
    pub kind: ProxyUiDestinationDomainMatcherKind,
    pub value: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
#[non_exhaustive]
pub enum ProxyUiDestinationDomainMatcherKind {
    Exact,
    Suffix,
    Geosite,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProxyUiDestinationIpMatcher {
    pub kind: ProxyUiDestinationIpMatcherKind,
    pub value: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
#[non_exhaustive]
pub enum ProxyUiDestinationIpMatcherKind {
    Cidr,
    GeoIp,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProxyUiDestinationPortRange {
    pub start: u16,
    pub end_inclusive: u16,
}
