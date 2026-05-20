use crate::types::{StrategyProbeAuditCoverage, StrategyProbeCandidateSummary};

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
    numerator.saturating_mul(100).saturating_add(denominator / 2).checked_div(denominator).unwrap_or(0)
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

pub(in crate::engine::runners::strategy) fn build_audit_coverage(
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
