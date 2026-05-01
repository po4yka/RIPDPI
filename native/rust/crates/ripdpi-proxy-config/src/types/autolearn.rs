use ripdpi_config::HOST_AUTOLEARN_DEFAULT_MAX_HOSTS;
use serde::{Deserialize, Serialize};

use super::constants::HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_HOURS;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiHostAutolearnConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_host_autolearn_penalty_ttl_hours")]
    pub penalty_ttl_hours: i64,
    #[serde(default = "default_host_autolearn_max_hosts")]
    pub max_hosts: usize,
    #[serde(default)]
    pub store_path: Option<String>,
    #[serde(default)]
    pub network_scope_key: Option<String>,
    /// When true (default), spawn a background warmup probe after VPN start to
    /// pre-populate the autolearn table with commonly-blocked domains.
    #[serde(default = "default_warmup_probe_enabled")]
    pub warmup_probe_enabled: bool,
    #[serde(default = "default_network_reprobe_enabled")]
    pub network_reprobe_enabled: bool,
}

fn default_warmup_probe_enabled() -> bool {
    true
}

fn default_network_reprobe_enabled() -> bool {
    true
}

impl Default for ProxyUiHostAutolearnConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            penalty_ttl_hours: default_host_autolearn_penalty_ttl_hours(),
            max_hosts: default_host_autolearn_max_hosts(),
            store_path: None,
            network_scope_key: None,
            warmup_probe_enabled: default_warmup_probe_enabled(),
            network_reprobe_enabled: default_network_reprobe_enabled(),
        }
    }
}

fn default_host_autolearn_penalty_ttl_hours() -> i64 {
    HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_HOURS
}

fn default_host_autolearn_max_hosts() -> usize {
    HOST_AUTOLEARN_DEFAULT_MAX_HOSTS
}
