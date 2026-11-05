use crate::types::{
    StrategyProbeAuditAssessment, StrategyProbeAuditConfidence, StrategyProbeAuditConfidenceLevel,
    StrategyProbeCandidateSummary, StrategyProbeRecommendation,
};
use crate::util::STRATEGY_PROBE_SUITE_FULL_MATRIX_V1;

use super::super::audit_confidence::{AuditSignals, build_audit_confidence};
use super::super::audit_counts::{build_audit_coverage, round_percent, strategy_audit_lane_counts};
use super::super::audit_scoring::{all_candidates_tied, candidate_score_percent, winner_margin_percent};

pub(in crate::engine::runners::strategy) fn resolve_strategy_probe_audit_assessment(
    suite_id: &str,
    tcp_candidates: &[StrategyProbeCandidateSummary],
    quic_candidates: &[StrategyProbeCandidateSummary],
    recommendation: Option<&StrategyProbeRecommendation>,
    tcp_candidates_planned: usize,
    quic_candidates_planned: usize,
    dns_tampered: bool,
) -> Option<StrategyProbeAuditAssessment> {
    if suite_id != STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 {
        return None;
    }

    let tcp_counts = strategy_audit_lane_counts(tcp_candidates, tcp_candidates_planned);
    let quic_counts = strategy_audit_lane_counts(quic_candidates, quic_candidates_planned);
    let tcp_winner_id = recommendation.and_then(|value| value.tcp_candidate_id.as_deref());
    let quic_winner_id = recommendation.and_then(|value| value.quic_candidate_id.as_deref());
    let tcp_winner = tcp_candidates.iter().find(|c| Some(c.id.as_str()) == tcp_winner_id);
    let quic_winner = quic_candidates.iter().find(|c| Some(c.id.as_str()) == quic_winner_id);
    let tcp_winner_coverage = tcp_winner.map_or(0, candidate_score_percent);
    let quic_winner_coverage = quic_winner.map_or(0, candidate_score_percent);
    let tcp_lane_coverage = round_percent(tcp_counts.executed, tcp_counts.applicable_planned());
    let quic_lane_coverage = round_percent(quic_counts.executed, quic_counts.applicable_planned());
    let fallback_candidates_ran = tcp_counts.executed > 0 || quic_counts.executed > 0;
    let dns_short_circuited = dns_tampered && !fallback_candidates_ran;

    let signals = AuditSignals {
        no_winner: tcp_winner.is_none() && quic_winner.is_none(),
        dns_tampering_with_fallback: dns_tampered && fallback_candidates_ran,
        weak_winner_coverage: tcp_winner.is_some() && tcp_winner_coverage < 50
            || quic_winner.is_some() && quic_winner_coverage < 50,
        low_tcp_execution: tcp_counts.applicable_planned() > 0 && tcp_lane_coverage < 75,
        low_quic_execution: quic_counts.applicable_planned() > 0 && quic_lane_coverage < 75,
        narrow_tcp_margin: tcp_winner_id.is_some_and(|id| winner_margin_percent(tcp_candidates, id) < 10),
        narrow_quic_margin: quic_winner_id.is_some_and(|id| winner_margin_percent(quic_candidates, id) < 10),
        all_tcp_tied: tcp_winner.is_some() && all_candidates_tied(tcp_candidates),
        all_quic_tied: quic_winner.is_some() && all_candidates_tied(quic_candidates),
    };
    let coverage = build_audit_coverage(
        tcp_counts,
        quic_counts,
        tcp_winner,
        quic_winner,
        tcp_winner_coverage,
        quic_winner_coverage,
    );
    let no_evasion_needed = !dns_tampered
        && signals.all_tcp_tied
        && signals.all_quic_tied
        && tcp_winner_id == Some("baseline_plain_direct")
        && quic_winner_id == Some("quic_disabled")
        && tcp_winner_coverage >= 80
        && quic_winner_coverage >= 80;
    if no_evasion_needed {
        return Some(StrategyProbeAuditAssessment {
            dns_short_circuited: false,
            coverage,
            confidence: StrategyProbeAuditConfidence {
                level: StrategyProbeAuditConfidenceLevel::High,
                score: 100,
                rationale: "All strategies performed equally — no evasion needed".to_string(),
                warnings: Vec::new(),
            },
        });
    }
    Some(StrategyProbeAuditAssessment {
        dns_short_circuited,
        coverage,
        confidence: build_audit_confidence(dns_short_circuited, &signals),
    })
}
