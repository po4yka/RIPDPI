use crate::candidates::{CandidateEligibility, CandidateWarmup, StrategyCandidateSpec};
use crate::engine::strategy_plan::StrategyExecutionPlan;
use crate::transport::TransportConfig;
use crate::types::{
    EXECUTION_PLAN_VERSION, ExecutionPlanSnapshot, ExecutionPlanTargetCounts, ProbeTaskFamily,
    StrategyCandidatePlanSnapshot, StrategyExecutionPlanSnapshot,
};

use super::plan::ExecutionPlan;

impl ExecutionPlan {
    pub(in crate::engine) fn snapshot(&self) -> ExecutionPlanSnapshot {
        let mut probe_task_families: Vec<ProbeTaskFamily> = Vec::new();
        for task in &self.request.probe_tasks {
            if !probe_task_families.contains(&task.family) {
                probe_task_families.push(task.family.clone());
            }
        }
        let strategy_selection =
            self.request.strategy_probe.as_ref().and_then(|request| request.target_selection.as_ref());
        ExecutionPlanSnapshot {
            plan_version: EXECUTION_PLAN_VERSION.to_string(),
            scan_kind: self.request.kind.clone(),
            profile_family: self.request.family.clone(),
            path_mode: self.request.path_mode.clone(),
            transport_kind: match self.transport {
                TransportConfig::Direct { .. } => "direct",
                TransportConfig::Socks5 { .. } => "socks5",
            }
            .to_string(),
            stage_order: self.stage_order.iter().map(|stage| stage.as_str().to_string()).collect(),
            total_steps: self.total_steps,
            scan_deadline_ms: self.request.scan_deadline_ms.unwrap_or(360_000),
            pack_refs: self.request.pack_refs.clone(),
            probe_task_families,
            target_counts: ExecutionPlanTargetCounts {
                domain_target_count: self.request.domain_targets.len(),
                dns_target_count: self.request.dns_targets.len(),
                tcp_target_count: self.request.tcp_targets.len(),
                quic_target_count: self.request.quic_targets.len(),
                service_target_count: self.request.service_targets.len(),
                circumvention_target_count: self.request.circumvention_targets.len(),
                throughput_target_count: self.request.throughput_targets.len(),
                whitelist_sni_count: self.request.whitelist_sni.len(),
                telegram_target_count: usize::from(self.request.telegram_target.is_some()),
                strategy_selected_domain_count: strategy_selection.map_or(0, |selection| selection.domain_hosts.len()),
                strategy_selected_quic_count: strategy_selection.map_or(0, |selection| selection.quic_hosts.len()),
            },
            strategy: self.strategy.as_ref().map(strategy_snapshot),
        }
    }
}

fn strategy_snapshot(plan: &StrategyExecutionPlan) -> StrategyExecutionPlanSnapshot {
    StrategyExecutionPlanSnapshot {
        suite_id: plan.suite_id.clone(),
        inventory_semantics: "ordered_pre_runtime_filter_pool".to_string(),
        probe_seed: plan.probe_seed.to_string(),
        max_candidates: plan.max_candidates,
        tcp_candidates: plan.suite.tcp_candidates.iter().map(candidate_snapshot).collect(),
        quic_candidates: plan.suite.quic_candidates.iter().map(candidate_snapshot).collect(),
        short_circuit_hostfake: plan.suite.short_circuit_hostfake,
        short_circuit_quic_burst: plan.suite.short_circuit_quic_burst,
        family_failure_threshold: plan.suite.family_failure_threshold,
    }
}

fn candidate_snapshot(candidate: &StrategyCandidateSpec) -> StrategyCandidatePlanSnapshot {
    StrategyCandidatePlanSnapshot {
        id: candidate.id.to_string(),
        label: candidate.label.to_string(),
        family: candidate.family.to_string(),
        emitter_tier: candidate.emitter_tier,
        exact_emitter_requires_root: candidate.exact_emitter_requires_root,
        approximate_fallback_family: candidate.approximate_fallback_family.map(str::to_string),
        quic_layout_family: candidate.quic_layout_family.map(str::to_string),
        eligibility: match candidate.eligibility {
            CandidateEligibility::Always => "always",
            CandidateEligibility::RequiresEchCapability => "requires_ech_capability",
        }
        .to_string(),
        warmup: match candidate.warmup {
            CandidateWarmup::None => "none",
            CandidateWarmup::AdaptiveFakeTtl => "adaptive_fake_ttl",
        }
        .to_string(),
        preserve_adaptive_fake_ttl: candidate.preserve_adaptive_fake_ttl,
        requires_fake_ttl: candidate.requires_fake_ttl,
        requires_tcp_fast_open: candidate.requires_tcp_fast_open,
        required_capabilities: candidate
            .requires_capabilities
            .iter()
            .map(|capability| capability.as_str().to_string())
            .collect(),
    }
}
