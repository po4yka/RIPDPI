use std::net::IpAddr;

use super::{DestinationRoutingPolicy, DesyncGroup, QuicInitialMode, WsTunnelMode};

mod environment;
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

#[derive(Clone, PartialEq, Eq)]
pub struct RuntimeSecretString(String);

impl RuntimeSecretString {
    pub fn new(value: String) -> Self {
        Self(value)
    }

    pub fn expose_secret(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Debug for RuntimeSecretString {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("<redacted>")
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RuntimeWsTunnelWorkerRoute {
    url: String,
    bearer: RuntimeSecretString,
}

impl RuntimeWsTunnelWorkerRoute {
    pub fn parse(url: String, bearer: String) -> Result<Self, String> {
        validate_worker_url(&url)?;
        validate_worker_bearer(&bearer)?;
        Ok(Self { url, bearer: RuntimeSecretString::new(bearer) })
    }

    pub fn url(&self) -> &str {
        &self.url
    }

    pub fn bearer(&self) -> &RuntimeSecretString {
        &self.bearer
    }
}

fn validate_worker_url(url: &str) -> Result<(), String> {
    validate_worker_url_characters(url)?;
    let without_scheme = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("wss://"))
        .ok_or_else(|| "Cloudflare Worker URL scheme must be https or wss".to_string())?;
    if without_scheme.contains('#') {
        return Err("Cloudflare Worker URL must not contain a fragment".to_string());
    }
    let authority_end = without_scheme.find(['/', '?']).unwrap_or(without_scheme.len());
    let authority = &without_scheme[..authority_end];
    if authority.is_empty() {
        return Err("Cloudflare Worker URL must contain a hostname".to_string());
    }
    if authority.contains('@') {
        return Err("Cloudflare Worker URL must not contain userinfo".to_string());
    }
    validate_worker_authority(authority)?;
    Ok(())
}

fn validate_worker_authority(authority: &str) -> Result<(), String> {
    if let Some(stripped) = authority.strip_prefix('[') {
        let (host, rest) = stripped
            .split_once(']')
            .ok_or_else(|| "Cloudflare Worker IPv6 host is missing closing bracket".to_string())?;
        if host.is_empty() {
            return Err("Cloudflare Worker URL must contain a hostname".to_string());
        }
        host.parse::<std::net::Ipv6Addr>()
            .map_err(|_| "Cloudflare Worker URL contains an invalid IPv6 hostname".to_string())?;
        if !rest.is_empty() {
            validate_worker_port(
                rest.strip_prefix(':')
                    .ok_or_else(|| "Cloudflare Worker IPv6 host has invalid port delimiter".to_string())?,
            )?;
        }
        return Ok(());
    }
    if let Some((host, port)) = authority.rsplit_once(':') {
        if host.contains(':') {
            return Err("Cloudflare Worker IPv6 host must use brackets".to_string());
        }
        if host.is_empty() {
            return Err("Cloudflare Worker URL must contain a hostname".to_string());
        }
        validate_worker_hostname(host)?;
        validate_worker_port(port)?;
        return Ok(());
    }
    if authority.contains(':') {
        return Err("Cloudflare Worker IPv6 host must use brackets".to_string());
    }
    validate_worker_hostname(authority)?;
    Ok(())
}

fn validate_worker_url_characters(url: &str) -> Result<(), String> {
    if !url.is_ascii() || url.bytes().any(|byte| byte <= b' ' || byte == 0x7f) {
        return Err("Cloudflare Worker URL must contain only visible ASCII characters".to_string());
    }
    let bytes = url.as_bytes();
    for (index, byte) in bytes.iter().copied().enumerate() {
        let allowed = byte.is_ascii_alphanumeric() || b"-._~:/?[]@!$&'()*+,;=%".contains(&byte);
        if !allowed {
            return Err("Cloudflare Worker URL contains an invalid URI character".to_string());
        }
        if byte == b'%'
            && (index + 2 >= bytes.len()
                || !bytes[index + 1].is_ascii_hexdigit()
                || !bytes[index + 2].is_ascii_hexdigit())
        {
            return Err("Cloudflare Worker URL contains invalid percent encoding".to_string());
        }
    }
    Ok(())
}

fn validate_worker_hostname(host: &str) -> Result<(), String> {
    if host.is_empty()
        || host.split('.').any(|label| {
            label.is_empty()
                || label.starts_with('-')
                || label.ends_with('-')
                || !label.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        })
    {
        return Err("Cloudflare Worker URL contains an invalid hostname".to_string());
    }
    Ok(())
}

fn validate_worker_port(value: &str) -> Result<(), String> {
    value
        .parse::<u16>()
        .ok()
        .filter(|port| *port != 0)
        .map(|_| ())
        .ok_or_else(|| "Cloudflare Worker URL contains an invalid port".to_string())
}

fn validate_worker_bearer(bearer: &str) -> Result<(), String> {
    if bearer.is_empty() || bearer.len() > 4096 || !is_rfc6750_bearer_token(bearer.as_bytes()) {
        return Err("Cloudflare Worker bearer must be a bounded RFC 6750 bearer token".to_string());
    }
    Ok(())
}

fn is_rfc6750_bearer_token(bearer: &[u8]) -> bool {
    let padding_start = bearer.iter().position(|byte| *byte == b'=').unwrap_or(bearer.len());
    padding_start > 0
        && bearer[..padding_start].iter().all(|byte| byte.is_ascii_alphanumeric() || b"-._~+/".contains(byte))
        && bearer[padding_start..].iter().all(|byte| *byte == b'=')
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
