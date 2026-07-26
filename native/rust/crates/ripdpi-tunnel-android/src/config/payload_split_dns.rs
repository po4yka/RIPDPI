use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SplitDnsPolicyPayload {
    pub(crate) canonical_digest: String,
    pub(crate) destination_routing_digest: String,
    pub(crate) default_action: String,
    pub(crate) rules: Vec<SplitDnsRulePayload>,
    pub(crate) direct_resolver_candidates: Vec<String>,
    pub(crate) bootstrap_pins: Vec<String>,
    #[serde(default)]
    pub(crate) geosite_db_path: Option<String>,
    pub(crate) coverage_reason: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SplitDnsRulePayload {
    pub(crate) action: String,
    pub(crate) network: String,
    pub(crate) domains: Vec<SplitDnsDomainMatcherPayload>,
    pub(crate) has_ip_ranges: bool,
    pub(crate) has_ports: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct SplitDnsDomainMatcherPayload {
    pub(crate) kind: String,
    pub(crate) value: String,
}
