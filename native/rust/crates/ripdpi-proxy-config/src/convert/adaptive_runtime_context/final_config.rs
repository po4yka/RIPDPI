use std::net::IpAddr;

use ripdpi_config::{DesyncGroup, RuntimeConfig};

use crate::presets::group_needs_delayed_connect;
use crate::types::{ProxyConfigError, ProxyUiHostAutolearnConfig, ProxyUiListenConfig};

pub(crate) fn finalize_ui_config(
    mut config: RuntimeConfig,
    groups: Vec<DesyncGroup>,
    listen: &ProxyUiListenConfig,
    host_autolearn: &ProxyUiHostAutolearnConfig,
    root_mode: bool,
    root_helper_socket_path: Option<String>,
    environment_kind: Option<&str>,
) -> Result<RuntimeConfig, ProxyConfigError> {
    config.groups = groups;
    // Tor's first SOCKS reply includes its bounded 90-second bootstrap.
    config.timeouts.connect_timeout_ms = if config.network.remote_dns_upstream.is_some() { 120_000 } else { 10_000 };
    if listen.freeze_detection_enabled {
        config.timeouts.freeze_max_stalls = 3;
    }
    config.network.delay_conn = config.groups.iter().any(group_needs_delayed_connect);
    if !matches!(config.network.listen.bind_ip, IpAddr::V6(_)) {
        config.network.ipv6 = false;
    }
    if config.host_autolearn.enabled && config.host_autolearn.store_path.is_none() {
        return Err(ProxyConfigError::InvalidConfig(
            "hostAutolearn.storePath is required when hostAutolearn.enabled is true".to_string(),
        ));
    }

    config.process.root_mode = root_mode;
    config.process.root_helper_socket_path = root_helper_socket_path;
    config.process.environment_kind = parse_environment_kind(environment_kind);

    let _ = host_autolearn;
    Ok(config)
}

/// Map the JSON wire-form string to [`ripdpi_config::EnvironmentKind`].
/// Unknown values fall back so stale Kotlin clients cannot inject arbitrary keys.
fn parse_environment_kind(value: Option<&str>) -> ripdpi_config::EnvironmentKind {
    match value {
        Some("Field") => ripdpi_config::EnvironmentKind::Field,
        Some("Emulator") => ripdpi_config::EnvironmentKind::Emulator,
        _ => ripdpi_config::EnvironmentKind::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tor_listener_budget_includes_bootstrap_without_changing_other_routes() {
        for (upstream, expected) in [(None, 10_000), (Some("127.0.0.1:11980".parse().unwrap()), 120_000)] {
            let mut config = RuntimeConfig::default();
            config.network.remote_dns_upstream = upstream;
            let result = finalize_ui_config(
                config,
                vec![],
                &ProxyUiListenConfig::default(),
                &ProxyUiHostAutolearnConfig::default(),
                false,
                None,
                None,
            )
            .unwrap();
            assert_eq!(result.timeouts.connect_timeout_ms, expected);
        }
    }
}
