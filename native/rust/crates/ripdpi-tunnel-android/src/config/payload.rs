use serde::Deserialize;

use crate::config::defaults::{
    default_log_level, default_multi_queue, default_socks5_address, default_socks5_udp, default_task_stack_size,
    default_tunnel_mtu, default_tunnel_name,
};
use crate::config::log_context::TunnelLogContext;

pub(crate) const TUNNEL_JNI_CONFIG_SCHEMA_VERSION: u32 = 3;

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

#[cfg(test)]
mod sample;

#[cfg(test)]
pub(crate) use sample::sample_payload;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct TunnelConfigPayload {
    pub(crate) schema_version: u32,
    #[serde(default = "default_tunnel_name")]
    pub(crate) tunnel_name: String,
    #[serde(default = "default_tunnel_mtu")]
    pub(crate) tunnel_mtu: u32,
    #[serde(default = "default_multi_queue")]
    pub(crate) multi_queue: bool,
    pub(crate) tunnel_ipv4: Option<String>,
    pub(crate) tunnel_ipv6: Option<String>,
    #[serde(default = "default_socks5_address")]
    pub(crate) socks5_address: String,
    pub(crate) socks5_port: u16,
    #[serde(default = "default_socks5_udp")]
    pub(crate) socks5_udp: Option<String>,
    pub(crate) socks5_udp_address: Option<String>,
    pub(crate) socks5_pipeline: Option<bool>,
    pub(crate) username: Option<String>,
    pub(crate) password: Option<String>,
    pub(crate) mapdns_address: Option<String>,
    pub(crate) mapdns_port: Option<u16>,
    pub(crate) mapdns_network: Option<String>,
    pub(crate) mapdns_netmask: Option<String>,
    pub(crate) mapdns_cache_size: Option<u32>,
    pub(crate) encrypted_dns_resolver_id: Option<String>,
    pub(crate) encrypted_dns_protocol: Option<String>,
    pub(crate) encrypted_dns_host: Option<String>,
    pub(crate) encrypted_dns_port: Option<u16>,
    pub(crate) encrypted_dns_tls_server_name: Option<String>,
    pub(crate) encrypted_dns_doh_url: Option<String>,
    pub(crate) encrypted_dns_dnscrypt_provider_name: Option<String>,
    pub(crate) encrypted_dns_dnscrypt_public_key: Option<String>,
    pub(crate) encrypted_dns_odoh_proxy_url: Option<String>,
    pub(crate) encrypted_dns_odoh_proxy_operator_id: Option<String>,
    pub(crate) encrypted_dns_odoh_target_host: Option<String>,
    pub(crate) encrypted_dns_odoh_target_path: Option<String>,
    pub(crate) encrypted_dns_odoh_target_operator_id: Option<String>,
    pub(crate) encrypted_dns_odoh_config_source: Option<String>,
    pub(crate) encrypted_dns_odoh_configs_hex: Option<String>,
    pub(crate) encrypted_dns_odoh_configs_retrieved_at_secs: Option<u64>,
    pub(crate) encrypted_dns_odoh_configs_ttl_secs: Option<u64>,
    pub(crate) encrypted_dns_tls_roots_pem: Option<String>,
    #[serde(default)]
    pub(crate) encrypted_dns_bootstrap_ips: Vec<String>,
    pub(crate) dns_query_timeout_ms: Option<u32>,
    pub(crate) resolver_fallback_active: Option<bool>,
    pub(crate) resolver_fallback_reason: Option<String>,
    pub(crate) route_dns_through_socks5: Option<bool>,
    pub(crate) split_dns_policy: Option<SplitDnsPolicyPayload>,
    pub(crate) strategy_chain_yaml: Option<String>,
    pub(crate) protect_path: Option<String>,
    pub(crate) root_helper_socket_path: Option<String>,
    pub(crate) lua_script_base_dir: Option<String>,
    #[serde(default = "default_task_stack_size")]
    pub(crate) task_stack_size: u32,
    pub(crate) tcp_buffer_size: Option<u32>,
    pub(crate) udp_recv_buffer_size: Option<u32>,
    pub(crate) udp_copy_buffer_nums: Option<u32>,
    pub(crate) max_session_count: Option<u32>,
    pub(crate) connect_timeout_ms: Option<u32>,
    pub(crate) tcp_read_write_timeout_ms: Option<u32>,
    pub(crate) udp_read_write_timeout_ms: Option<u32>,
    #[serde(default = "default_log_level")]
    pub(crate) log_level: String,
    pub(crate) limit_nofile: Option<u32>,
    #[serde(default)]
    pub(crate) filter_injected_resets: Option<bool>,
    #[serde(default)]
    pub(crate) webrtc_protection_enabled: bool,
    #[serde(default)]
    pub(crate) uid_policy_mode: Option<String>,
    #[serde(default)]
    pub(crate) uid_policy_uids: Vec<u32>,
    #[serde(default)]
    pub(crate) uid_policy_allow_icmp: bool,
    #[serde(default)]
    pub(crate) log_context: Option<TunnelLogContext>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TunnelConfigSchemaEnvelope {
    schema_version: Option<u64>,
}

pub(crate) fn parse_tunnel_config_json(json: &str) -> Result<TunnelConfigPayload, String> {
    let envelope = serde_json::from_str::<TunnelConfigSchemaEnvelope>(json)
        .map_err(|err| format!("Invalid tunnel config JSON: {err}"))?;
    let schema_version = envelope
        .schema_version
        .ok_or_else(|| format!("Missing tunnel config schemaVersion; expected {TUNNEL_JNI_CONFIG_SCHEMA_VERSION}"))?;
    if schema_version != u64::from(TUNNEL_JNI_CONFIG_SCHEMA_VERSION) {
        return Err(format!(
            "Unsupported tunnel config schemaVersion: {schema_version}; expected {TUNNEL_JNI_CONFIG_SCHEMA_VERSION}"
        ));
    }
    serde_json::from_str::<TunnelConfigPayload>(json).map_err(|err| format!("Invalid tunnel config JSON: {err}"))
}
