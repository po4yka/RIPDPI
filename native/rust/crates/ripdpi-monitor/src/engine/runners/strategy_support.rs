use std::time::Duration;

use ripdpi_failure_classifier::{ClassifiedFailure, FailureClass};

use crate::candidates::{strategy_probe_config_json, CandidateEligibility, StrategyCandidateSpec};
use crate::classification::{interleave_candidate_families, reorder_tcp_candidates_for_failure};
use crate::execution::not_applicable_candidate_execution;
use crate::types::StrategyProbeCandidateSummary as CandidateSummary;
use crate::types::{ProbeResult, StrategyProbeCandidateSummary, StrategyProbeLiveProgress, StrategyProbeProgressLane};

use super::super::super::runtime::{ExecutionPlan, ExecutionRuntime, RunnerArtifacts};

pub(super) const ECH_ELIGIBILITY_RATIONALE: &str =
    "Baseline did not expose an ECH-capable HTTPS target, so ECH extension splitting would be a no-op";

pub(super) const FAKE_TTL_ELIGIBILITY_RATIONALE: &str =
    "setsockopt(IP_TTL) is unavailable on this platform (Android VPN/tun mode); fake-packet strategies that rely on TTL manipulation are skipped";

pub(super) const TCP_FAST_OPEN_ELIGIBILITY_RATIONALE: &str =
    "TCP Fast Open is unavailable on this device/kernel, so TFO probe variants are skipped";

pub(super) fn record_not_applicable_tcp_candidate(
    runtime: &mut ExecutionRuntime,
    plan: &ExecutionPlan,
    phase: &str,
    spec: &StrategyCandidateSpec,
    candidate_index: usize,
    candidate_total: usize,
    reason: &str,
    log_suffix: &str,
) {
    let execution = not_applicable_candidate_execution(spec, plan.request.domain_targets.len() * 2, 3, reason);
    runtime.record_step(
        plan,
        phase,
        format!("Marked {} as not applicable{}", spec.label, log_suffix),
        Some(spec.label.to_string()),
        Some(execution.summary.outcome.clone()),
        Some(strategy_probe_live_progress_with_targets(
            StrategyProbeProgressLane::Tcp,
            candidate_index,
            candidate_total,
            spec.id,
            spec.label,
            0,
            0,
        )),
        RunnerArtifacts::from_results(
            execution.results.clone(),
            "strategy_probe",
            "debug",
            format!("Skipped execution for {}{}", spec.label, log_suffix),
        ),
    );
    runtime.strategy.tcp_candidates.push(execution.summary);
}

pub(super) fn resolve_recommended_proxy_config_json(
    quic_candidate: &CandidateSummary,
    fallback_quic_spec: &StrategyCandidateSpec,
) -> String {
    quic_candidate
        .proxy_config_json
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .map_or_else(|| strategy_probe_config_json(&fallback_quic_spec.config), str::to_owned)
}

pub(super) fn strategy_probe_live_progress_with_targets(
    lane: StrategyProbeProgressLane,
    candidate_index: usize,
    candidate_total: usize,
    candidate_id: &str,
    candidate_label: &str,
    succeeded_targets: usize,
    total_targets: usize,
) -> StrategyProbeLiveProgress {
    StrategyProbeLiveProgress {
        lane,
        candidate_index,
        candidate_total,
        candidate_id: candidate_id.to_string(),
        candidate_label: candidate_label.to_string(),
        succeeded_targets,
        total_targets,
    }
}

pub(super) struct FamilyFailureTracker<'a> {
    blocked: Option<&'a str>,
    last_failed: Option<&'a str>,
    consecutive: usize,
    threshold: usize,
}

impl<'a> FamilyFailureTracker<'a> {
    pub(super) fn new(threshold: usize) -> Self {
        Self { blocked: None, last_failed: None, consecutive: 0, threshold }
    }

    pub(super) fn record(&mut self, family: &'a str, failed: bool) {
        if failed {
            if self.last_failed == Some(family) {
                self.consecutive += 1;
            } else {
                self.last_failed = Some(family);
                self.consecutive = 1;
            }
            if self.consecutive >= self.threshold {
                self.blocked = Some(family);
                self.consecutive = 0;
            }
        } else {
            self.last_failed = None;
            self.consecutive = 0;
            self.blocked = None;
        }
        if self.blocked.is_some() && family != self.blocked.unwrap_or_default() {
            self.blocked = None;
        }
    }

    pub(super) fn blocked_family(&self) -> Option<&'a str> {
        self.blocked
    }
}

fn probe_detail_value<'a>(result: &'a ProbeResult, key: &str) -> Option<&'a str> {
    result.details.iter().find(|detail| detail.key == key).map(|detail| detail.value.as_str())
}

pub(super) fn compute_rst_adaptive_timeout(baseline_failure: &ClassifiedFailure) -> Option<Duration> {
    if !matches!(baseline_failure.class, FailureClass::TcpReset) {
        return None;
    }
    Some(Duration::from_millis(1500))
}

#[cfg(test)]
pub(super) fn baseline_has_tls_ech_only(results: &[ProbeResult]) -> bool {
    results.iter().any(|result| result.probe_type == "strategy_https" && result.outcome == "tls_ech_only")
}

pub(super) fn baseline_supports_ech_candidates(results: &[ProbeResult]) -> bool {
    results.iter().any(|result| {
        result.probe_type == "strategy_https"
            && (result.outcome == "tls_ech_only"
                || probe_detail_value(result, "tlsEchResolutionDetail") == Some("ech_config_available")
                || probe_detail_value(result, "cdnProvider").is_some_and(|value| !value.trim().is_empty()))
    })
}

pub(super) fn ordered_follow_up_tcp_candidates(
    tcp_specs: &[StrategyCandidateSpec],
    failure_class: Option<FailureClass>,
    baseline_results: &[ProbeResult],
    probe_seed: u64,
    fake_ttl_available: bool,
) -> Vec<StrategyCandidateSpec> {
    let reordered = reorder_tcp_candidates_for_failure(tcp_specs, failure_class, fake_ttl_available)
        .into_iter()
        .skip(1)
        .collect::<Vec<_>>();
    if !baseline_supports_ech_candidates(baseline_results) {
        return interleave_candidate_families(reordered, probe_seed);
    }

    let mut ech_priority = Vec::new();
    let mut remaining = Vec::new();
    for spec in reordered {
        if spec.eligibility == CandidateEligibility::RequiresEchCapability {
            ech_priority.push(spec);
        } else {
            remaining.push(spec);
        }
    }
    ech_priority.extend(interleave_candidate_families(remaining, probe_seed));
    ech_priority
}

#[derive(Clone, Copy)]
pub(super) struct StrategyAuditLaneCounts {
    pub(super) planned: usize,
    pub(super) executed: usize,
    pub(super) skipped: usize,
    pub(super) not_applicable: usize,
}

impl StrategyAuditLaneCounts {
    pub(super) fn applicable_planned(self) -> usize {
        self.planned.saturating_sub(self.not_applicable)
    }
}

pub(super) fn round_percent(numerator: usize, denominator: usize) -> usize {
    if denominator == 0 {
        0
    } else {
        (numerator.saturating_mul(100) + (denominator / 2)) / denominator
    }
}

pub(super) fn strategy_audit_lane_counts(
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
