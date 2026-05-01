use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub struct MapDnsConfig {
    pub address: String,
    #[serde(default = "default_mapdns_port")]
    pub port: u16,
    pub network: Option<String>,
    pub netmask: Option<String>,
    #[serde(default = "default_mapdns_cache_size")]
    pub cache_size: u32,
    pub resolver_id: Option<String>,
    pub encrypted_dns_protocol: Option<String>,
    pub encrypted_dns_host: Option<String>,
    pub encrypted_dns_port: Option<u16>,
    pub encrypted_dns_tls_server_name: Option<String>,
    #[serde(default)]
    pub encrypted_dns_bootstrap_ips: Vec<String>,
    pub encrypted_dns_doh_url: Option<String>,
    pub encrypted_dns_dnscrypt_provider_name: Option<String>,
    pub encrypted_dns_dnscrypt_public_key: Option<String>,
    #[serde(default = "default_dns_query_timeout_ms")]
    pub dns_query_timeout_ms: u32,
    #[serde(default)]
    pub resolver_fallback_active: bool,
    pub resolver_fallback_reason: Option<String>,
}

fn default_mapdns_port() -> u16 {
    53
}

fn default_mapdns_cache_size() -> u32 {
    10000
}

fn default_dns_query_timeout_ms() -> u32 {
    4000
}
