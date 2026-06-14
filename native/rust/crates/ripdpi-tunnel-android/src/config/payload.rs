use serde::Deserialize;

use crate::config::defaults::{
    default_log_level, default_multi_queue, default_socks5_address, default_socks5_udp, default_task_stack_size,
    default_tunnel_mtu, default_tunnel_name,
};
use crate::config::log_context::TunnelLogContext;

#[cfg(test)]
mod sample;

#[cfg(test)]
pub(crate) use sample::sample_payload;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct TunnelConfigPayload {
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
    pub(crate) log_context: Option<TunnelLogContext>,
}

pub(crate) fn parse_tunnel_config_json(json: &str) -> Result<TunnelConfigPayload, String> {
    serde_json::from_str::<TunnelConfigPayload>(json).map_err(|err| format!("Invalid tunnel config JSON: {err}"))
}
