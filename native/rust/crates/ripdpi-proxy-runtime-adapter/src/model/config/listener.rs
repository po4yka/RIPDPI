use std::net::SocketAddr;
use std::path::PathBuf;

use ripdpi_config::RuntimeConfig;

use super::{protect_path_owned, runtime_buffer_size};

#[derive(Clone)]
pub struct NetworkReprobeSettings {
    pub enabled: bool,
    pub protect_path: Option<String>,
}

pub fn network_reprobe_settings(config: &RuntimeConfig) -> NetworkReprobeSettings {
    NetworkReprobeSettings {
        enabled: config.host_autolearn.network_reprobe_enabled,
        protect_path: config.process.protect_path.clone(),
    }
}

pub fn udp_flow_limit(config: &RuntimeConfig) -> usize {
    config.network.max_open.max(1) as usize
}

pub fn udp_flow_at_capacity(flow_exists: bool, active_flows: usize, flow_limit: usize) -> bool {
    !flow_exists && active_flows >= flow_limit
}

pub fn listener_bind_addr(config: &RuntimeConfig) -> SocketAddr {
    SocketAddr::new(config.network.listen.listen_ip, config.network.listen.listen_port)
}

pub fn client_capacity(config: &RuntimeConfig) -> usize {
    config.network.max_open.max(1) as usize
}

pub fn max_route_retries(config: &RuntimeConfig) -> usize {
    config.max_route_retries
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TcpRouteRetrySettings {
    pub max_route_retries: usize,
}

pub fn tcp_route_retry_settings(config: &RuntimeConfig) -> TcpRouteRetrySettings {
    TcpRouteRetrySettings { max_route_retries: max_route_retries(config) }
}

pub fn route_group_count(config: &RuntimeConfig) -> usize {
    config.groups.len()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ListenerSettings {
    pub bind_addr: SocketAddr,
    pub client_capacity: usize,
    pub route_group_count: usize,
}

pub fn listener_settings(config: &RuntimeConfig) -> ListenerSettings {
    ListenerSettings {
        bind_addr: listener_bind_addr(config),
        client_capacity: client_capacity(config),
        route_group_count: route_group_count(config),
    }
}

pub fn warmup_probe_enabled(config: &RuntimeConfig) -> bool {
    config.host_autolearn.enabled && config.host_autolearn.warmup_probe_enabled
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WarmupProbeSettings {
    pub scheduler_enabled: bool,
    pub response_buffer_size: usize,
    pub ipv6_enabled: bool,
    pub protect_path: Option<String>,
}

pub fn warmup_probe_settings(config: &RuntimeConfig) -> WarmupProbeSettings {
    WarmupProbeSettings {
        scheduler_enabled: warmup_probe_enabled(config) && route_group_count(config) >= 2,
        response_buffer_size: runtime_buffer_size(config),
        ipv6_enabled: config.network.ipv6,
        protect_path: protect_path_owned(config),
    }
}

pub fn host_autolearn_enabled(config: &RuntimeConfig) -> bool {
    config.host_autolearn.enabled
}

pub fn strategy_evolution_enabled(config: &RuntimeConfig) -> bool {
    config.adaptive.strategy_evolution
}

#[derive(Clone)]
pub struct ProcessSettings {
    pub daemonize: bool,
    pub pid_file_path: Option<PathBuf>,
}

pub fn process_settings(config: &RuntimeConfig) -> ProcessSettings {
    ProcessSettings {
        daemonize: config.process.daemonize,
        pid_file_path: config.process.pid_file.as_deref().map(PathBuf::from),
    }
}

#[cfg(test)]
mod tests {
    use std::net::SocketAddr;

    use ripdpi_config::{DesyncGroup, RuntimeConfig};

    use super::*;

    #[test]
    fn udp_flow_capacity_only_rejects_new_flows_at_limit() {
        assert!(!udp_flow_at_capacity(true, 2, 2));
        assert!(udp_flow_at_capacity(false, 2, 2));
        assert!(!udp_flow_at_capacity(false, 1, 2));
    }

    #[test]
    fn tcp_route_retry_settings_project_retry_limit() {
        let mut config = RuntimeConfig { max_route_retries: 3, ..Default::default() };

        assert_eq!(tcp_route_retry_settings(&config), TcpRouteRetrySettings { max_route_retries: 3 });

        config.max_route_retries = 0;
        assert_eq!(tcp_route_retry_settings(&config), TcpRouteRetrySettings { max_route_retries: 0 });
    }

    #[test]
    fn listener_settings_project_bind_capacity_and_route_count() {
        let mut config = RuntimeConfig::default();
        config.network.max_open = 0;
        config.groups = vec![DesyncGroup::new(0), DesyncGroup::new(1)];

        assert_eq!(
            listener_settings(&config),
            ListenerSettings {
                bind_addr: SocketAddr::new(config.network.listen.listen_ip, config.network.listen.listen_port),
                client_capacity: 1,
                route_group_count: 2,
            },
        );
    }

    #[test]
    fn warmup_probe_settings_require_enablement_and_fallback_group() {
        let mut config = RuntimeConfig::default();
        config.host_autolearn.enabled = true;
        config.host_autolearn.warmup_probe_enabled = true;
        config.network.buffer_size = 512;
        config.network.ipv6 = true;
        config.process.protect_path = Some("/tmp/protect.sock".to_string());
        config.groups = vec![DesyncGroup::new(0)];

        assert_eq!(
            warmup_probe_settings(&config),
            WarmupProbeSettings {
                scheduler_enabled: false,
                response_buffer_size: 16_384,
                ipv6_enabled: true,
                protect_path: Some("/tmp/protect.sock".to_string()),
            },
        );

        config.groups.push(DesyncGroup::new(1));
        assert_eq!(
            warmup_probe_settings(&config),
            WarmupProbeSettings {
                scheduler_enabled: true,
                response_buffer_size: 16_384,
                ipv6_enabled: true,
                protect_path: Some("/tmp/protect.sock".to_string()),
            },
        );
    }
}
