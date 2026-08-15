use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

use rustls::client::danger::ServerCertVerifier;

use crate::CandidateRuntimeLauncher;
use crate::connectivity::{push_event, set_progress, set_report};
use crate::transport::transport_for_request_with_session;
use crate::types::{ScanCompletionKind, ScanKind, ScanProgress, ScanRequest, ScanTerminationReason, SharedState};

use super::plan::build_execution_plan;
use super::report::{ReportBuildContext, build_report, connectivity_analytics_summary, connectivity_summary};
use super::runners::execution_coordinator;
use super::runtime::{ExecutionRuntime, RunnerOutcome, publish_cancelled_run};

pub fn run_engine_scan(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
) {
    let started_at = crate::util::now_ms();
    let transport = transport_for_request_with_session(&request, &session_id);
    let mut plan = match build_execution_plan(session_id.clone(), request.clone(), started_at, transport.clone()) {
        Ok(plan) => plan,
        Err(message) => {
            let mut report = build_report(
                ReportBuildContext { session_id: session_id.clone(), request, started_at, execution_plan: None },
                message,
                Vec::new(),
                Vec::new(),
                None,
                None,
            );
            report.completion_kind = ScanCompletionKind::Terminated;
            report.termination_reason = Some(ScanTerminationReason::EngineError);
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
    let (coordinator, supervisor) = execution_coordinator(candidate_runtime_launcher);
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
            crate::transport::describe_transport(&plan.transport)
        ),
    );

    let mut runtime = ExecutionRuntime::new(shared.clone(), cancel);
    runtime.set_scan_deadline(
        std::time::Instant::now() + std::time::Duration::from_millis(plan.request.scan_deadline_ms.unwrap_or(360_000)),
    );
    let outcome = coordinator.run(&plan, &mut runtime, tls_verifier.as_ref());
    let Some(cleanup_receipt) = supervisor.terminal_receipt() else {
        let message = "candidate runtime cleanup barrier did not join every runtime".to_string();
        let mut report = build_report(
            ReportBuildContext {
                session_id: plan.session_id.clone(),
                request: plan.request.clone(),
                started_at: plan.started_at,
                execution_plan: Some(plan.snapshot(runtime.stage_executions())),
            },
            message.clone(),
            runtime.results,
            runtime.observations,
            runtime.strategy.strategy_probe_report,
            None,
        );
        report.completion_kind = ScanCompletionKind::Terminated;
        report.termination_reason = Some(ScanTerminationReason::EngineError);
        set_report(&shared, report);
        push_event(
            &shared,
            &plan.session_id,
            &plan.request.profile_id,
            &plan.request.path_mode,
            "engine",
            "error",
            format!("Diagnostics terminated: {message}"),
        );
        set_progress(
            &shared,
            ScanProgress {
                session_id: plan.session_id,
                phase: "error".to_string(),
                completed_steps: runtime.completed_steps,
                total_steps: plan.total_steps,
                message: "Diagnostics terminated by candidate cleanup failure".to_string(),
                is_finished: true,
                latest_probe_target: None,
                latest_probe_outcome: Some("candidate_cleanup_unjoined".to_string()),
                strategy_probe_progress: None,
            },
        );
        return;
    };
    push_event(
        &shared,
        &plan.session_id,
        &plan.request.profile_id,
        &plan.request.path_mode,
        "engine",
        "info",
        format!(
            "Candidate cleanup started={} stopped={} joined={} forced_abort={}",
            cleanup_receipt.started, cleanup_receipt.stopped, cleanup_receipt.joined, cleanup_receipt.forced_abort,
        ),
    );
    match outcome {
        RunnerOutcome::Cancelled => {
            publish_cancelled_run(&plan, &shared, runtime);
        }
        RunnerOutcome::Finished => {
            if let Some(report) = runtime.final_report {
                let analytics_summary = matches!(plan.request.kind, ScanKind::Connectivity)
                    .then(|| connectivity_analytics_summary(&report.results, &report.path_mode));
                set_report(&shared, report);
                if let Some(analytics_summary) = analytics_summary {
                    push_event(
                        &shared,
                        &plan.session_id,
                        &plan.request.profile_id,
                        &plan.request.path_mode,
                        "engine",
                        "info",
                        analytics_summary,
                    );
                }
            }
            publish_finished_progress(
                &shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                plan.total_steps,
            );
        }
        RunnerOutcome::Failed(message) => {
            let mut report = build_report(
                ReportBuildContext {
                    session_id: plan.session_id.clone(),
                    request: plan.request.clone(),
                    started_at: plan.started_at,
                    execution_plan: Some(plan.snapshot(runtime.stage_executions())),
                },
                message.clone(),
                runtime.results,
                runtime.observations,
                runtime.strategy.strategy_probe_report,
                None,
            );
            report.completion_kind = ScanCompletionKind::Terminated;
            report.termination_reason = Some(ScanTerminationReason::EngineError);
            set_report(&shared, report);
            push_event(
                &shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                "engine",
                "error",
                format!("Diagnostics terminated: {message}"),
            );
            set_progress(
                &shared,
                ScanProgress {
                    session_id: plan.session_id,
                    phase: "error".to_string(),
                    completed_steps: runtime.completed_steps,
                    total_steps: plan.total_steps,
                    message: "Diagnostics terminated by an internal runner failure".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: Some("runner_panicked".to_string()),
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
            let analytics_summary = matches!(plan.request.kind, ScanKind::Connectivity)
                .then(|| connectivity_analytics_summary(&runtime.results, &plan.request.path_mode));
            let report = build_report(
                ReportBuildContext {
                    session_id: plan.session_id.clone(),
                    request: plan.request.clone(),
                    started_at: plan.started_at,
                    execution_plan: Some(plan.snapshot(runtime.stage_executions())),
                },
                summary,
                runtime.results,
                runtime.observations,
                runtime.strategy.strategy_probe_report,
                None,
            );
            set_report(&shared, report);
            if let Some(analytics_summary) = analytics_summary {
                push_event(
                    &shared,
                    &plan.session_id,
                    &plan.request.profile_id,
                    &plan.request.path_mode,
                    "engine",
                    "info",
                    analytics_summary,
                );
            }
            publish_finished_progress(
                &shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                plan.total_steps,
            );
        }
    }
}

fn publish_finished_progress(
    shared: &Arc<Mutex<SharedState>>,
    session_id: &str,
    profile_id: &str,
    path_mode: &crate::types::ScanPathMode,
    total_steps: usize,
) {
    set_progress(
        shared,
        ScanProgress {
            session_id: session_id.to_string(),
            phase: "finished".to_string(),
            completed_steps: total_steps,
            total_steps,
            message: "Diagnostics finished".to_string(),
            is_finished: true,
            latest_probe_target: None,
            latest_probe_outcome: None,
            strategy_probe_progress: None,
        },
    );
    push_event(shared, session_id, profile_id, path_mode, "engine", "info", "Diagnostics finished".to_string());
}
