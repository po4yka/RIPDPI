use crate::types::{
    StrategyProbeAuditAssessment, StrategyProbeAuditConfidence, StrategyProbeAuditConfidenceLevel,
    StrategyProbeAuditCoverage, StrategyProbeCandidateSummary, StrategyProbeRecommendation,
};
use crate::util::STRATEGY_PROBE_SUITE_FULL_MATRIX_V1;

#[derive(Clone, Copy)]
pub(in crate::engine::runners::strategy) struct StrategyAuditLaneCounts {
    pub(in crate::engine::runners::strategy) planned: usize,
    pub(in crate::engine::runners::strategy) executed: usize,
    pub(in crate::engine::runners::strategy) skipped: usize,
    pub(in crate::engine::runners::strategy) not_applicable: usize,
}

impl StrategyAuditLaneCounts {
    pub(in crate::engine::runners::strategy) fn applicable_planned(self) -> usize {
        self.planned.saturating_sub(self.not_applicable)
    }
}

pub(in crate::engine::runners::strategy) fn round_percent(numerator: usize, denominator: usize) -> usize {
    if denominator == 0 {
        0
    } else {
        (numerator.saturating_mul(100) + (denominator / 2)) / denominator
    }
}

pub(in crate::engine::runners::strategy) fn strategy_audit_lane_counts(
    candidates: &[StrategyProbeCandidateSummary],
    planned: usize,
) -> StrategyAuditLaneCounts {
    StrategyAuditLaneCounts {
        planned,
        executed: candidates
            .iter()
            .filter(|candidate| !candidate.skipped && candidate.outcome != "not_applicable")
            .count(),
        skipped: candidates.iter().filter(|candidate| candidate.skipped).count(),
        not_applicable: candidates.iter().filter(|candidate| candidate.outcome == "not_applicable").count(),
    }
}

fn all_candidates_tied(candidates: &[StrategyProbeCandidateSummary]) -> bool {
    let eligible: Vec<_> = candidates.iter().filter(|c| !c.skipped && c.outcome != "not_applicable").collect();
    if eligible.len() < 2 {
        return false;
    }
    let first = &eligible[0];
    eligible
        .iter()
        .all(|c| c.weighted_success_score == first.weighted_success_score && c.quality_score == first.quality_score)
}

fn candidate_score_percent(candidate: &StrategyProbeCandidateSummary) -> usize {
    round_percent(candidate.weighted_success_score, candidate.total_weight)
}

fn winner_margin_percent(candidates: &[StrategyProbeCandidateSummary], winner_candidate_id: &str) -> usize {
    let executable_scores = candidates
        .iter()
        .filter(|candidate| !candidate.skipped && candidate.outcome != "not_applicable")
        .map(|candidate| (candidate.id.as_str(), candidate_score_percent(candidate)))
        .collect::<Vec<_>>();
    let Some((_, winner_score)) =
        executable_scores.iter().find(|(candidate_id, _)| *candidate_id == winner_candidate_id)
    else {
        return 0;
    };
    let runner_up_score = executable_scores
        .iter()
        .filter(|(candidate_id, _)| *candidate_id != winner_candidate_id)
        .map(|(_, score)| *score)
        .max()
        .unwrap_or(0);
    winner_score.saturating_sub(runner_up_score)
}

struct AuditSignals {
    weak_winner_coverage: bool,
    low_tcp_execution: bool,
    low_quic_execution: bool,
    narrow_tcp_margin: bool,
    narrow_quic_margin: bool,
    all_tcp_tied: bool,
    all_quic_tied: bool,
}

fn build_audit_confidence(dns_short_circuited: bool, signals: &AuditSignals) -> StrategyProbeAuditConfidence {
    let penalty_table: &[(bool, i32, &str)] = &[
        (dns_short_circuited, 45, "Baseline DNS tampering short-circuited the audit before fallback candidates ran."),
        (
            signals.weak_winner_coverage,
            25,
            "The winning TCP or QUIC lane recovered too few weighted targets to trust the recommendation.",
        ),
        (signals.low_tcp_execution, 15, "TCP matrix coverage stayed below 75% of applicable candidates."),
        (signals.low_quic_execution, 15, "QUIC matrix coverage stayed below 75% of applicable candidates."),
        (signals.narrow_tcp_margin, 10, "TCP winner margin stayed below 10 points over the next candidate."),
        (signals.narrow_quic_margin, 10, "QUIC winner margin stayed below 10 points over the next candidate."),
        (signals.all_tcp_tied, 20, "All TCP candidates produced identical results; the winner is arbitrary."),
        (signals.all_quic_tied, 15, "All QUIC candidates produced identical results; the winner is arbitrary."),
    ];
    let mut score = 100i32;
    let mut warnings = Vec::new();
    for &(condition, penalty, message) in penalty_table {
        if condition {
            score -= penalty;
            warnings.push(message.to_string());
        }
    }
    let score = score.clamp(0, 100) as usize;
    let level = if score >= 80 {
        StrategyProbeAuditConfidenceLevel::High
    } else if score >= 50 {
        StrategyProbeAuditConfidenceLevel::Medium
    } else {
        StrategyProbeAuditConfidenceLevel::Low
    };
    let rationale = match () {
        _ if dns_short_circuited => "Baseline DNS tampering short-circuited the audit before fallback candidates ran",
        _ if signals.weak_winner_coverage => "The winning TCP or QUIC lane recovered too few weighted targets",
        _ if signals.low_tcp_execution || signals.low_quic_execution => {
            "The audit did not execute enough of the applicable matrix to fully trust the winner"
        }
        _ if signals.all_tcp_tied || signals.all_quic_tied => {
            "All candidates in a lane produced identical results; the recommendation is arbitrary"
        }
        _ if signals.narrow_tcp_margin || signals.narrow_quic_margin => {
            "The winning candidates only narrowly outperformed the next-best options"
        }
        _ => "Matrix coverage and winner strength are consistent",
    }
    .to_string();
    StrategyProbeAuditConfidence { level, score, rationale, warnings }
}

fn build_audit_coverage(
    tcp_counts: StrategyAuditLaneCounts,
    quic_counts: StrategyAuditLaneCounts,
    tcp_winner: Option<&StrategyProbeCandidateSummary>,
    quic_winner: Option<&StrategyProbeCandidateSummary>,
    tcp_winner_coverage: usize,
    quic_winner_coverage: usize,
) -> StrategyProbeAuditCoverage {
    let total_planned = tcp_counts.applicable_planned() + quic_counts.applicable_planned();
    let total_executed = tcp_counts.executed + quic_counts.executed;
    StrategyProbeAuditCoverage {
        tcp_candidates_planned: tcp_counts.planned,
        tcp_candidates_executed: tcp_counts.executed,
        tcp_candidates_skipped: tcp_counts.skipped,
        tcp_candidates_not_applicable: tcp_counts.not_applicable,
        quic_candidates_planned: quic_counts.planned,
        quic_candidates_executed: quic_counts.executed,
        quic_candidates_skipped: quic_counts.skipped,
        quic_candidates_not_applicable: quic_counts.not_applicable,
        tcp_winner_succeeded_targets: tcp_winner.map_or(0, |c| c.succeeded_targets),
        tcp_winner_total_targets: tcp_winner.map_or(0, |c| c.total_targets),
        quic_winner_succeeded_targets: quic_winner.map_or(0, |c| c.succeeded_targets),
        quic_winner_total_targets: quic_winner.map_or(0, |c| c.total_targets),
        matrix_coverage_percent: round_percent(total_executed, total_planned),
        winner_coverage_percent: (tcp_winner_coverage + quic_winner_coverage).div_ceil(2),
        tcp_winner_coverage_percent: tcp_winner_coverage,
        quic_winner_coverage_percent: quic_winner_coverage,
    }
}

pub(in crate::engine::runners::strategy) fn resolve_strategy_probe_audit_assessment(
    suite_id: &str,
    tcp_candidates: &[StrategyProbeCandidateSummary],
    quic_candidates: &[StrategyProbeCandidateSummary],
    recommendation: &StrategyProbeRecommendation,
    tcp_candidates_planned: usize,
    quic_candidates_planned: usize,
    dns_short_circuited: bool,
) -> Option<StrategyProbeAuditAssessment> {
    if suite_id != STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 {
        return None;
    }

    let tcp_counts = strategy_audit_lane_counts(tcp_candidates, tcp_candidates_planned);
    let quic_counts = strategy_audit_lane_counts(quic_candidates, quic_candidates_planned);
    let tcp_winner = tcp_candidates.iter().find(|c| c.id == recommendation.tcp_candidate_id);
    let quic_winner = quic_candidates.iter().find(|c| c.id == recommendation.quic_candidate_id);
    let tcp_winner_coverage = tcp_winner.map_or(0, candidate_score_percent);
    let quic_winner_coverage = quic_winner.map_or(0, candidate_score_percent);
    let tcp_lane_coverage = round_percent(tcp_counts.executed, tcp_counts.applicable_planned());
    let quic_lane_coverage = round_percent(quic_counts.executed, quic_counts.applicable_planned());

    let signals = AuditSignals {
        weak_winner_coverage: tcp_winner_coverage < 50 || quic_winner_coverage < 50,
        low_tcp_execution: tcp_counts.applicable_planned() > 0 && tcp_lane_coverage < 75,
        low_quic_execution: quic_counts.applicable_planned() > 0 && quic_lane_coverage < 75,
        narrow_tcp_margin: winner_margin_percent(tcp_candidates, &recommendation.tcp_candidate_id) < 10,
        narrow_quic_margin: winner_margin_percent(quic_candidates, &recommendation.quic_candidate_id) < 10,
        all_tcp_tied: all_candidates_tied(tcp_candidates),
        all_quic_tied: all_candidates_tied(quic_candidates),
    };

    let coverage = build_audit_coverage(
        tcp_counts,
        quic_counts,
        tcp_winner,
        quic_winner,
        tcp_winner_coverage,
        quic_winner_coverage,
    );

    let no_evasion_needed = !dns_short_circuited
        && signals.all_tcp_tied
        && signals.all_quic_tied
        && recommendation.tcp_candidate_id == "baseline_current"
        && recommendation.quic_candidate_id == "baseline_current"
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
