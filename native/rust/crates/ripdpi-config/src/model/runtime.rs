use std::net::IpAddr;

use super::{DestinationRoutingPolicy, DesyncGroup, QuicInitialMode};

mod adaptive;
mod environment;
pub use adaptive::{RuntimeAdaptiveSettings, RuntimeSecretString, RuntimeWsTunnelWorkerRoute};
pub use environment::EnvironmentKind;

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
    /// Maximum number of route-advance retries before giving up.  Prevents
    /// unbounded retry loops when many desync groups are configured but the
    /// target is genuinely unreachable.
    pub max_route_retries: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeNetworkSettings {
    pub listen: ListenConfig,
    pub resolve: bool,
    pub ipv6: bool,
    pub udp: bool,
    pub transparent: bool,
    pub http_connect: bool,
    /// Mixed inbound: a single listener that speaks SOCKS5, SOCKS4 *and*
    /// HTTP CONNECT, dispatched by peeking the first request byte
    /// (`0x05` → SOCKS5, `0x04` → SOCKS4, `'C'` → HTTP CONNECT). Distinct
    /// from `http_connect`, which is an exclusive HTTP-only listener.
    pub mixed: bool,
    pub shadowsocks: bool,
    pub delay_conn: bool,
    pub tfo: bool,
    pub max_open: i32,
    pub buffer_size: usize,
    pub default_ttl: u8,
    pub custom_ttl: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RuntimeTimeoutSettings {
    pub timeout_ms: u32,
    pub partial_timeout_ms: u32,
    pub timeout_count_limit: i32,
    pub timeout_bytes_limit: i32,
    pub wait_send: bool,
    pub await_interval: i32,
    pub connect_timeout_ms: u32,
    pub freeze_window_ms: u32,
    pub freeze_min_bytes: u32,
    pub freeze_max_stalls: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct RuntimeProcessSettings {
    pub debug: i32,
    pub protect_path: Option<String>,
    pub geoip_db_path: Option<String>,
    pub geosite_db_path: Option<String>,
    pub daemonize: bool,
    pub pid_file: Option<String>,
    pub root_mode: bool,
    pub root_helper_socket_path: Option<String>,
    /// Coarse environment classification supplied by the platform-side
    /// detector (Android Build properties, etc). Plumbed through the JNI
    /// config bridge alongside [`Self::root_mode`]. Defaults to
    /// [`EnvironmentKind::Unknown`] when unset.
    pub environment_kind: EnvironmentKind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RuntimeQuicSettings {
    pub initial_mode: QuicInitialMode,
    pub support_v1: bool,
    pub support_v2: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostAutolearnSettings {
    pub enabled: bool,
    pub penalty_ttl_secs: i64,
    pub max_hosts: usize,
    pub store_path: Option<String>,
    /// When true (default), the runtime spawns a background warmup probe after
    /// the proxy listener starts.  The probe attempts TLS connections to a small
    /// set of commonly-blocked domains so that the autolearn table is populated
    /// before user traffic arrives.
    pub warmup_probe_enabled: bool,
    pub network_reprobe_enabled: bool,
}

impl RuntimeConfig {
    pub fn actionable_group(&self) -> usize {
        self.groups.iter().position(DesyncGroup::is_actionable).unwrap_or(0)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ParseResult {
    Run(Box<RuntimeConfig>),
    Help,
    Version,
}
