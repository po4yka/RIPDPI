use crate::candidates::{
    StrategyCandidateSpec, probe_fake_ttl_capability, probe_ip_fragmentation_capabilities,
    probe_tcp_fast_open_capability,
};
use crate::execution::skipped_candidate_summary;
use crate::types::StrategyProbeProgressLane;

use super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime};
use super::super::support::select_promotable_candidate_index;

pub(super) fn skip_for_confirmed_quic(
    plan: &ExecutionPlan,
    runtime: &mut ExecutionRuntime,
    tcp_specs: &[StrategyCandidateSpec],
) -> bool {
    let strategy_plan = plan.strategy.as_ref().expect("strategy plan");
    let quic_pivot_candidate = select_promotable_candidate_index(
        &runtime.strategy.quic_candidates,
        &strategy_plan.suite.quic_candidates,
        probe_fake_ttl_capability(),
        probe_tcp_fast_open_capability(),
        probe_ip_fragmentation_capabilities(),
    );
    if plan.request.confirm_good_dpi_evidence.is_none() || quic_pivot_candidate.is_none() {
        return false;
    }

    let rationale = "Post-handshake Reality stalls were corroborated by QUIC; fingerprint tuning is not applicable";
    for (index, spec) in tcp_specs.iter().enumerate() {
        let summary = skipped_candidate_summary(spec, plan.request.domain_targets.len(), 2, rationale);
        runtime.strategy.tcp_candidates.push(summary.clone());
        runtime.record_skipped_strategy_probe_candidate(
            plan,
            "tcp",
            StrategyProbeProgressLane::Tcp,
            index + 1,
            tcp_specs.len(),
            &summary.id,
            &summary.label,
            Some(summary.outcome.clone()),
            format!("Skipped {}", summary.label),
        );
    }
    true
}
