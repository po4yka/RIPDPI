use ripdpi_proxy_config::NetworkSnapshot;
use serde::{Deserialize, Serialize};

use crate::types::{
    CircumventionTarget, ConfirmGoodDpiEvidence, DiagnosticProfileFamily, DnsTarget, DomainTarget, ProbeTask,
    QuicTarget, ScanKind, ScanPathMode, ServiceTarget, StrategyProbeRequest, TcpTarget, TelegramTarget,
    ThroughputTarget,
};
use crate::util::{default_diagnostic_profile_family, default_scan_kind};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RouteProbeConfig {
    #[serde(default = "default_route_probe_stable_flow_attempts")]
    pub stable_flow_attempts: usize,
    #[serde(default = "default_route_probe_diversity_buckets")]
    pub diversity_buckets: usize,
    #[serde(default = "default_route_probe_diversity_on_failure_only")]
    pub diversity_on_failure_only: bool,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InPathProxyCredentials {
    pub username: String,
    pub password: String,
}

impl InPathProxyCredentials {
    pub fn is_valid(&self) -> bool {
        !self.username.is_empty()
            && self.username.len() <= u8::MAX as usize
            && !self.password.is_empty()
            && self.password.len() <= u8::MAX as usize
    }
}

impl std::fmt::Display for InPathProxyCredentials {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("[REDACTED]")
    }
}

impl std::fmt::Debug for InPathProxyCredentials {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("InPathProxyCredentials([REDACTED])")
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InPathRoute {
    pub host: String,
    pub port: u16,
    pub credentials: InPathProxyCredentials,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanRequest {
    pub profile_id: String,
    pub display_name: String,
    pub path_mode: ScanPathMode,
    #[serde(default = "default_scan_kind")]
    pub kind: ScanKind,
    #[serde(default = "default_diagnostic_profile_family")]
    pub family: DiagnosticProfileFamily,
    #[serde(default)]
    pub region_tag: Option<String>,
    #[serde(default)]
    pub manual_only: bool,
    #[serde(default)]
    pub pack_refs: Vec<String>,
    pub proxy_host: Option<String>,
    pub proxy_port: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub in_path_route: Option<InPathRoute>,
    #[serde(default)]
    pub probe_tasks: Vec<ProbeTask>,
    pub domain_targets: Vec<DomainTarget>,
    pub dns_targets: Vec<DnsTarget>,
    pub tcp_targets: Vec<TcpTarget>,
    #[serde(default)]
    pub quic_targets: Vec<QuicTarget>,
    #[serde(default)]
    pub service_targets: Vec<ServiceTarget>,
    #[serde(default)]
    pub circumvention_targets: Vec<CircumventionTarget>,
    #[serde(default)]
    pub throughput_targets: Vec<ThroughputTarget>,
    pub whitelist_sni: Vec<String>,
    #[serde(default)]
    pub telegram_target: Option<TelegramTarget>,
    #[serde(default)]
    pub strategy_probe: Option<StrategyProbeRequest>,
    /// Provisional Reality post-handshake evidence captured before this scan.
    /// QUIC corroboration is performed by the strategy engine before a verdict
    /// can be finalized.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub confirm_good_dpi_evidence: Option<ConfirmGoodDpiEvidence>,
    /// Optional OS-level network state snapshot from Android ConnectivityManager/TelephonyManager.
    /// When present, used to short-circuit probes when the OS reports no network, annotate
    /// results with transport context, and emit environment metadata in the scan report.
    #[serde(default)]
    pub network_snapshot: Option<NetworkSnapshot>,
    #[serde(default)]
    pub route_probe: Option<RouteProbeConfig>,
    /// Optional scan deadline in milliseconds from now. When present, the engine will finalize
    /// the scan at this deadline. Defaults to 270 000 ms (270 s) when absent.
    #[serde(default)]
    pub scan_deadline_ms: Option<u64>,
    /// Optional SSLKEYLOGFILE-compatible path for diagnostics TLS probes.
    /// Only trusted Android callers should populate this with an app-private path.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diagnostic_tls_keylog_path: Option<String>,
}

fn default_route_probe_stable_flow_attempts() -> usize {
    2
}

fn default_route_probe_diversity_buckets() -> usize {
    3
}

fn default_route_probe_diversity_on_failure_only() -> bool {
    true
}
