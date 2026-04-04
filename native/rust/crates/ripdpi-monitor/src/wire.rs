use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::types::{
    CircumventionTarget, Diagnosis, DiagnosticProfileFamily, DnsTarget, DomainTarget, ProbeDetail, ProbeObservation,
    ProbeResult, ProbeTask, ProbeTaskFamily, QuicTarget, ScanKind, ScanPathMode, ScanProgress, ScanReport, ScanRequest,
    ServiceTarget, StrategyProbeLiveProgress, StrategyProbeReport, StrategyProbeRequest, TcpTarget, TelegramTarget,
    ThroughputTarget,
};

pub const DIAGNOSTICS_ENGINE_SCHEMA_VERSION: u32 = 1;

pub type EngineProbeTaskFamily = ProbeTaskFamily;
pub type EngineProbeTaskWire = ProbeTask;
pub type EngineObservationWire = ProbeObservation;

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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scan_deadline_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub native_log_level: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub log_context: Option<ripdpi_proxy_config::ProxyLogContext>,
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
    pub observations: Vec<EngineObservationWire>,
    #[serde(default)]
    pub engine_analysis_version: Option<String>,
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
    #[serde(default)]
    pub strategy_probe_progress: Option<StrategyProbeLiveProgress>,
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
            scan_deadline_ms: value.scan_deadline_ms,
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
            scan_deadline_ms: value.scan_deadline_ms,
            native_log_level: None,
            log_context: None,
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
            observations: value.observations,
            engine_analysis_version: value.engine_analysis_version,
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
            strategy_probe_progress: value.strategy_probe_progress,
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{
        StrategyProbeAuditAssessment, StrategyProbeAuditConfidence, StrategyProbeAuditConfidenceLevel,
        StrategyProbeAuditCoverage, StrategyProbeCandidateSummary, StrategyProbeCompletionKind,
        StrategyProbeRecommendation, StrategyProbeTargetSelection,
    };

    #[test]
    fn diagnostics_schema_version_matches_contract_fixture() {
        use golden_test_support::assert_contract_fixture;
        use serde_json::json;

        let fixture = json!({
            "schemaVersion": DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
        });
        let actual = serde_json::to_string_pretty(&fixture).expect("serialize");
        assert_contract_fixture("diagnostics_schema_version.json", &actual);
    }

    #[test]
    fn diagnostics_progress_field_manifest_matches_contract_fixture() {
        use golden_test_support::{assert_contract_fixture, extract_field_paths};

        let progress = EngineProgressWire {
            schema_version: DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
            session_id: "test-session".to_string(),
            phase: "probing".to_string(),
            completed_steps: 5,
            total_steps: 10,
            message: "Running probes".to_string(),
            is_finished: false,
            latest_probe_target: Some("example.org".to_string()),
            latest_probe_outcome: Some("reachable".to_string()),
            strategy_probe_progress: None,
        };

        let json = serde_json::to_value(&progress).expect("serialize progress");
        let paths = extract_field_paths(&json);
        let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
        assert_contract_fixture("diagnostics_progress_fields.json", &manifest);
    }

    #[test]
    fn diagnostics_scan_report_field_manifest_matches_contract_fixture() {
        use golden_test_support::{assert_contract_fixture, extract_field_paths};

        let report = EngineScanReportWire {
            schema_version: DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
            session_id: "test-session".to_string(),
            profile_id: "connectivity-basic".to_string(),
            path_mode: ScanPathMode::RawPath,
            started_at: 1000,
            finished_at: 2000,
            summary: "All probes passed".to_string(),
            results: vec![EngineProbeResultWire {
                probe_type: "dns".to_string(),
                target: "example.org".to_string(),
                outcome: "reachable".to_string(),
                details: vec![],
                probe_retry_count: Some(0),
            }],
            resolver_recommendation: Some(ResolverRecommendationWire {
                trigger_outcome: "dns_tampering".to_string(),
                selected_resolver_id: "cloudflare".to_string(),
                selected_protocol: "doh".to_string(),
                selected_endpoint: "1.1.1.1:443".to_string(),
                selected_bootstrap_ips: vec!["1.1.1.1".to_string()],
                selected_host: "cloudflare-dns.com".to_string(),
                selected_port: 443,
                selected_tls_server_name: "cloudflare-dns.com".to_string(),
                selected_doh_url: "https://cloudflare-dns.com/dns-query".to_string(),
                selected_dnscrypt_provider_name: String::new(),
                selected_dnscrypt_public_key: String::new(),
                rationale: "DNS tampering detected".to_string(),
                applied_temporarily: false,
                persistable: true,
            }),
            strategy_probe_report: Some(StrategyProbeReport {
                suite_id: "full_matrix_v1".to_string(),
                tcp_candidates: vec![StrategyProbeCandidateSummary {
                    id: "baseline_current".to_string(),
                    label: "Current strategy".to_string(),
                    family: "baseline_current".to_string(),
                    outcome: "skipped".to_string(),
                    rationale: "DNS tampering detected before fallback; TCP strategy escalation skipped".to_string(),
                    succeeded_targets: 0,
                    total_targets: 6,
                    weighted_success_score: 0,
                    total_weight: 18,
                    quality_score: 0,
                    proxy_config_json: None,
                    notes: vec![],
                    average_latency_ms: None,
                    skipped: true,
                }],
                quic_candidates: vec![StrategyProbeCandidateSummary {
                    id: "quic_disabled".to_string(),
                    label: "Current QUIC strategy".to_string(),
                    family: "quic_disabled".to_string(),
                    outcome: "skipped".to_string(),
                    rationale: "DNS tampering detected before fallback; QUIC strategy escalation skipped".to_string(),
                    succeeded_targets: 0,
                    total_targets: 2,
                    weighted_success_score: 0,
                    total_weight: 4,
                    quality_score: 0,
                    proxy_config_json: None,
                    notes: vec![],
                    average_latency_ms: None,
                    skipped: true,
                }],
                recommendation: StrategyProbeRecommendation {
                    tcp_candidate_id: "baseline_current".to_string(),
                    tcp_candidate_label: "Current strategy".to_string(),
                    quic_candidate_id: "quic_disabled".to_string(),
                    quic_candidate_label: "Current QUIC strategy".to_string(),
                    rationale:
                        "dns_tampering classified before fallback; keep current strategy and prefer resolver override"
                            .to_string(),
                    recommended_proxy_config_json: "{}".to_string(),
                },
                completion_kind: StrategyProbeCompletionKind::DnsShortCircuited,
                audit_assessment: Some(StrategyProbeAuditAssessment {
                    dns_short_circuited: true,
                    coverage: StrategyProbeAuditCoverage {
                        tcp_candidates_planned: 11,
                        tcp_candidates_executed: 0,
                        tcp_candidates_skipped: 1,
                        tcp_candidates_not_applicable: 0,
                        quic_candidates_planned: 2,
                        quic_candidates_executed: 0,
                        quic_candidates_skipped: 1,
                        quic_candidates_not_applicable: 0,
                        tcp_winner_succeeded_targets: 0,
                        tcp_winner_total_targets: 6,
                        quic_winner_succeeded_targets: 0,
                        quic_winner_total_targets: 2,
                        matrix_coverage_percent: 0,
                        winner_coverage_percent: 0,
                        tcp_winner_coverage_percent: 0,
                        quic_winner_coverage_percent: 0,
                    },
                    confidence: StrategyProbeAuditConfidence {
                        level: StrategyProbeAuditConfidenceLevel::Low,
                        score: 35,
                        rationale: "Baseline DNS tampering short-circuited the audit before fallback candidates ran"
                            .to_string(),
                        warnings: vec![
                            "Baseline DNS tampering short-circuited the audit before fallback candidates ran."
                                .to_string(),
                        ],
                    },
                }),
                target_selection: Some(StrategyProbeTargetSelection {
                    cohort_id: "global-core".to_string(),
                    cohort_label: "Global core".to_string(),
                    domain_hosts: vec!["www.youtube.com".to_string(), "discord.com".to_string()],
                    quic_hosts: vec!["www.youtube.com".to_string()],
                }),
            }),
            observations: vec![],
            engine_analysis_version: Some("1.0".to_string()),
            diagnoses: vec![],
            classifier_version: Some("1.0".to_string()),
            pack_versions: BTreeMap::from([("core".to_string(), 1)]),
        };

        let json = serde_json::to_value(&report).expect("serialize report");
        let paths = extract_field_paths(&json);
        let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
        assert_contract_fixture("diagnostics_scan_report_fields.json", &manifest);
    }
}
