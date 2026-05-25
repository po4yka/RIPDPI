use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{
    build_strategy_probe_summary, probe_fake_ttl_capability, probe_ip_fragmentation_capabilities,
    probe_tcp_fast_open_capability,
};
use crate::connectivity::set_progress;
use crate::types::{
    ScanProgress, StrategyProbeCompletionKind, StrategyProbeRecommendation, StrategyProbeReport,
    STRATEGY_PROBE_METHODOLOGY_VERSION,
};

use super::super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerOutcome,
};
use super::support::{
    pilot_bucket_label, resolve_recommended_proxy_config_json, resolve_strategy_probe_audit_assessment,
    select_safe_or_baseline_candidate_index, stratified_pilot_targets,
};

pub(in crate::engine::runners) struct StrategyRecommendationRunner;

impl ExecutionStageRunner for StrategyRecommendationRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyRecommendation
    }

    fn phase(&self) -> &'static str {
        "recommendation"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        usize::from(plan.strategy.is_some())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        if !prepare_strategy_probe_report(plan, runtime) {
            return RunnerOutcome::Completed;
        }
        runtime.completed_steps += 1;
        set_progress(
            &runtime.shared,
            ScanProgress {
                session_id: plan.session_id.clone(),
                phase: self.phase().to_string(),
                completed_steps: runtime.completed_steps,
                total_steps: plan.total_steps,
                message: "Prepared strategy recommendation".to_string(),
                is_finished: false,
                latest_probe_target: None,
                latest_probe_outcome: Some("ready".to_string()),
                strategy_probe_progress: None,
            },
        );
        RunnerOutcome::Completed
    }
}

pub(in crate::engine) fn prepare_strategy_probe_report(plan: &ExecutionPlan, runtime: &mut ExecutionRuntime) -> bool {
    let Some(strategy_plan) = plan.strategy.as_ref() else {
        return false;
    };
    if runtime.strategy.strategy_probe_report.is_some() {
        return true;
    }
    if runtime.strategy.tcp_candidates.is_empty() || runtime.strategy.quic_candidates.is_empty() {
        runtime.strategy.summary = Some("Automatic probing finished".to_string());
        return false;
    }
    let fake_ttl_available = probe_fake_ttl_capability();
    let tcp_fast_open_available = probe_tcp_fast_open_capability();
    let ipfrag_caps = probe_ip_fragmentation_capabilities();
    let Some(wi_tcp) = select_safe_or_baseline_candidate_index(
        &runtime.strategy.tcp_candidates,
        &strategy_plan.suite.tcp_candidates,
        fake_ttl_available,
        tcp_fast_open_available,
        ipfrag_caps,
    ) else {
        runtime.strategy.summary = Some("Automatic probing finished without a safe TCP recommendation".to_string());
        return false;
    };
    let Some(wi_quic) = select_safe_or_baseline_candidate_index(
        &runtime.strategy.quic_candidates,
        &strategy_plan.suite.quic_candidates,
        fake_ttl_available,
        tcp_fast_open_available,
        ipfrag_caps,
    ) else {
        runtime.strategy.summary = Some("Automatic probing finished without a safe QUIC recommendation".to_string());
        return false;
    };
    let tcp_w = &runtime.strategy.tcp_candidates[wi_tcp];
    let quic_w = &runtime.strategy.quic_candidates[wi_quic];
    let Some(quic_winner_spec) = strategy_plan
        .suite
        .quic_candidates
        .iter()
        .find(|spec| spec.id == quic_w.id)
        .or_else(|| strategy_plan.suite.quic_candidates.first())
    else {
        runtime.strategy.summary = Some("Automatic probing finished".to_string());
        return false;
    };
    let recommendation = StrategyProbeRecommendation {
        tcp_candidate_id: tcp_w.id.clone(),
        tcp_candidate_label: tcp_w.label.clone(),
        quic_candidate_id: quic_w.id.clone(),
        quic_candidate_label: quic_w.label.clone(),
        quic_candidate_layout_family: quic_w.quic_layout_family.clone(),
        rationale: format!(
            "{} with {} weighted TCP success and {} weighted QUIC success",
            tcp_w.label, tcp_w.weighted_success_score, quic_w.weighted_success_score,
        ),
        recommended_proxy_config_json: resolve_recommended_proxy_config_json(quic_w, quic_winner_spec),
    };
    let is_dns_tampered = runtime.strategy.dns_override_domain_targets.is_some();
    let audit_assessment = resolve_strategy_probe_audit_assessment(
        &strategy_plan.suite_id,
        &runtime.strategy.tcp_candidates,
        &runtime.strategy.quic_candidates,
        &recommendation,
        strategy_plan.suite.tcp_candidates.len(),
        strategy_plan.suite.quic_candidates.len(),
        is_dns_tampered,
    );
    let summary = build_strategy_probe_summary(
        &strategy_plan.suite_id,
        &runtime.strategy.tcp_candidates,
        &runtime.strategy.quic_candidates,
        &recommendation,
        audit_assessment.as_ref(),
    );
    let pilot_bucket_labels = stratified_pilot_targets(&plan.request.domain_targets)
        .into_iter()
        .map(|target| pilot_bucket_label(&target))
        .collect();
    let is_partial = runtime.strategy.tcp_candidates.len() < strategy_plan.suite.tcp_candidates.len()
        || runtime.strategy.quic_candidates.len() < strategy_plan.suite.quic_candidates.len();
    runtime.strategy.strategy_probe_report = Some(StrategyProbeReport {
        suite_id: strategy_plan.suite_id.clone(),
        methodology_version: STRATEGY_PROBE_METHODOLOGY_VERSION.to_string(),
        tcp_candidates: runtime.strategy.tcp_candidates.clone(),
        quic_candidates: runtime.strategy.quic_candidates.clone(),
        recommendation,
        completion_kind: match () {
            _ if is_dns_tampered => StrategyProbeCompletionKind::DnsTamperingWithFallback,
            _ if is_partial => StrategyProbeCompletionKind::PartialResults,
            _ => StrategyProbeCompletionKind::Normal,
        },
        audit_assessment,
        target_selection: plan.request.strategy_probe.as_ref().and_then(|p| p.target_selection.clone()),
        pilot_bucket_labels,
        domain_strategy_seeds: Vec::new(),
    });
    runtime.strategy.summary = Some(summary);
    true
}
