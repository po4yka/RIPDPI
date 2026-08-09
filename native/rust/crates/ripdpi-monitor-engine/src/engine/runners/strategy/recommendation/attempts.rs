use std::cmp::Ordering;

use crate::candidates::StrategyCandidateSpec;
use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime};
use crate::types::{
    STRATEGY_PROBE_ATTEMPT_VERSION, StrategyProbeAttempt, StrategyProbeAttemptRound, StrategyProbeAttemptStatus,
    StrategyProbeCandidateSummary, StrategyProbeProgressLane,
};

pub(super) fn finalize_strategy_probe_attempts(
    plan: &ExecutionPlan,
    runtime: &ExecutionRuntime,
) -> Vec<StrategyProbeAttempt> {
    let Some(strategy_plan) = plan.strategy.as_ref() else {
        return Vec::new();
    };
    let mut attempts = runtime.strategy.attempts.clone();
    for attempt in &mut attempts {
        attempt.candidate_index = candidate_index(plan, attempt.lane, &attempt.candidate_id);
        attempt.target_index = target_index(plan, attempt.lane, &attempt.target);
    }

    for (candidate_index, spec) in strategy_plan.suite.tcp_candidates.iter().enumerate() {
        for (target_index, target) in plan.request.domain_targets.iter().enumerate() {
            for protocol in ["HTTP", "HTTPS"] {
                ensure_terminal_attempt(
                    &mut attempts,
                    spec,
                    candidate_index + 1,
                    StrategyProbeProgressLane::Tcp,
                    &target.host,
                    target_index + 1,
                    target.is_control,
                    protocol,
                    runtime.strategy.tcp_candidates.iter().find(|summary| summary.id == spec.id),
                    runtime,
                );
            }
        }
    }
    for (candidate_index, spec) in strategy_plan.suite.quic_candidates.iter().enumerate() {
        for (target_index, target) in plan.request.quic_targets.iter().enumerate() {
            ensure_terminal_attempt(
                &mut attempts,
                spec,
                candidate_index + 1,
                StrategyProbeProgressLane::Quic,
                &target.host,
                target_index + 1,
                false,
                "QUIC",
                runtime.strategy.quic_candidates.iter().find(|summary| summary.id == spec.id),
                runtime,
            );
        }
    }

    attempts.sort_by(compare_attempts);
    for (index, attempt) in attempts.iter_mut().enumerate() {
        attempt.sequence = index + 1;
    }
    attempts
}

#[allow(clippy::too_many_arguments)]
fn ensure_terminal_attempt(
    attempts: &mut Vec<StrategyProbeAttempt>,
    spec: &StrategyCandidateSpec,
    candidate_index: usize,
    lane: StrategyProbeProgressLane,
    target: &str,
    target_index: usize,
    is_control: bool,
    protocol: &str,
    summary: Option<&StrategyProbeCandidateSummary>,
    runtime: &ExecutionRuntime,
) {
    let qualifier_eliminated = summary.is_some_and(|value| value.outcome == "eliminated");
    let already_recorded = attempts.iter().any(|attempt| {
        attempt.candidate_id == spec.id
            && attempt.target == target
            && attempt.protocol == protocol
            && (!qualifier_eliminated || attempt.round != StrategyProbeAttemptRound::Qualifier)
    });
    if already_recorded {
        return;
    }
    let (status, reason) = terminal_status(summary, runtime);
    attempts.push(StrategyProbeAttempt {
        attempt_version: STRATEGY_PROBE_ATTEMPT_VERSION.to_string(),
        sequence: 0,
        candidate_index,
        candidate_id: spec.id.to_string(),
        candidate_label: spec.label.to_string(),
        candidate_family: spec.family.to_string(),
        lane,
        target: target.to_string(),
        target_index,
        is_control,
        protocol: protocol.to_string(),
        round: StrategyProbeAttemptRound::NotStarted,
        status,
        started_at_ms: None,
        duration_ms: None,
        retry_count: 0,
        outcome: summary.map(|value| value.outcome.clone()),
        reason: Some(reason),
    });
}

fn terminal_status(
    summary: Option<&StrategyProbeCandidateSummary>,
    runtime: &ExecutionRuntime,
) -> (StrategyProbeAttemptStatus, String) {
    if let Some(summary) = summary {
        let rationale = summary.rationale.clone();
        if summary.skipped || summary.outcome == "skipped" || summary.outcome == "capability_skipped" {
            return (StrategyProbeAttemptStatus::Skipped, rationale);
        }
        if summary.outcome == "not_applicable" {
            return (StrategyProbeAttemptStatus::NotApplicable, rationale);
        }
        if summary.outcome == "eliminated" {
            return (StrategyProbeAttemptStatus::Eliminated, rationale);
        }
        let normalized = rationale.to_ascii_lowercase();
        if normalized.contains("timed out") || normalized.contains("timeout") {
            return (StrategyProbeAttemptStatus::TimedOut, rationale);
        }
        if summary.outcome == "failed" {
            return (StrategyProbeAttemptStatus::NotApplicable, rationale);
        }
    }
    if runtime.is_past_deadline() {
        return (
            StrategyProbeAttemptStatus::TimedOut,
            "Scan deadline elapsed before this planned attempt started".to_string(),
        );
    }
    if runtime.is_cancelled() {
        return (
            StrategyProbeAttemptStatus::Planned,
            "Scan was cancelled before this planned attempt started".to_string(),
        );
    }
    (StrategyProbeAttemptStatus::Planned, "Strategy probe finalized before this planned attempt started".to_string())
}

fn target_index(plan: &ExecutionPlan, lane: StrategyProbeProgressLane, target: &str) -> usize {
    match lane {
        StrategyProbeProgressLane::Tcp => plan.request.domain_targets.iter().position(|item| item.host == target),
        StrategyProbeProgressLane::Quic => plan.request.quic_targets.iter().position(|item| item.host == target),
    }
    .map_or(0, |index| index + 1)
}

fn candidate_index(plan: &ExecutionPlan, lane: StrategyProbeProgressLane, candidate_id: &str) -> usize {
    let Some(strategy) = plan.strategy.as_ref() else {
        return 0;
    };
    let specs = match lane {
        StrategyProbeProgressLane::Tcp => &strategy.suite.tcp_candidates,
        StrategyProbeProgressLane::Quic => &strategy.suite.quic_candidates,
    };
    candidate_index_for_spec(specs, candidate_id)
}

fn candidate_index_for_spec(specs: &[StrategyCandidateSpec], candidate_id: &str) -> usize {
    specs.iter().position(|spec| spec.id == candidate_id).map_or(0, |index| index + 1)
}

fn compare_attempts(left: &StrategyProbeAttempt, right: &StrategyProbeAttempt) -> Ordering {
    lane_order(left.lane)
        .cmp(&lane_order(right.lane))
        .then_with(|| left.candidate_index.cmp(&right.candidate_index))
        .then_with(|| left.target_index.cmp(&right.target_index))
        .then_with(|| protocol_order(&left.protocol).cmp(&protocol_order(&right.protocol)))
        .then_with(|| round_order(left.round).cmp(&round_order(right.round)))
        .then_with(|| left.started_at_ms.cmp(&right.started_at_ms))
}

fn lane_order(lane: StrategyProbeProgressLane) -> u8 {
    match lane {
        StrategyProbeProgressLane::Tcp => 0,
        StrategyProbeProgressLane::Quic => 1,
    }
}

fn protocol_order(protocol: &str) -> u8 {
    match protocol {
        "HTTP" => 0,
        "HTTPS" => 1,
        "QUIC" => 2,
        _ => 3,
    }
}

fn round_order(round: StrategyProbeAttemptRound) -> u8 {
    match round {
        StrategyProbeAttemptRound::Baseline => 0,
        StrategyProbeAttemptRound::Qualifier => 1,
        StrategyProbeAttemptRound::FullMatrix => 2,
        StrategyProbeAttemptRound::NotStarted => 3,
    }
}
