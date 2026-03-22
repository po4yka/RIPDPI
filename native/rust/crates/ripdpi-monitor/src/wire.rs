use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::types::{
    CircumventionTarget, Diagnosis, DiagnosticProfileFamily, DnsTarget, DomainTarget, ProbeDetail, ProbeResult,
    ProbeTask, ProbeTaskFamily, QuicTarget, ScanKind, ScanPathMode, ScanProgress, ScanReport, ScanRequest,
    ServiceTarget, StrategyProbeReport, StrategyProbeRequest, TcpTarget, TelegramTarget, ThroughputTarget,
};

pub const DIAGNOSTICS_ENGINE_SCHEMA_VERSION: u32 = 1;

pub type EngineProbeTaskFamily = ProbeTaskFamily;
pub type EngineProbeTaskWire = ProbeTask;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ResolverRecommendationWire {
    pub trigger_outcome: String,
    pub selected_resolver_id: String,
    pub selected_protocol: String,
    pub selected_endpoint: String,
    #[serde(default)]
    pub selected_bootstrap_ips: Vec<String>,
    #[serde(default)]
    pub selected_host: String,
    #[serde(default)]
    pub selected_port: u16,
    #[serde(default)]
    pub selected_tls_server_name: String,
    #[serde(default)]
    pub selected_doh_url: String,
    #[serde(default)]
    pub selected_dnscrypt_provider_name: String,
    #[serde(default)]
    pub selected_dnscrypt_public_key: String,
    pub rationale: String,
    #[serde(default)]
    pub applied_temporarily: bool,
    #[serde(default)]
    pub persistable: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineScanRequestWire {
    #[serde(default = "default_schema_version")]
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
    #[serde(default)]
    pub network_snapshot: Option<ripdpi_proxy_config::NetworkSnapshot>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineProbeResultWire {
    pub probe_type: String,
    pub target: String,
    pub outcome: String,
    #[serde(default)]
    pub details: Vec<ProbeDetail>,
    #[serde(default)]
    pub probe_retry_count: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineScanReportWire {
    #[serde(default = "default_schema_version")]
    pub schema_version: u32,
    pub session_id: String,
    pub profile_id: String,
    pub path_mode: ScanPathMode,
    pub started_at: u64,
    pub finished_at: u64,
    pub summary: String,
    #[serde(default)]
    pub results: Vec<EngineProbeResultWire>,
    #[serde(default)]
    pub resolver_recommendation: Option<ResolverRecommendationWire>,
    #[serde(default)]
    pub strategy_probe_report: Option<StrategyProbeReport>,
    #[serde(default)]
    pub diagnoses: Vec<Diagnosis>,
    #[serde(default)]
    pub classifier_version: Option<String>,
    #[serde(default)]
    pub pack_versions: BTreeMap<String, u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineProgressWire {
    #[serde(default = "default_schema_version")]
    pub schema_version: u32,
    pub session_id: String,
    pub phase: String,
    pub completed_steps: usize,
    pub total_steps: usize,
    pub message: String,
    #[serde(default)]
    pub is_finished: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub latest_probe_target: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub latest_probe_outcome: Option<String>,
}

impl From<EngineScanRequestWire> for ScanRequest {
    fn from(value: EngineScanRequestWire) -> Self {
        ScanRequest {
            profile_id: value.profile_id,
            display_name: value.display_name,
            path_mode: value.path_mode,
            kind: value.kind,
            family: value.family,
            region_tag: value.region_tag,
            manual_only: false,
            pack_refs: value.pack_refs,
            proxy_host: value.proxy_host,
            proxy_port: value.proxy_port,
            probe_tasks: value.probe_tasks,
            domain_targets: value.domain_targets,
            dns_targets: value.dns_targets,
            tcp_targets: value.tcp_targets,
            quic_targets: value.quic_targets,
            service_targets: value.service_targets,
            circumvention_targets: value.circumvention_targets,
            throughput_targets: value.throughput_targets,
            whitelist_sni: value.whitelist_sni,
            telegram_target: value.telegram_target,
            strategy_probe: value.strategy_probe,
            network_snapshot: value.network_snapshot,
        }
    }
}

impl From<ScanRequest> for EngineScanRequestWire {
    fn from(value: ScanRequest) -> Self {
        Self {
            schema_version: DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
            profile_id: value.profile_id,
            display_name: value.display_name,
            path_mode: value.path_mode,
            kind: value.kind,
            family: value.family,
            region_tag: value.region_tag,
            pack_refs: value.pack_refs,
            proxy_host: value.proxy_host,
            proxy_port: value.proxy_port,
            probe_tasks: value.probe_tasks,
            domain_targets: value.domain_targets,
            dns_targets: value.dns_targets,
            tcp_targets: value.tcp_targets,
            quic_targets: value.quic_targets,
            service_targets: value.service_targets,
            circumvention_targets: value.circumvention_targets,
            throughput_targets: value.throughput_targets,
            whitelist_sni: value.whitelist_sni,
            telegram_target: value.telegram_target,
            strategy_probe: value.strategy_probe,
            network_snapshot: value.network_snapshot,
        }
    }
}

impl From<ScanReport> for EngineScanReportWire {
    fn from(value: ScanReport) -> Self {
        Self {
            schema_version: DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
            session_id: value.session_id,
            profile_id: value.profile_id,
            path_mode: value.path_mode,
            started_at: value.started_at,
            finished_at: value.finished_at,
            summary: value.summary,
            results: value.results.into_iter().map(EngineProbeResultWire::from).collect(),
            resolver_recommendation: None,
            strategy_probe_report: value.strategy_probe_report,
            diagnoses: value.diagnoses,
            classifier_version: value.classifier_version,
            pack_versions: value.pack_versions,
        }
    }
}

impl From<ProbeResult> for EngineProbeResultWire {
    fn from(value: ProbeResult) -> Self {
        Self {
            probe_type: value.probe_type,
            target: value.target,
            outcome: value.outcome,
            details: value.details,
            probe_retry_count: None,
        }
    }
}

impl From<ScanProgress> for EngineProgressWire {
    fn from(value: ScanProgress) -> Self {
        Self {
            schema_version: DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
            session_id: value.session_id,
            phase: value.phase,
            completed_steps: value.completed_steps,
            total_steps: value.total_steps,
            message: value.message,
            is_finished: value.is_finished,
            latest_probe_target: value.latest_probe_target,
            latest_probe_outcome: value.latest_probe_outcome,
        }
    }
}

fn default_schema_version() -> u32 {
    DIAGNOSTICS_ENGINE_SCHEMA_VERSION
}

fn default_scan_kind() -> ScanKind {
    ScanKind::Connectivity
}

fn default_diagnostic_profile_family() -> DiagnosticProfileFamily {
    DiagnosticProfileFamily::General
}
