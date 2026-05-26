use crate::types::{StrategyProbeAuditConfidence, StrategyProbeAuditConfidenceLevel};

use super::AuditSignals;

pub(in crate::engine::runners::strategy) fn build_audit_confidence(
    dns_short_circuited: bool,
    signals: &AuditSignals,
) -> StrategyProbeAuditConfidence {
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
    if signals.dns_tampering_with_fallback {
        warnings
            .push("Baseline DNS tampering was detected; confidence reflects fallback strategy candidates.".to_string());
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
        _ if signals.dns_tampering_with_fallback => {
            "Baseline DNS tampering was detected, but fallback strategy candidates ran"
        }
        _ => "Matrix coverage and winner strength are consistent",
    }
    .to_string();
    StrategyProbeAuditConfidence { level, score, rationale, warnings }
}
