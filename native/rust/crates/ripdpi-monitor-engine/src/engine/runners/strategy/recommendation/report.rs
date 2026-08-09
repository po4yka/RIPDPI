use crate::candidates::{
    build_strategy_probe_summary, probe_fake_ttl_capability, probe_ip_fragmentation_capabilities,
    probe_tcp_fast_open_capability,
};
use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime};
use crate::types::{
    STRATEGY_PROBE_METHODOLOGY_VERSION, StrategyProbeCompletionKind, StrategyProbeRecommendation, StrategyProbeReport,
    TransportFamily, TransportPivotRecommendation, TransportPivotViability,
};

use super::super::support::{
    pilot_bucket_label, resolve_recommended_proxy_config_json, resolve_strategy_probe_audit_assessment,
    select_safe_or_baseline_candidate_index, stratified_pilot_targets,
};
use super::attempts::finalize_strategy_probe_attempts;

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
    let confirm_good_corroborated =
        plan.request.confirm_good_dpi_evidence.is_some() && quic_w.succeeded_targets > 0 && !quic_w.skipped;
    let recommendation = StrategyProbeRecommendation {
        tcp_candidate_id: tcp_w.id.clone(),
        tcp_candidate_label: tcp_w.label.clone(),
        quic_candidate_id: quic_w.id.clone(),
        quic_candidate_label: quic_w.label.clone(),
        quic_candidate_layout_family: quic_w.quic_layout_family.clone(),
        rationale: if confirm_good_corroborated {
            "Reality application data stalled after successful handshakes; QUIC succeeded, so pivot transport family"
                .to_string()
        } else {
            format!(
                "{} with {} weighted TCP success and {} weighted QUIC success",
                tcp_w.label, tcp_w.weighted_success_score, quic_w.weighted_success_score,
            )
        },
        recommended_proxy_config_json: resolve_recommended_proxy_config_json(quic_w, quic_winner_spec),
        transport_pivot: confirm_good_corroborated.then(|| TransportPivotRecommendation {
            reason_code: "confirm_good_dpi_suspected".to_string(),
            preferred_family: TransportFamily::UdpQuic,
            viability: TransportPivotViability::Confirmed,
            selected_relay_role: None,
        }),
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
    let attempts = finalize_strategy_probe_attempts(plan, runtime);
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
        connection_concurrency_assessment: runtime.strategy.connection_concurrency_assessment.clone(),
        target_selection: plan.request.strategy_probe.as_ref().and_then(|p| p.target_selection.clone()),
        pilot_bucket_labels,
        domain_strategy_seeds: Vec::new(),
        attempts,
    });
    runtime.strategy.summary = Some(summary);
    true
}
