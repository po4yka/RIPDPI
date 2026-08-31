use std::net::IpAddr;

use super::{DestinationRoutingPolicy, DesyncGroup};

mod environment;
mod settings;
mod worker_route;

pub use environment::EnvironmentKind;
pub use settings::{
    RuntimeAdaptiveSettings, RuntimeNetworkSettings, RuntimeProcessSettings, RuntimeQuicSettings,
    RuntimeTimeoutSettings,
};
pub use worker_route::{RuntimeSecretString, RuntimeWsTunnelWorkerRoute};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ListenConfig {
    pub listen_ip: IpAddr,
    pub listen_port: u16,
    pub bind_ip: IpAddr,
    pub auth_token: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeConfig {
    pub network: RuntimeNetworkSettings,
    pub timeouts: RuntimeTimeoutSettings,
    pub process: RuntimeProcessSettings,
    pub quic: RuntimeQuicSettings,
    pub adaptive: RuntimeAdaptiveSettings,
    pub host_autolearn: HostAutolearnSettings,
    pub destination_routing: DestinationRoutingPolicy,
    pub groups: Vec<DesyncGroup>,
    /// Maximum number of route-advance retries before giving up. Prevents
    /// unbounded retry loops when many desync groups are configured but the
    /// target is genuinely unreachable.
    pub max_route_retries: usize,
}

impl RuntimeConfig {
    pub fn actionable_group(&self) -> usize {
        self.groups.iter().position(DesyncGroup::is_actionable).unwrap_or(0)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostAutolearnSettings {
    pub enabled: bool,
    pub penalty_ttl_secs: i64,
    pub max_hosts: usize,
    pub store_path: Option<String>,
    /// When true (default), the runtime spawns a background warmup probe after
    /// the proxy listener starts. The probe attempts TLS connections to a small
    /// set of commonly-blocked domains so that the autolearn table is populated
    /// before user traffic arrives.
    pub warmup_probe_enabled: bool,
    pub network_reprobe_enabled: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ParseResult {
    Run(Box<RuntimeConfig>),
    Help,
    Version,
}
