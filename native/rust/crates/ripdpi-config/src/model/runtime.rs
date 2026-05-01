use std::net::IpAddr;

use super::{DesyncGroup, QuicInitialMode, WsTunnelMode};

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

/// Coarse classification of the device hosting the RIPDPI runtime.
///
/// Used by the offline learner to distinguish bandit
/// statistics gathered on real user devices (`Field`) from those gathered
/// on Android emulators or CI test devices (`Emulator`). Including this in
/// [`crate::strategy_evolver::LearningContext`] (the bandit's HashMap key)
/// segregates the two populations automatically — emulator runs cannot
/// pollute field priors, and field runs are not biased by simulator-only
/// network characteristics. `Unknown` is the conservative default for
/// builds that have not wired the platform-side detector.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum EnvironmentKind {
    #[default]
    Unknown,
    Field,
    Emulator,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct RuntimeProcessSettings {
    pub debug: i32,
    pub protect_path: Option<String>,
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
pub struct RuntimeAdaptiveSettings {
    pub auto_level: u32,
    pub cache_ttl: i64,
    pub cache_prefix: u8,
    pub network_scope_key: Option<String>,
    pub ws_tunnel_mode: WsTunnelMode,
    pub ws_tunnel_fake_sni: Option<String>,
    pub strategy_evolution: bool,
    /// Exploration rate in thousandths (0-1000 maps to 0.0-1.0). Default: 100 (= 10%).
    pub evolution_epsilon_permil: u32,
    /// Wall-clock budget for a single experiment slot in the strategy
    /// evolver. After elapsing, the next `suggest_hints()` drops the
    /// pending experiment without recording stats and re-rolls. `0`
    /// disables the TTL gate. Default 30 000 ms.
    pub evolution_experiment_ttl_ms: u64,
    /// Half-life for the recency-weighted decay applied to combo fitness
    /// in the strategy evolver. `0` disables decay. Default 3 600 000 ms
    /// (1 h).
    pub evolution_decay_half_life_ms: u64,
    /// Number of consecutive non-skip failures that trips a per-combo
    /// cooldown in the strategy evolver. `0` disables the cooldown gate.
    /// Default 3.
    pub evolution_cooldown_after_failures: u32,
    /// Length of the per-combo cooldown window in milliseconds.
    /// Default 300 000 ms (5 min).
    pub evolution_cooldown_ms: u64,
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
