use serde::Deserialize;

use crate::config::defaults::{
    default_log_level, default_multi_queue, default_socks5_address, default_socks5_udp, default_task_stack_size,
    default_tunnel_mtu, default_tunnel_name,
};
use crate::config::log_context::TunnelLogContext;

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
    #[serde(default)]
    pub(crate) encrypted_dns_bootstrap_ips: Vec<String>,
    pub(crate) dns_query_timeout_ms: Option<u32>,
    pub(crate) resolver_fallback_active: Option<bool>,
    pub(crate) resolver_fallback_reason: Option<String>,
    pub(crate) strategy_chain_yaml: Option<String>,
    pub(crate) protect_path: Option<String>,
    pub(crate) root_helper_socket_path: Option<String>,
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

#[cfg(test)]
pub(crate) fn sample_payload() -> TunnelConfigPayload {
    TunnelConfigPayload {
        tunnel_name: "tun0".to_string(),
        tunnel_mtu: 1500,
        multi_queue: false,
        tunnel_ipv4: None,
        tunnel_ipv6: None,
        socks5_address: "127.0.0.1".to_string(),
        socks5_port: 1080,
        socks5_udp: Some("udp".to_string()),
        socks5_udp_address: None,
        socks5_pipeline: None,
        username: None,
        password: None,
        mapdns_address: None,
        mapdns_port: None,
        mapdns_network: None,
        mapdns_netmask: None,
        mapdns_cache_size: None,
        encrypted_dns_resolver_id: None,
        encrypted_dns_protocol: None,
        encrypted_dns_host: None,
        encrypted_dns_port: None,
        encrypted_dns_tls_server_name: None,
        encrypted_dns_doh_url: None,
        encrypted_dns_dnscrypt_provider_name: None,
        encrypted_dns_dnscrypt_public_key: None,
        encrypted_dns_bootstrap_ips: Vec::new(),
        dns_query_timeout_ms: None,
        resolver_fallback_active: None,
        resolver_fallback_reason: None,
        strategy_chain_yaml: None,
        protect_path: None,
        root_helper_socket_path: None,
        task_stack_size: default_task_stack_size(),
        tcp_buffer_size: None,
        udp_recv_buffer_size: None,
        udp_copy_buffer_nums: None,
        max_session_count: None,
        connect_timeout_ms: None,
        tcp_read_write_timeout_ms: None,
        udp_read_write_timeout_ms: None,
        log_level: default_log_level(),
        limit_nofile: None,
        filter_injected_resets: None,
        log_context: None,
    }
}
