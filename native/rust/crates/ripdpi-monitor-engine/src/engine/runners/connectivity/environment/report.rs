use crate::engine::report::{ReportBuildContext, build_report, connectivity_summary};
use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime};
use crate::types::{Diagnosis, ScanCompletionKind, ScanTerminationReason};

pub(super) fn finish_offline_scan(plan: &ExecutionPlan, runtime: &mut ExecutionRuntime) {
    let mut report = build_report(
        ReportBuildContext {
            session_id: plan.session_id.clone(),
            request: plan.request.clone(),
            started_at: plan.started_at,
            execution_plan: Some(plan.snapshot()),
        },
        connectivity_summary(&runtime.results, &plan.request.path_mode),
        runtime.results.clone(),
        runtime.observations.clone(),
        None,
        None,
    );
    report.completion_kind = ScanCompletionKind::Terminated;
    report.termination_reason = Some(ScanTerminationReason::NetworkUnavailable);
    report.diagnoses.push(Diagnosis {
        code: "network_unavailable".to_string(),
        summary: "The operating system reports no available network".to_string(),
        severity: "error".to_string(),
        target: None,
        evidence: vec!["network transport is unavailable".to_string()],
        recommendation: Some("Connect to a network and run diagnostics again".to_string()),
        control_validated: None,
    });
    runtime.finish_with_report(report);
}
