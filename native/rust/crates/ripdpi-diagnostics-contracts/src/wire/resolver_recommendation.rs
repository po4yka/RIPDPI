use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ResolverRecommendationWire {
    pub trigger_outcome: String,
    pub selected_resolver_id: String,
    pub selected_protocol: String,
    pub selected_endpoint: String,
    #[serde(default)]
    pub selected_bootstrap_ips: Vec<String>,
    #[serde(default)]
    pub selected_host: String,
    #[serde(default)]
    pub selected_port: u16,
    #[serde(default)]
    pub selected_tls_server_name: String,
    #[serde(default)]
    pub selected_doh_url: String,
    #[serde(default)]
    pub selected_dnscrypt_provider_name: String,
    #[serde(default)]
    pub selected_dnscrypt_public_key: String,
    pub rationale: String,
    #[serde(default)]
    pub applied_temporarily: bool,
    #[serde(default)]
    pub persistable: bool,
}
