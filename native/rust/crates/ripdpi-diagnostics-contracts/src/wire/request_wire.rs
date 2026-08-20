use serde::{Deserialize, Serialize};

use crate::types::{
    CircumventionTarget, ConfirmGoodDpiEvidence, DiagnosticProfileFamily, DnsTarget, DomainTarget, InPathRoute,
    QuicTarget, RouteProbeConfig, ScanKind, ScanPathMode, ServiceTarget, StrategyProbeRequest, TcpTarget,
    TelegramTarget, ThroughputTarget,
};

use super::{EngineProbeTaskWire, default_diagnostic_profile_family, default_scan_kind};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineScanRequestWire {
    pub schema_version: u32,
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
    pub pack_refs: Vec<String>,
    pub proxy_host: Option<String>,
    pub proxy_port: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub in_path_route: Option<InPathRoute>,
    #[serde(default)]
    pub probe_tasks: Vec<EngineProbeTaskWire>,
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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub confirm_good_dpi_evidence: Option<ConfirmGoodDpiEvidence>,
    #[serde(default)]
    pub network_snapshot: Option<ripdpi_proxy_config::NetworkSnapshot>,
    #[serde(default)]
    pub route_probe: Option<RouteProbeConfig>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scan_deadline_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub native_log_level: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub log_context: Option<ripdpi_proxy_config::ProxyLogContext>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diagnostic_tls_keylog_path: Option<String>,
}
