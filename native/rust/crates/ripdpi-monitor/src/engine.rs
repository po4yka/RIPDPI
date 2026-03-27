mod plan;
mod report;
mod runners;
mod runtime;

use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::{push_event, set_progress, set_report};
use crate::transport::transport_for_request;
#[cfg(test)]
use crate::types::ProbeResult;
#[cfg(test)]
use crate::types::ProbeTaskFamily;
use crate::types::{ScanKind, ScanProgress, ScanRequest, SharedState};

use plan::build_execution_plan;
#[cfg(test)]
use plan::connectivity_stage_order;
use report::{build_report, connectivity_summary};
use runners::execution_coordinator;
#[cfg(test)]
use runtime::ExecutionStageId;
use runtime::{ExecutionRuntime, RunnerOutcome};

pub(crate) fn run_engine_scan(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
) {
    let started_at = crate::util::now_ms();
    let transport = transport_for_request(&request);
    let mut plan = match build_execution_plan(session_id.clone(), request.clone(), started_at, transport.clone()) {
        Ok(plan) => plan,
        Err(message) => {
            let report =
                build_report(session_id.clone(), request, started_at, message, Vec::new(), Vec::new(), None, None);
            set_report(&shared, report);
            set_progress(
                &shared,
                ScanProgress {
                    session_id,
                    phase: "finished".to_string(),
                    completed_steps: 1,
                    total_steps: 1,
                    message: "Diagnostics finished".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: None,
                    strategy_probe_progress: None,
                },
            );
            return;
        }
    };
    let coordinator = execution_coordinator();
    plan.total_steps = coordinator.total_steps(&plan);

    set_progress(
        &shared,
        ScanProgress {
            session_id: plan.session_id.clone(),
            phase: "starting".to_string(),
            completed_steps: 0,
            total_steps: plan.total_steps,
            message: format!("Preparing {}", plan.request.display_name),
            is_finished: false,
            latest_probe_target: None,
            latest_probe_outcome: None,
            strategy_probe_progress: None,
        },
    );
    push_event(
        &shared,
        &plan.session_id,
        &plan.request.profile_id,
        &plan.request.path_mode,
        "engine",
        "info",
        format!(
            "Starting {} in {:?} transport={}",
            plan.request.display_name,
            plan.request.path_mode,
            crate::transport::describe_transport(&plan.transport),
        ),
    );

    let mut runtime = ExecutionRuntime::new(shared.clone(), cancel);
    runtime.set_scan_deadline(std::time::Instant::now() + std::time::Duration::from_secs(270));
    match coordinator.run(&plan, &mut runtime, tls_verifier.as_ref()) {
        RunnerOutcome::Cancelled => {
            let report = build_report(
                plan.session_id.clone(),
                plan.request.clone(),
                plan.started_at,
                "Scan cancelled".to_string(),
                runtime.results,
                runtime.observations,
                None,
                None,
            );
            set_report(&shared, report);
            push_event(
                &shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                "engine",
                "warn",
                "Diagnostics cancelled".to_string(),
            );
            set_progress(
                &shared,
                ScanProgress {
                    session_id: plan.session_id,
                    phase: "cancelled".to_string(),
                    completed_steps: runtime.completed_steps,
                    total_steps: plan.total_steps,
                    message: "Diagnostics cancelled".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: None,
                    strategy_probe_progress: None,
                },
            );
        }
        RunnerOutcome::Finished => {
            if let Some(report) = runtime.final_report {
                set_report(&shared, report);
            }
            set_progress(
                &shared,
                ScanProgress {
                    session_id: plan.session_id,
                    phase: "finished".to_string(),
                    completed_steps: plan.total_steps,
                    total_steps: plan.total_steps,
                    message: "Diagnostics finished".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: None,
                    strategy_probe_progress: None,
                },
            );
        }
        RunnerOutcome::Completed => {
            let summary = match plan.request.kind {
                ScanKind::Connectivity => connectivity_summary(&runtime.results, &plan.request.path_mode),
                ScanKind::StrategyProbe => {
                    runtime.strategy.summary.clone().unwrap_or_else(|| "Automatic probing finished".to_string())
                }
            };
            let report = build_report(
                plan.session_id.clone(),
                plan.request.clone(),
                plan.started_at,
                summary,
                runtime.results,
                runtime.observations,
                runtime.strategy.strategy_probe_report,
                None,
            );
            set_report(&shared, report);
            push_event(
                &shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                "engine",
                "info",
                "Diagnostics finished".to_string(),
            );
            set_progress(
                &shared,
                ScanProgress {
                    session_id: plan.session_id,
                    phase: "finished".to_string(),
                    completed_steps: plan.total_steps,
                    total_steps: plan.total_steps,
                    message: "Diagnostics finished".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: None,
                    strategy_probe_progress: None,
                },
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connectivity_summary_reports_bucket_breakdown() {
        let mut results = vec![probe("network_environment", "wifi", "network_available")];
        results.extend((0..24).map(|index| probe("dns_integrity", format!("dns-{index}"), "dns_match")));
        results.extend((0..4).map(|index| probe("domain_reachability", format!("tls-{index}"), "tls_ok")));
        results.push(probe("tcp_fat_header", "16.15.219.241:443 (AWS)", "whitelist_sni_ok"));
        results.push(probe("tcp_fat_header", "172.67.70.222:443 (Cloudflare)", "whitelist_sni_failed"));

        assert_eq!(
            connectivity_summary(&results, &crate::types::ScanPathMode::RawPath),
            "31 completed · 30 healthy · 1 failed",
        );
    }

    #[test]
    fn connectivity_summary_omits_zero_value_non_healthy_buckets() {
        assert_eq!(connectivity_summary(&[], &crate::types::ScanPathMode::RawPath), "0 completed · 0 healthy");
    }

    #[test]
    fn connectivity_stage_order_deduplicates_requested_families() {
        let request = scan_request(
            ScanKind::Connectivity,
            vec![
                crate::types::ProbeTask {
                    family: ProbeTaskFamily::Tcp,
                    target_id: "tcp-1".to_string(),
                    label: "tcp".to_string(),
                },
                crate::types::ProbeTask {
                    family: ProbeTaskFamily::Dns,
                    target_id: "dns-1".to_string(),
                    label: "dns".to_string(),
                },
                crate::types::ProbeTask {
                    family: ProbeTaskFamily::Tcp,
                    target_id: "tcp-2".to_string(),
                    label: "tcp2".to_string(),
                },
                crate::types::ProbeTask {
                    family: ProbeTaskFamily::Service,
                    target_id: "svc-1".to_string(),
                    label: "service".to_string(),
                },
            ],
            None,
        );

        assert_eq!(
            connectivity_stage_order(&request),
            vec![
                ExecutionStageId::Environment,
                ExecutionStageId::Tcp,
                ExecutionStageId::Dns,
                ExecutionStageId::Service,
            ],
        );
    }

    #[test]
    fn run_engine_scan_records_planning_errors_in_report_and_progress() {
        let shared = Arc::new(Mutex::new(SharedState::default()));
        let cancel = Arc::new(AtomicBool::new(false));
        let request = scan_request(
            ScanKind::StrategyProbe,
            Vec::new(),
            Some(crate::types::StrategyProbeRequest {
                suite_id: "phase1".to_string(),
                base_proxy_config_json: None,
                target_selection: None,
            }),
        );

        run_engine_scan(shared.clone(), cancel, "session-1".to_string(), request, None);

        let state = shared.lock().expect("shared state lock");
        let report = state.report.clone().expect("report");
        let progress = state.progress.clone().expect("progress");

        assert_eq!(report.summary, "strategy_probe scan requires baseProxyConfigJson");
        assert!(report.results.is_empty());
        assert_eq!(progress.phase, "finished");
        assert_eq!(progress.completed_steps, 1);
        assert_eq!(progress.total_steps, 1);
        assert!(progress.is_finished);
    }

    fn probe(probe_type: &str, target: impl Into<String>, outcome: &str) -> ProbeResult {
        ProbeResult {
            probe_type: probe_type.to_string(),
            target: target.into(),
            outcome: outcome.to_string(),
            details: Vec::new(),
        }
    }

    fn scan_request(
        kind: ScanKind,
        probe_tasks: Vec<crate::types::ProbeTask>,
        strategy_probe: Option<crate::types::StrategyProbeRequest>,
    ) -> ScanRequest {
        ScanRequest {
            profile_id: "profile".to_string(),
            display_name: "Phase 1".to_string(),
            path_mode: crate::types::ScanPathMode::RawPath,
            kind,
            family: crate::types::DiagnosticProfileFamily::General,
            region_tag: None,
            manual_only: false,
            pack_refs: Vec::new(),
            proxy_host: None,
            proxy_port: None,
            probe_tasks,
            domain_targets: Vec::new(),
            dns_targets: Vec::new(),
            tcp_targets: Vec::new(),
            quic_targets: Vec::new(),
            service_targets: Vec::new(),
            circumvention_targets: Vec::new(),
            throughput_targets: Vec::new(),
            whitelist_sni: Vec::new(),
            telegram_target: None,
            strategy_probe,
            network_snapshot: None,
        }
    }
}
