use crate::transport::TransportConfig;
use crate::types::{ScanKind, ScanPathMode, ScanRequest};

use super::runners::{PROBE_STAGE_REGISTRATIONS, registration_for_family};
use super::runtime::{ExecutionPlan, ExecutionStageId};
use super::strategy_plan::build_strategy_execution_plan;

pub(super) fn build_execution_plan(
    session_id: String,
    request: ScanRequest,
    started_at: u64,
    transport: TransportConfig,
) -> Result<ExecutionPlan, String> {
    let strategy = if matches!(request.kind, ScanKind::StrategyProbe) {
        Some(build_strategy_execution_plan(&session_id, &request)?)
    } else {
        None
    };
    let stage_order = match request.kind {
        ScanKind::Connectivity => connectivity_stage_order(&request),
        ScanKind::StrategyProbe => strategy_stage_order(&request),
    };
    let runtime_context = strategy.as_ref().and_then(|plan| plan.runtime_context.as_ref());
    let probe_context =
        crate::connectivity::ProbeExecutionContext::from_runtime_context(transport.clone(), runtime_context)?;
    Ok(ExecutionPlan {
        session_id,
        request,
        started_at,
        total_steps: 0,
        transport,
        probe_context,
        stage_order,
        strategy,
    })
}

pub(super) fn strategy_stage_order(request: &ScanRequest) -> Vec<ExecutionStageId> {
    let mut stages = vec![ExecutionStageId::Environment];
    if matches!(request.path_mode, ScanPathMode::InPath)
        && request.in_path_route.is_some()
        && !request.domain_targets.is_empty()
    {
        stages.push(ExecutionStageId::Web);
    }
    stages.push(ExecutionStageId::StrategyDnsBaseline);
    if request.confirm_good_dpi_evidence.is_some() {
        stages.push(ExecutionStageId::StrategyQuicCandidates);
        stages.push(ExecutionStageId::StrategyTcpCandidates);
    } else {
        stages.push(ExecutionStageId::StrategyTcpCandidates);
        stages.push(ExecutionStageId::StrategyQuicCandidates);
    }
    stages.push(ExecutionStageId::StrategyConnectionConcurrency);
    stages.push(ExecutionStageId::StrategyRecommendation);
    stages
}

pub(super) fn connectivity_stage_order(request: &ScanRequest) -> Vec<ExecutionStageId> {
    // Always-on stages — today only `Environment` — come first, in
    // registration order. Followed by either the probe-task-driven sequence
    // (user-supplied order, deduplicated) or the canonical registration
    // order for all selectable stages.
    let mut ordered: Vec<ExecutionStageId> = PROBE_STAGE_REGISTRATIONS
        .iter()
        .filter(|registration| registration.task_family_selector.is_none())
        .map(|registration| registration.stage_id.clone())
        .collect();

    if !request.probe_tasks.is_empty() {
        for task in &request.probe_tasks {
            if let Some(registration) = registration_for_family(&task.family)
                && !ordered.contains(&registration.stage_id)
            {
                ordered.push(registration.stage_id.clone());
            }
        }
        return ordered;
    }

    for registration in PROBE_STAGE_REGISTRATIONS {
        if registration.task_family_selector.is_some() && !ordered.contains(&registration.stage_id) {
            ordered.push(registration.stage_id.clone());
        }
    }
    ordered
}
