use std::net::IpAddr;

use ripdpi_config::{DesyncGroup, RuntimeConfig};
use ripdpi_packets::{IS_HTTP, IS_HTTPS};

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
    config.timeouts.connect_timeout_ms = 10_000;
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

fn group_needs_delayed_connect(group: &DesyncGroup) -> bool {
    !group.matches.filters.hosts.is_empty() || (group.matches.proto & (IS_HTTP | IS_HTTPS)) != 0
}
