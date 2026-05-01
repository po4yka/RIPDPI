use serde::{Deserialize, Serialize};

use super::constants::RELAY_KIND_OFF;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiRelayFinalmaskConfig {
    #[serde(default = "default_relay_finalmask_type")]
    pub r#type: String,
    #[serde(default)]
    pub header_hex: String,
    #[serde(default)]
    pub trailer_hex: String,
    #[serde(default)]
    pub rand_range: String,
    #[serde(default)]
    pub sudoku_seed: String,
    #[serde(default)]
    pub fragment_packets: i32,
    #[serde(default)]
    pub fragment_min_bytes: i32,
    #[serde(default)]
    pub fragment_max_bytes: i32,
}

fn default_relay_cloudflare_tunnel_mode() -> String {
    "consume_existing".to_string()
}

fn default_relay_finalmask_type() -> String {
    "off".to_string()
}

impl Default for ProxyUiRelayFinalmaskConfig {
    fn default() -> Self {
        Self {
            r#type: default_relay_finalmask_type(),
            header_hex: String::new(),
            trailer_hex: String::new(),
            rand_range: String::new(),
            sudoku_seed: String::new(),
            fragment_packets: 0,
            fragment_min_bytes: 0,
            fragment_max_bytes: 0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiRelayConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_relay_kind")]
    pub kind: String,
    #[serde(default)]
    pub profile_id: String,
    #[serde(default)]
    pub outbound_bind_ip: String,
    #[serde(default)]
    pub server: String,
    #[serde(default = "default_relay_server_port")]
    pub server_port: i32,
    #[serde(default)]
    pub server_name: String,
    #[serde(default)]
    pub reality_public_key: String,
    #[serde(default)]
    pub reality_short_id: String,
    #[serde(default)]
    pub vless_transport: String,
    #[serde(default)]
    pub xhttp_path: String,
    #[serde(default)]
    pub xhttp_host: String,
    #[serde(default = "default_relay_cloudflare_tunnel_mode")]
    pub cloudflare_tunnel_mode: String,
    #[serde(default)]
    pub cloudflare_publish_local_origin_url: String,
    #[serde(default)]
    pub cloudflare_credentials_ref: String,
    #[serde(default)]
    pub chain_entry_server: String,
    #[serde(default = "default_relay_server_port")]
    pub chain_entry_port: i32,
    #[serde(default)]
    pub chain_entry_server_name: String,
    #[serde(default)]
    pub chain_entry_public_key: String,
    #[serde(default)]
    pub chain_entry_short_id: String,
    #[serde(default)]
    pub chain_exit_server: String,
    #[serde(default = "default_relay_server_port")]
    pub chain_exit_port: i32,
    #[serde(default)]
    pub chain_exit_server_name: String,
    #[serde(default)]
    pub chain_exit_public_key: String,
    #[serde(default)]
    pub chain_exit_short_id: String,
    #[serde(default)]
    pub masque_url: String,
    #[serde(default = "super::common::default_true")]
    pub masque_use_http2_fallback: bool,
    #[serde(default)]
    pub finalmask: ProxyUiRelayFinalmaskConfig,
    #[serde(default = "default_relay_local_socks_host")]
    pub local_socks_host: String,
    #[serde(default = "default_relay_local_socks_port")]
    pub local_socks_port: i32,
    #[serde(default)]
    pub udp_enabled: bool,
    #[serde(default = "super::common::default_true")]
    pub tcp_fallback_enabled: bool,
}

impl Default for ProxyUiRelayConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            kind: default_relay_kind(),
            profile_id: String::new(),
            outbound_bind_ip: String::new(),
            server: String::new(),
            server_port: default_relay_server_port(),
            server_name: String::new(),
            reality_public_key: String::new(),
            reality_short_id: String::new(),
            vless_transport: String::new(),
            xhttp_path: String::new(),
            xhttp_host: String::new(),
            cloudflare_tunnel_mode: default_relay_cloudflare_tunnel_mode(),
            cloudflare_publish_local_origin_url: String::new(),
            cloudflare_credentials_ref: String::new(),
            chain_entry_server: String::new(),
            chain_entry_port: default_relay_server_port(),
            chain_entry_server_name: String::new(),
            chain_entry_public_key: String::new(),
            chain_entry_short_id: String::new(),
            chain_exit_server: String::new(),
            chain_exit_port: default_relay_server_port(),
            chain_exit_server_name: String::new(),
            chain_exit_public_key: String::new(),
            chain_exit_short_id: String::new(),
            masque_url: String::new(),
            masque_use_http2_fallback: true,
            finalmask: ProxyUiRelayFinalmaskConfig::default(),
            local_socks_host: default_relay_local_socks_host(),
            local_socks_port: default_relay_local_socks_port(),
            udp_enabled: false,
            tcp_fallback_enabled: true,
        }
    }
}

fn default_relay_kind() -> String {
    RELAY_KIND_OFF.to_string()
}

fn default_relay_server_port() -> i32 {
    443
}

fn default_relay_local_socks_host() -> String {
    "127.0.0.1".to_string()
}

fn default_relay_local_socks_port() -> i32 {
    11980
}
