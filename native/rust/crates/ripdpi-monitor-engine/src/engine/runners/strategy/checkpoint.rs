use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime};
use crate::types::{STRATEGY_PROBE_METHODOLOGY_VERSION, StrategyProbeCompletionKind, StrategyProbeReport};

use super::support::resolve_strategy_probe_audit_assessment;

pub(in crate::engine) fn checkpoint_strategy_probe_report(
    plan: &ExecutionPlan,
    runtime: &ExecutionRuntime,
) -> Option<StrategyProbeReport> {
    let strategy_plan = plan.strategy.as_ref()?;
    let strategy = &runtime.strategy;
    if strategy.tcp_candidates.is_empty() && strategy.quic_candidates.is_empty() {
        return None;
    }
    let audit_assessment = resolve_strategy_probe_audit_assessment(
        &strategy_plan.suite_id,
        &strategy.tcp_candidates,
        &strategy.quic_candidates,
        None,
        strategy_plan.suite.tcp_candidates.len(),
        strategy_plan.suite.quic_candidates.len(),
        false,
    );
    Some(StrategyProbeReport {
        suite_id: strategy_plan.suite_id.clone(),
        methodology_version: STRATEGY_PROBE_METHODOLOGY_VERSION.to_string(),
        tcp_candidates: strategy.tcp_candidates.clone(),
        quic_candidates: strategy.quic_candidates.clone(),
        recommendation: None,
        completion_kind: StrategyProbeCompletionKind::PartialResults,
        audit_assessment,
        connection_concurrency_assessment: None,
        target_selection: plan.request.strategy_probe.as_ref().and_then(|request| request.target_selection.clone()),
        pilot_bucket_labels: Vec::new(),
        active_path_observation: None,
        domain_strategy_seeds: Vec::new(),
    })
}
