use crate::candidates::{StrategyCandidateSpec, build_quic_candidates_for_suite, probe_tcp_fast_open_capability};
use crate::classification::{filter_quic_candidates_for_failure, interleave_candidate_families};
use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime};
use crate::types::QuicTarget;
use crate::util::stable_probe_hash;

use super::super::support::select_safe_or_baseline_candidate_index;

pub(super) struct PreparedQuicCandidates {
    pub(super) pending_quic_specs: Vec<StrategyCandidateSpec>,
    pub(super) quic_targets: Vec<QuicTarget>,
    pub(super) quic_candidate_total: usize,
}

pub(super) fn prepare_quic_candidates(
    plan: &ExecutionPlan,
    runtime: &ExecutionRuntime,
    fake_ttl_available: bool,
    ipfrag_caps: ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities,
) -> Option<PreparedQuicCandidates> {
    let strategy_plan = plan.strategy.as_ref()?;
    let tcp_fast_open_available = probe_tcp_fast_open_capability();
    let tcp_winner_id = select_safe_or_baseline_candidate_index(
        &runtime.strategy.tcp_candidates,
        &strategy_plan.suite.tcp_candidates,
        fake_ttl_available,
        tcp_fast_open_available,
        ipfrag_caps,
    )
    .map(|i| runtime.strategy.tcp_candidates[i].id.as_str());
    let tcp_winner_spec = tcp_winner_id
        .and_then(|id| strategy_plan.suite.tcp_candidates.iter().find(|s| s.id == id))
        .or_else(|| strategy_plan.suite.tcp_candidates.first())?;
    let quic_specs = filter_quic_candidates_for_failure(
        build_quic_candidates_for_suite(&strategy_plan.suite_id, &tcp_winner_spec.config)
            .unwrap_or_else(|_| strategy_plan.suite.quic_candidates.clone()),
        runtime.strategy.baseline_failure.as_ref().map(|value| value.class),
    );
    let quic_candidate_total = quic_specs.len();
    let mut pending_quic_specs =
        interleave_candidate_families(quic_specs, stable_probe_hash(strategy_plan.probe_seed, "quic"));
    if let Some(max) = strategy_plan.max_candidates {
        pending_quic_specs.truncate(max);
    }
    let quic_targets =
        runtime.strategy.dns_override_quic_targets.clone().unwrap_or_else(|| plan.request.quic_targets.clone());
    Some(PreparedQuicCandidates { pending_quic_specs, quic_targets, quic_candidate_total })
}
