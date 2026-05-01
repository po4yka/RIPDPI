use serde::{Deserialize, Serialize};

use super::constants::{WARP_ENDPOINT_SELECTION_AUTOMATIC, WARP_ROUTE_MODE_OFF};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiWarpManualEndpointConfig {
    #[serde(default)]
    pub host: String,
    #[serde(default)]
    pub ipv4: String,
    #[serde(default)]
    pub ipv6: String,
    #[serde(default = "default_warp_manual_endpoint_port")]
    pub port: i32,
}

impl Default for ProxyUiWarpManualEndpointConfig {
    fn default() -> Self {
        Self {
            host: String::new(),
            ipv4: String::new(),
            ipv6: String::new(),
            port: default_warp_manual_endpoint_port(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiWarpAmneziaConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub jc: i32,
    #[serde(default)]
    pub jmin: i32,
    #[serde(default)]
    pub jmax: i32,
    #[serde(default)]
    pub h1: i64,
    #[serde(default)]
    pub h2: i64,
    #[serde(default)]
    pub h3: i64,
    #[serde(default)]
    pub h4: i64,
    #[serde(default)]
    pub s1: i32,
    #[serde(default)]
    pub s2: i32,
    #[serde(default)]
    pub s3: i32,
    #[serde(default)]
    pub s4: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiWarpConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_warp_route_mode")]
    pub route_mode: String,
    #[serde(default)]
    pub route_hosts: String,
    #[serde(default = "super::common::default_true")]
    pub built_in_rules_enabled: bool,
    #[serde(default = "default_warp_endpoint_selection_mode")]
    pub endpoint_selection_mode: String,
    #[serde(default)]
    pub manual_endpoint: ProxyUiWarpManualEndpointConfig,
    #[serde(default = "super::common::default_true")]
    pub scanner_enabled: bool,
    #[serde(default = "default_warp_scanner_parallelism")]
    pub scanner_parallelism: i32,
    #[serde(default = "default_warp_scanner_max_rtt_ms")]
    pub scanner_max_rtt_ms: i32,
    #[serde(default)]
    pub amnezia: ProxyUiWarpAmneziaConfig,
    #[serde(default = "default_warp_local_socks_host")]
    pub local_socks_host: String,
    #[serde(default = "default_warp_local_socks_port")]
    pub local_socks_port: i32,
}

impl Default for ProxyUiWarpConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            route_mode: default_warp_route_mode(),
            route_hosts: String::new(),
            built_in_rules_enabled: true,
            endpoint_selection_mode: default_warp_endpoint_selection_mode(),
            manual_endpoint: ProxyUiWarpManualEndpointConfig::default(),
            scanner_enabled: true,
            scanner_parallelism: default_warp_scanner_parallelism(),
            scanner_max_rtt_ms: default_warp_scanner_max_rtt_ms(),
            amnezia: ProxyUiWarpAmneziaConfig::default(),
            local_socks_host: default_warp_local_socks_host(),
            local_socks_port: default_warp_local_socks_port(),
        }
    }
}

fn default_warp_route_mode() -> String {
    WARP_ROUTE_MODE_OFF.to_string()
}

fn default_warp_endpoint_selection_mode() -> String {
    WARP_ENDPOINT_SELECTION_AUTOMATIC.to_string()
}

fn default_warp_manual_endpoint_port() -> i32 {
    2408
}

fn default_warp_scanner_parallelism() -> i32 {
    10
}

fn default_warp_scanner_max_rtt_ms() -> i32 {
    1500
}

fn default_warp_local_socks_host() -> String {
    "127.0.0.1".to_string()
}

fn default_warp_local_socks_port() -> i32 {
    11888
}
