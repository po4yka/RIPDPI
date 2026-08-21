use crate::types::{ProbeResult, ScanProgress, ScanReport, ScanRequest};

use super::{
    DIAGNOSTICS_ENGINE_SCHEMA_VERSION, EngineProbeResultWire, EngineProgressWire, EngineScanReportWire,
    EngineScanRequestWire,
};

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
            in_path_route: value.in_path_route,
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
            confirm_good_dpi_evidence: value.confirm_good_dpi_evidence,
            network_snapshot: value.network_snapshot,
            route_probe: value.route_probe,
            scan_deadline_ms: value.scan_deadline_ms,
            diagnostic_tls_keylog_path: value.diagnostic_tls_keylog_path,
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
            in_path_route: value.in_path_route,
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
            confirm_good_dpi_evidence: value.confirm_good_dpi_evidence,
            network_snapshot: value.network_snapshot,
            route_probe: value.route_probe,
            scan_deadline_ms: value.scan_deadline_ms,
            native_log_level: None,
            log_context: None,
            diagnostic_tls_keylog_path: value.diagnostic_tls_keylog_path,
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
            report_disposition: value.report_disposition,
            completion_kind: value.completion_kind,
            termination_reason: value.termination_reason,
            results: value.results.into_iter().map(EngineProbeResultWire::from).collect(),
            resolver_recommendation: None,
            strategy_probe_report: value.strategy_probe_report,
            confirm_good_dpi_verdict: value.confirm_good_dpi_verdict,
            observations: value.observations,
            engine_analysis_version: value.engine_analysis_version,
            diagnoses: value.diagnoses,
            classifier_version: value.classifier_version,
            pack_versions: value.pack_versions,
            execution_plan: value.execution_plan,
            candidate_runtime_cleanup: value.candidate_runtime_cleanup,
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
