use super::prelude::*;

pub fn build_strategy_probe_summary(
    suite_id: &str,
    tcp_candidates: &[StrategyProbeCandidateSummary],
    quic_candidates: &[StrategyProbeCandidateSummary],
    recommendation: &StrategyProbeRecommendation,
    audit_assessment: Option<&StrategyProbeAuditAssessment>,
) -> String {
    let labels = recommendation
        .tcp_candidate_label
        .iter()
        .chain(&recommendation.quic_candidate_label)
        .map(String::as_str)
        .collect::<Vec<_>>()
        .join(" + ");
    if suite_id != STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 {
        return format!("Recommended {labels}");
    }
    let mut worked = 0usize;
    let mut partial = 0usize;
    let mut failed = 0usize;
    let mut not_applicable = 0usize;
    for candidate in tcp_candidates.iter().chain(quic_candidates.iter()) {
        match candidate.outcome.as_str() {
            "success" => worked += 1,
            "partial" => partial += 1,
            "not_applicable" => not_applicable += 1,
            _ => failed += 1,
        }
    }
    let mut summary = format!(
        "Recommended {labels}. Worked {worked} · partial {partial} · failed {failed} · not applicable {not_applicable}",
    );
    if let Some(assessment) = audit_assessment {
        summary.push_str(&format!(
            " · confidence {} · matrix coverage {}%",
            strategy_probe_audit_confidence_label(assessment.confidence.level),
            assessment.coverage.matrix_coverage_percent,
        ));
    }
    summary
}

fn strategy_probe_audit_confidence_label(level: StrategyProbeAuditConfidenceLevel) -> &'static str {
    match level {
        StrategyProbeAuditConfidenceLevel::High => "HIGH",
        StrategyProbeAuditConfidenceLevel::Medium => "MEDIUM",
        StrategyProbeAuditConfidenceLevel::Low => "LOW",
    }
}
