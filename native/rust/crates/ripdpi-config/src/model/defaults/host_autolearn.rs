use super::{HOST_AUTOLEARN_DEFAULT_MAX_HOSTS, HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS, HostAutolearnSettings};

impl Default for HostAutolearnSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            store_path: None,
            warmup_probe_enabled: true,
            network_reprobe_enabled: true,
        }
    }
}
