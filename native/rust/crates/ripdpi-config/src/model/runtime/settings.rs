use crate::model::{QuicInitialMode, WsTunnelMode};

use super::{EnvironmentKind, ListenConfig, RuntimeWsTunnelWorkerRoute};

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
pub struct RuntimeAdaptiveSettings {
    pub auto_level: u32,
    pub cache_ttl: i64,
    pub cache_prefix: u8,
    pub network_scope_key: Option<String>,
    pub ws_tunnel_mode: WsTunnelMode,
    pub ws_tunnel_fake_sni: Option<String>,
    /// Explicit operator acknowledgement that the ws-tunnel fake-SNI cover
    /// domain ([`Self::ws_tunnel_fake_sni`]) disables standard TLS
    /// certificate verification. The ws-tunnel runtime refuses a `fake_sni`
    /// value at connect time unless this is `true`. Defaults to `false` for
    /// safe-by-default behaviour. See
    /// completed task `gate-fake-sni-cert-bypass-behind-allow-insecure-flag-with-telemetry` (see git history).
    pub ws_tunnel_allow_insecure_sni: bool,
    pub ws_tunnel_worker_route: Option<RuntimeWsTunnelWorkerRoute>,
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
