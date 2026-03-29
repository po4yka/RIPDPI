use std::sync::Arc;
use std::thread;
use std::time::Duration;

use ripdpi_failure_classifier::FailureClass;
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{
    build_quic_candidates_for_suite, build_strategy_probe_summary, candidate_pause_ms, CandidateEligibility,
    StrategyCandidateSpec,
};
use crate::classification::{
    classified_failure_probe_result, classify_strategy_probe_baseline_observations, filter_quic_candidates_for_failure,
    interleave_candidate_families, next_candidate_index, reorder_tcp_candidates_for_failure,
};
use crate::connectivity::set_progress;
use crate::execution::{
    execute_quic_candidate, execute_tcp_candidate, not_applicable_candidate_execution, skipped_candidate_summary,
    winning_candidate_index,
};
use crate::observations::observations_for_results;
use crate::strategy::detect_strategy_probe_dns_tampering;
use crate::types::{
    ProbeResult, ScanProgress, StrategyProbeAuditAssessment, StrategyProbeAuditConfidence,
    StrategyProbeAuditConfidenceLevel, StrategyProbeAuditCoverage, StrategyProbeCandidateSummary,
    StrategyProbeCompletionKind, StrategyProbeLiveProgress, StrategyProbeProgressLane, StrategyProbeRecommendation,
    StrategyProbeReport,
};
use crate::util::{stable_probe_hash, STRATEGY_PROBE_SUITE_FULL_MATRIX_V1};

use super::super::report::build_report;
use super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};

pub(super) struct StrategyDnsBaselineRunner;
pub(super) struct StrategyTcpRunner;
pub(super) struct StrategyQuicRunner;
pub(super) struct StrategyRecommendationRunner;

const ECH_ELIGIBILITY_RATIONALE: &str =
    "Baseline did not expose an ECH-capable HTTPS target, so ECH extension splitting would be a no-op";

fn resolve_recommended_proxy_config_json(
    quic_candidate: &crate::types::StrategyProbeCandidateSummary,
    fallback_quic_spec: &crate::candidates::StrategyCandidateSpec,
) -> String {
    quic_candidate
        .proxy_config_json
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .map_or_else(|| crate::candidates::strategy_probe_config_json(&fallback_quic_spec.config), str::to_owned)
}

fn strategy_probe_live_progress(
    lane: StrategyProbeProgressLane,
    candidate_index: usize,
    candidate_total: usize,
    candidate_id: &str,
    candidate_label: &str,
) -> StrategyProbeLiveProgress {
    StrategyProbeLiveProgress {
        lane,
        candidate_index,
        candidate_total,
        candidate_id: candidate_id.to_string(),
        candidate_label: candidate_label.to_string(),
    }
}

fn probe_detail_value<'a>(result: &'a ProbeResult, key: &str) -> Option<&'a str> {
    result.details.iter().find(|detail| detail.key == key).map(|detail| detail.value.as_str())
}

fn baseline_has_tls_ech_only(results: &[ProbeResult]) -> bool {
    results.iter().any(|result| result.probe_type == "strategy_https" && result.outcome == "tls_ech_only")
}

fn baseline_supports_ech_candidates(results: &[ProbeResult]) -> bool {
    results.iter().any(|result| {
        result.probe_type == "strategy_https"
            && (result.outcome == "tls_ech_only"
                || probe_detail_value(result, "tlsEchResolutionDetail") == Some("ech_config_available"))
    })
}

fn ordered_follow_up_tcp_candidates(
    tcp_specs: &[StrategyCandidateSpec],
    failure_class: Option<FailureClass>,
    baseline_results: &[ProbeResult],
    probe_seed: u64,
) -> Vec<StrategyCandidateSpec> {
    let reordered =
        reorder_tcp_candidates_for_failure(tcp_specs, failure_class).into_iter().skip(1).collect::<Vec<_>>();
    if !baseline_has_tls_ech_only(baseline_results) {
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
struct StrategyAuditLaneCounts {
    planned: usize,
    executed: usize,
    skipped: usize,
    not_applicable: usize,
}

impl StrategyAuditLaneCounts {
    fn applicable_planned(self) -> usize {
        self.planned.saturating_sub(self.not_applicable)
    }
}

fn round_percent(numerator: usize, denominator: usize) -> usize {
    if denominator == 0 {
        0
    } else {
        (numerator.saturating_mul(100) + (denominator / 2)) / denominator
    }
}

fn strategy_audit_lane_counts(candidates: &[StrategyProbeCandidateSummary], planned: usize) -> StrategyAuditLaneCounts {
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

fn resolve_strategy_probe_audit_assessment(
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
    let total_planned = tcp_counts.applicable_planned() + quic_counts.applicable_planned();
    let total_executed = tcp_counts.executed + quic_counts.executed;

    let tcp_winner = tcp_candidates.iter().find(|candidate| candidate.id == recommendation.tcp_candidate_id);
    let quic_winner = quic_candidates.iter().find(|candidate| candidate.id == recommendation.quic_candidate_id);
    let tcp_winner_coverage = tcp_winner.map_or(0, candidate_score_percent);
    let quic_winner_coverage = quic_winner.map_or(0, candidate_score_percent);
    let tcp_lane_coverage = round_percent(tcp_counts.executed, tcp_counts.applicable_planned());
    let quic_lane_coverage = round_percent(quic_counts.executed, quic_counts.applicable_planned());
    let tcp_margin = winner_margin_percent(tcp_candidates, &recommendation.tcp_candidate_id);
    let quic_margin = winner_margin_percent(quic_candidates, &recommendation.quic_candidate_id);

    let weak_winner_coverage = tcp_winner_coverage < 50 || quic_winner_coverage < 50;
    let low_tcp_execution = tcp_counts.applicable_planned() > 0 && tcp_lane_coverage < 75;
    let low_quic_execution = quic_counts.applicable_planned() > 0 && quic_lane_coverage < 75;
    let narrow_tcp_margin = tcp_margin < 10;
    let narrow_quic_margin = quic_margin < 10;
    let all_tcp_tied = all_candidates_tied(tcp_candidates);
    let all_quic_tied = all_candidates_tied(quic_candidates);

    let mut score = 100i32;
    let mut warnings = Vec::new();

    if dns_short_circuited {
        score -= 45;
        warnings.push("Baseline DNS tampering short-circuited the audit before fallback candidates ran.".to_string());
    }
    if weak_winner_coverage {
        score -= 25;
        warnings.push(
            "The winning TCP or QUIC lane recovered too few weighted targets to trust the recommendation.".to_string(),
        );
    }
    if low_tcp_execution {
        score -= 15;
        warnings.push("TCP matrix coverage stayed below 75% of applicable candidates.".to_string());
    }
    if low_quic_execution {
        score -= 15;
        warnings.push("QUIC matrix coverage stayed below 75% of applicable candidates.".to_string());
    }
    if narrow_tcp_margin {
        score -= 10;
        warnings.push("TCP winner margin stayed below 10 points over the next candidate.".to_string());
    }
    if narrow_quic_margin {
        score -= 10;
        warnings.push("QUIC winner margin stayed below 10 points over the next candidate.".to_string());
    }
    if all_tcp_tied {
        score -= 20;
        warnings.push("All TCP candidates produced identical results; the winner is arbitrary.".to_string());
    }
    if all_quic_tied {
        score -= 15;
        warnings.push("All QUIC candidates produced identical results; the winner is arbitrary.".to_string());
    }

    let score = score.clamp(0, 100) as usize;
    let level = if score >= 80 {
        StrategyProbeAuditConfidenceLevel::High
    } else if score >= 50 {
        StrategyProbeAuditConfidenceLevel::Medium
    } else {
        StrategyProbeAuditConfidenceLevel::Low
    };
    let rationale = if dns_short_circuited {
        "Baseline DNS tampering short-circuited the audit before fallback candidates ran".to_string()
    } else if weak_winner_coverage {
        "The winning TCP or QUIC lane recovered too few weighted targets".to_string()
    } else if low_tcp_execution || low_quic_execution {
        "The audit did not execute enough of the applicable matrix to fully trust the winner".to_string()
    } else if all_tcp_tied || all_quic_tied {
        "All candidates in a lane produced identical results; the recommendation is arbitrary".to_string()
    } else if narrow_tcp_margin || narrow_quic_margin {
        "The winning candidates only narrowly outperformed the next-best options".to_string()
    } else {
        "Matrix coverage and winner strength are consistent".to_string()
    };

    Some(StrategyProbeAuditAssessment {
        dns_short_circuited,
        coverage: StrategyProbeAuditCoverage {
            tcp_candidates_planned: tcp_counts.planned,
            tcp_candidates_executed: tcp_counts.executed,
            tcp_candidates_skipped: tcp_counts.skipped,
            tcp_candidates_not_applicable: tcp_counts.not_applicable,
            quic_candidates_planned: quic_counts.planned,
            quic_candidates_executed: quic_counts.executed,
            quic_candidates_skipped: quic_counts.skipped,
            quic_candidates_not_applicable: quic_counts.not_applicable,
            tcp_winner_succeeded_targets: tcp_winner.map_or(0, |candidate| candidate.succeeded_targets),
            tcp_winner_total_targets: tcp_winner.map_or(0, |candidate| candidate.total_targets),
            quic_winner_succeeded_targets: quic_winner.map_or(0, |candidate| candidate.succeeded_targets),
            quic_winner_total_targets: quic_winner.map_or(0, |candidate| candidate.total_targets),
            matrix_coverage_percent: round_percent(total_executed, total_planned),
            winner_coverage_percent: (tcp_winner_coverage + quic_winner_coverage).div_ceil(2),
            tcp_winner_coverage_percent: tcp_winner_coverage,
            quic_winner_coverage_percent: quic_winner_coverage,
        },
        confidence: StrategyProbeAuditConfidence { level, score, rationale, warnings },
    })
}

impl ExecutionStageRunner for StrategyDnsBaselineRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyDnsBaseline
    }

    fn phase(&self) -> &'static str {
        "dns_baseline"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        usize::from(!plan.request.domain_targets.is_empty())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(strategy_plan) = plan.strategy.as_ref() else {
            return RunnerOutcome::Completed;
        };
        let Some(baseline) =
            detect_strategy_probe_dns_tampering(&plan.request.domain_targets, strategy_plan.runtime_context.as_ref())
        else {
            return RunnerOutcome::Completed;
        };
        let artifacts = RunnerArtifacts::from_results(
            baseline.results.clone(),
            "strategy_probe",
            "warn",
            format!(
                "Baseline classified as {} with {}",
                baseline.failure.class.as_str(),
                baseline.failure.action.as_str(),
            ),
        );
        runtime.record_step(
            plan,
            self.phase(),
            "Strategy baseline DNS classification".to_string(),
            Some("dns_baseline".to_string()),
            Some(baseline.failure.class.as_str().to_string()),
            artifacts,
        );
        runtime.results.push(classified_failure_probe_result("Current strategy", &baseline.failure));
        let tcp_candidates = strategy_plan
            .suite
            .tcp_candidates
            .iter()
            .map(|spec| {
                skipped_candidate_summary(
                    spec,
                    plan.request.domain_targets.len() * 2,
                    3,
                    "DNS tampering detected before fallback; TCP strategy escalation skipped",
                )
            })
            .collect::<Vec<_>>();
        let quic_specs = filter_quic_candidates_for_failure(
            strategy_plan.suite.quic_candidates.clone(),
            Some(FailureClass::QuicBreakage),
        );
        let quic_candidates = quic_specs
            .iter()
            .map(|spec| {
                skipped_candidate_summary(
                    spec,
                    plan.request.quic_targets.len(),
                    2,
                    "DNS tampering detected before fallback; QUIC strategy escalation skipped",
                )
            })
            .collect::<Vec<_>>();
        let Some(fallback_quic) = quic_specs.first().or_else(|| strategy_plan.suite.quic_candidates.first()) else {
            return RunnerOutcome::Completed;
        };
        let Some(fallback_tcp) = strategy_plan.suite.tcp_candidates.first() else {
            return RunnerOutcome::Completed;
        };
        let recommendation = StrategyProbeRecommendation {
            tcp_candidate_id: fallback_tcp.id.to_string(),
            tcp_candidate_label: fallback_tcp.label.to_string(),
            quic_candidate_id: fallback_quic.id.to_string(),
            quic_candidate_label: fallback_quic.label.to_string(),
            rationale: format!(
                "{} classified before fallback; keep current strategy and prefer resolver override",
                baseline.failure.class.as_str(),
            ),
            recommended_proxy_config_json: crate::candidates::strategy_probe_config_json(&strategy_plan.base_payload),
        };
        let audit_assessment = resolve_strategy_probe_audit_assessment(
            &strategy_plan.suite_id,
            &tcp_candidates,
            &quic_candidates,
            &recommendation,
            strategy_plan.suite.tcp_candidates.len(),
            strategy_plan.suite.quic_candidates.len(),
            true,
        );
        let strategy_probe_report = StrategyProbeReport {
            suite_id: strategy_plan.suite_id.clone(),
            tcp_candidates,
            quic_candidates,
            recommendation,
            completion_kind: StrategyProbeCompletionKind::DnsShortCircuited,
            audit_assessment,
            target_selection: plan.request.strategy_probe.as_ref().and_then(|probe| probe.target_selection.clone()),
        };
        let report = build_report(
            plan.session_id.clone(),
            plan.request.clone(),
            plan.started_at,
            "DNS tampering classified before fallback; resolver override recommended".to_string(),
            runtime.results.clone(),
            runtime.observations.clone(),
            Some(strategy_probe_report),
            None,
        );
        runtime.finish_with_report(report);
        RunnerOutcome::Finished
    }
}

impl ExecutionStageRunner for StrategyTcpRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyTcpCandidates
    }

    fn phase(&self) -> &'static str {
        "tcp"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.strategy.as_ref().map_or(0, |strategy| strategy.suite.tcp_candidates.len())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(strategy_plan) = plan.strategy.as_ref() else {
            return RunnerOutcome::Completed;
        };
        let tcp_specs = &strategy_plan.suite.tcp_candidates;
        if tcp_specs.is_empty() {
            return RunnerOutcome::Completed;
        }
        let tcp_candidate_total = tcp_specs.len();
        let baseline_spec = tcp_specs.first().expect("tcp candidate");
        runtime.publish_strategy_probe_candidate_started(
            plan,
            self.phase(),
            StrategyProbeProgressLane::Tcp,
            1,
            tcp_candidate_total,
            baseline_spec.id,
            baseline_spec.label,
            format!("Testing TCP candidate {}", baseline_spec.label),
        );
        let baseline_execution = execute_tcp_candidate(
            baseline_spec,
            &plan.request.domain_targets,
            strategy_plan.runtime_context.as_ref(),
            strategy_plan.probe_seed,
            tls_verifier,
            runtime.cancel_token(),
        );
        if baseline_execution.cancelled {
            return RunnerOutcome::Cancelled;
        }
        let baseline_observations = observations_for_results(&baseline_execution.results);
        runtime.strategy.baseline_failure = classify_strategy_probe_baseline_observations(&baseline_observations);
        let mut baseline_results = baseline_execution.results.clone();
        if let Some(failure) = &runtime.strategy.baseline_failure {
            baseline_results.push(classified_failure_probe_result(baseline_spec.label, failure));
        }
        runtime.record_step_with_strategy_probe_progress(
            plan,
            self.phase(),
            format!("Tested {}", baseline_spec.label),
            Some(baseline_spec.label.to_string()),
            Some(baseline_execution.summary.outcome.clone()),
            Some(strategy_probe_live_progress(
                StrategyProbeProgressLane::Tcp,
                1,
                tcp_candidate_total,
                baseline_spec.id,
                baseline_spec.label,
            )),
            RunnerArtifacts::from_results(
                baseline_results,
                "strategy_probe",
                if runtime.strategy.baseline_failure.is_some() { "warn" } else { "info" },
                format!("Testing TCP candidate {}", baseline_spec.label),
            ),
        );
        let mut hostfake_family_succeeded = baseline_execution.summary.family == "hostfake"
            && baseline_execution.summary.succeeded_targets == baseline_execution.summary.total_targets;
        runtime.strategy.tcp_candidates.push(baseline_execution.summary);

        if tcp_specs.len() > 1 {
            thread::sleep(Duration::from_millis(candidate_pause_ms(
                strategy_plan.probe_seed,
                baseline_spec,
                runtime.strategy.baseline_failure.is_some(),
            )));
        }

        let baseline_ech_capable = baseline_supports_ech_candidates(&baseline_execution.results);
        let ordered_tcp_specs = ordered_follow_up_tcp_candidates(
            tcp_specs,
            runtime.strategy.baseline_failure.as_ref().map(|value| value.class),
            &baseline_execution.results,
            strategy_plan.probe_seed,
        );
        let mut pending_tcp_specs = ordered_tcp_specs;
        let mut blocked_tcp_family = None::<&str>;
        let mut last_failed_tcp_family = None::<&str>;
        let mut consecutive_tcp_family_failures = 0usize;
        while !pending_tcp_specs.is_empty() {
            let candidate_index = runtime.strategy.tcp_candidates.len() + 1;
            let spec = pending_tcp_specs.remove(next_candidate_index(&pending_tcp_specs, blocked_tcp_family));
            if runtime.is_cancelled() {
                return RunnerOutcome::Cancelled;
            }
            runtime.publish_strategy_probe_candidate_started(
                plan,
                self.phase(),
                StrategyProbeProgressLane::Tcp,
                candidate_index,
                tcp_candidate_total,
                spec.id,
                spec.label,
                format!("Testing TCP candidate {}", spec.label),
            );
            if strategy_plan.suite.short_circuit_hostfake && spec.family == "hostfake" && hostfake_family_succeeded {
                let summary = skipped_candidate_summary(
                    &spec,
                    plan.request.domain_targets.len() * 2,
                    6,
                    "Earlier hostfake candidate already achieved full success",
                );
                runtime.strategy.tcp_candidates.push(summary.clone());
                runtime.record_skipped_strategy_probe_candidate(
                    plan,
                    self.phase(),
                    StrategyProbeProgressLane::Tcp,
                    candidate_index,
                    tcp_candidate_total,
                    &summary.id,
                    &summary.label,
                    Some(summary.outcome.clone()),
                    format!("Skipped {}", summary.label),
                );
                continue;
            }
            if spec.eligibility == CandidateEligibility::RequiresEchCapability && !baseline_ech_capable {
                let execution = not_applicable_candidate_execution(
                    &spec,
                    plan.request.domain_targets.len() * 2,
                    3,
                    ECH_ELIGIBILITY_RATIONALE,
                );
                runtime.record_step_with_strategy_probe_progress(
                    plan,
                    self.phase(),
                    format!("Marked {} as not applicable", spec.label),
                    Some(spec.label.to_string()),
                    Some(execution.summary.outcome.clone()),
                    Some(strategy_probe_live_progress(
                        StrategyProbeProgressLane::Tcp,
                        candidate_index,
                        tcp_candidate_total,
                        spec.id,
                        spec.label,
                    )),
                    RunnerArtifacts::from_results(
                        execution.results.clone(),
                        "strategy_probe",
                        "info",
                        format!("Skipped execution for {}", spec.label),
                    ),
                );
                runtime.strategy.tcp_candidates.push(execution.summary);
                continue;
            }

            let execution = execute_tcp_candidate(
                &spec,
                &plan.request.domain_targets,
                strategy_plan.runtime_context.as_ref(),
                strategy_plan.probe_seed,
                tls_verifier,
                runtime.cancel_token(),
            );
            if execution.cancelled {
                return RunnerOutcome::Cancelled;
            }
            if execution.summary.family == "hostfake"
                && execution.summary.succeeded_targets == execution.summary.total_targets
            {
                hostfake_family_succeeded = true;
            }
            let failed = execution.summary.outcome == "failed";
            runtime.record_step_with_strategy_probe_progress(
                plan,
                self.phase(),
                format!("Tested {}", spec.label),
                Some(spec.label.to_string()),
                Some(execution.summary.outcome.clone()),
                Some(strategy_probe_live_progress(
                    StrategyProbeProgressLane::Tcp,
                    candidate_index,
                    tcp_candidate_total,
                    spec.id,
                    spec.label,
                )),
                RunnerArtifacts::from_results(
                    execution.results.clone(),
                    "strategy_probe",
                    if failed { "warn" } else { "info" },
                    format!("Testing TCP candidate {}", spec.label),
                ),
            );
            runtime.strategy.tcp_candidates.push(execution.summary);
            if failed {
                if last_failed_tcp_family == Some(spec.family) {
                    consecutive_tcp_family_failures += 1;
                } else {
                    last_failed_tcp_family = Some(spec.family);
                    consecutive_tcp_family_failures = 1;
                }
                if consecutive_tcp_family_failures >= 2 {
                    blocked_tcp_family = Some(spec.family);
                    consecutive_tcp_family_failures = 0;
                }
            } else {
                last_failed_tcp_family = None;
                consecutive_tcp_family_failures = 0;
                blocked_tcp_family = None;
            }
            if blocked_tcp_family.is_some() && spec.family != blocked_tcp_family.unwrap_or_default() {
                blocked_tcp_family = None;
            }
            if !pending_tcp_specs.is_empty() {
                thread::sleep(Duration::from_millis(candidate_pause_ms(strategy_plan.probe_seed, &spec, failed)));
            }
        }
        RunnerOutcome::Completed
    }
}

impl ExecutionStageRunner for StrategyQuicRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyQuicCandidates
    }

    fn phase(&self) -> &'static str {
        "quic"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.strategy.as_ref().map_or(0, |strategy| strategy.suite.quic_candidates.len())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(strategy_plan) = plan.strategy.as_ref() else {
            return RunnerOutcome::Completed;
        };
        let tcp_winner_spec = if runtime.strategy.tcp_candidates.is_empty() {
            strategy_plan.suite.tcp_candidates.first()
        } else {
            let winning_tcp = winning_candidate_index(&runtime.strategy.tcp_candidates).unwrap_or(0);
            strategy_plan
                .suite
                .tcp_candidates
                .iter()
                .find(|spec| spec.id == runtime.strategy.tcp_candidates[winning_tcp].id)
                .or_else(|| strategy_plan.suite.tcp_candidates.first())
        };
        let Some(tcp_winner_spec) = tcp_winner_spec else {
            return RunnerOutcome::Completed;
        };
        let quic_specs = filter_quic_candidates_for_failure(
            build_quic_candidates_for_suite(&strategy_plan.suite_id, &tcp_winner_spec.config)
                .unwrap_or_else(|_| strategy_plan.suite.quic_candidates.clone()),
            runtime.strategy.baseline_failure.as_ref().map(|value| value.class),
        );
        let quic_candidate_total = quic_specs.len();
        if quic_candidate_total == 0 {
            return RunnerOutcome::Completed;
        }
        let mut pending_quic_specs =
            interleave_candidate_families(quic_specs.clone(), stable_probe_hash(strategy_plan.probe_seed, "quic"));
        let mut quic_family_succeeded = false;
        let mut blocked_quic_family = None::<&str>;
        let mut last_failed_quic_family = None::<&str>;
        let mut consecutive_quic_family_failures = 0usize;
        while !pending_quic_specs.is_empty() {
            let candidate_index = runtime.strategy.quic_candidates.len() + 1;
            let spec = pending_quic_specs.remove(next_candidate_index(&pending_quic_specs, blocked_quic_family));
            if runtime.is_cancelled() {
                return RunnerOutcome::Cancelled;
            }
            runtime.publish_strategy_probe_candidate_started(
                plan,
                self.phase(),
                StrategyProbeProgressLane::Quic,
                candidate_index,
                quic_candidate_total,
                spec.id,
                spec.label,
                format!("Testing QUIC candidate {}", spec.label),
            );
            if strategy_plan.suite.short_circuit_quic_burst && spec.family == "quic_burst" && quic_family_succeeded {
                let summary = skipped_candidate_summary(
                    &spec,
                    plan.request.quic_targets.len(),
                    2,
                    "Earlier QUIC burst candidate already achieved full success",
                );
                runtime.strategy.quic_candidates.push(summary.clone());
                runtime.record_skipped_strategy_probe_candidate(
                    plan,
                    self.phase(),
                    StrategyProbeProgressLane::Quic,
                    candidate_index,
                    quic_candidate_total,
                    &summary.id,
                    &summary.label,
                    Some(summary.outcome.clone()),
                    format!("Skipped {}", summary.label),
                );
                continue;
            }

            let execution = execute_quic_candidate(
                &spec,
                &plan.request.quic_targets,
                strategy_plan.runtime_context.as_ref(),
                strategy_plan.probe_seed,
                runtime.cancel_token(),
            );
            if execution.cancelled {
                return RunnerOutcome::Cancelled;
            }
            if execution.summary.family == "quic_burst"
                && execution.summary.succeeded_targets == execution.summary.total_targets
                && execution.summary.total_targets > 0
            {
                quic_family_succeeded = true;
            }
            let failed = execution.summary.outcome == "failed";
            runtime.record_step_with_strategy_probe_progress(
                plan,
                self.phase(),
                format!("Tested {}", spec.label),
                Some(spec.label.to_string()),
                Some(execution.summary.outcome.clone()),
                Some(strategy_probe_live_progress(
                    StrategyProbeProgressLane::Quic,
                    candidate_index,
                    quic_candidate_total,
                    spec.id,
                    spec.label,
                )),
                RunnerArtifacts::from_results(
                    execution.results.clone(),
                    "strategy_probe",
                    if failed { "warn" } else { "info" },
                    format!("Testing QUIC candidate {}", spec.label),
                ),
            );
            runtime.strategy.quic_candidates.push(execution.summary);
            if failed {
                if last_failed_quic_family == Some(spec.family) {
                    consecutive_quic_family_failures += 1;
                } else {
                    last_failed_quic_family = Some(spec.family);
                    consecutive_quic_family_failures = 1;
                }
                if consecutive_quic_family_failures >= 2 {
                    blocked_quic_family = Some(spec.family);
                    consecutive_quic_family_failures = 0;
                }
            } else {
                last_failed_quic_family = None;
                consecutive_quic_family_failures = 0;
                blocked_quic_family = None;
            }
            if blocked_quic_family.is_some() && spec.family != blocked_quic_family.unwrap_or_default() {
                blocked_quic_family = None;
            }
            if !pending_quic_specs.is_empty() {
                thread::sleep(Duration::from_millis(candidate_pause_ms(strategy_plan.probe_seed, &spec, failed)));
            }
        }
        RunnerOutcome::Completed
    }
}

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
        let Some(strategy_plan) = plan.strategy.as_ref() else {
            return RunnerOutcome::Completed;
        };
        if runtime.strategy.tcp_candidates.is_empty() || runtime.strategy.quic_candidates.is_empty() {
            runtime.strategy.summary = Some("Automatic probing finished".to_string());
            return RunnerOutcome::Completed;
        }
        let winning_tcp = winning_candidate_index(&runtime.strategy.tcp_candidates).unwrap_or(0);
        let winning_quic = winning_candidate_index(&runtime.strategy.quic_candidates).unwrap_or(0);
        let Some(quic_winner_spec) = strategy_plan
            .suite
            .quic_candidates
            .iter()
            .find(|spec| spec.id == runtime.strategy.quic_candidates[winning_quic].id)
            .or_else(|| strategy_plan.suite.quic_candidates.first())
        else {
            runtime.strategy.summary = Some("Automatic probing finished".to_string());
            return RunnerOutcome::Completed;
        };
        let recommended_proxy_config_json =
            resolve_recommended_proxy_config_json(&runtime.strategy.quic_candidates[winning_quic], quic_winner_spec);
        let recommendation = StrategyProbeRecommendation {
            tcp_candidate_id: runtime.strategy.tcp_candidates[winning_tcp].id.clone(),
            tcp_candidate_label: runtime.strategy.tcp_candidates[winning_tcp].label.clone(),
            quic_candidate_id: runtime.strategy.quic_candidates[winning_quic].id.clone(),
            quic_candidate_label: runtime.strategy.quic_candidates[winning_quic].label.clone(),
            rationale: format!(
                "{} with {} weighted TCP success and {} weighted QUIC success",
                runtime.strategy.tcp_candidates[winning_tcp].label,
                runtime.strategy.tcp_candidates[winning_tcp].weighted_success_score,
                runtime.strategy.quic_candidates[winning_quic].weighted_success_score,
            ),
            recommended_proxy_config_json,
        };
        let audit_assessment = resolve_strategy_probe_audit_assessment(
            &strategy_plan.suite_id,
            &runtime.strategy.tcp_candidates,
            &runtime.strategy.quic_candidates,
            &recommendation,
            strategy_plan.suite.tcp_candidates.len(),
            strategy_plan.suite.quic_candidates.len(),
            false,
        );
        let summary = build_strategy_probe_summary(
            &strategy_plan.suite_id,
            &runtime.strategy.tcp_candidates,
            &runtime.strategy.quic_candidates,
            &recommendation,
            audit_assessment.as_ref(),
        );
        runtime.strategy.strategy_probe_report = Some(StrategyProbeReport {
            suite_id: strategy_plan.suite_id.clone(),
            tcp_candidates: runtime.strategy.tcp_candidates.clone(),
            quic_candidates: runtime.strategy.quic_candidates.clone(),
            recommendation,
            completion_kind: StrategyProbeCompletionKind::Normal,
            audit_assessment,
            target_selection: plan.request.strategy_probe.as_ref().and_then(|probe| probe.target_selection.clone()),
        });
        runtime.strategy.summary = Some(summary);
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

#[cfg(test)]
mod tests {
    use ripdpi_failure_classifier::FailureClass;
    use ripdpi_proxy_config::{parse_proxy_config_json, ProxyConfigPayload, ProxyUiConfig};

    use super::{
        baseline_has_tls_ech_only, baseline_supports_ech_candidates, ordered_follow_up_tcp_candidates,
        resolve_recommended_proxy_config_json, resolve_strategy_probe_audit_assessment,
    };
    use crate::candidates::{build_tcp_candidates, CandidateEligibility};
    use crate::classification::{interleave_candidate_families, reorder_tcp_candidates_for_failure};
    use crate::types::{
        ProbeDetail, ProbeResult, StrategyProbeAuditConfidenceLevel, StrategyProbeCandidateSummary,
        StrategyProbeRecommendation,
    };
    use crate::util::STRATEGY_PROBE_SUITE_FULL_MATRIX_V1;

    fn quic_candidate_summary(proxy_config_json: Option<String>) -> StrategyProbeCandidateSummary {
        StrategyProbeCandidateSummary {
            id: "quic_realistic_burst".to_string(),
            label: "QUIC realistic burst".to_string(),
            family: "quic_burst".to_string(),
            outcome: "success".to_string(),
            rationale: "Recovered QUIC".to_string(),
            succeeded_targets: 1,
            total_targets: 1,
            weighted_success_score: 2,
            total_weight: 2,
            quality_score: 4,
            proxy_config_json,
            notes: Vec::new(),
            average_latency_ms: Some(220),
            skipped: false,
        }
    }

    fn strategy_candidate_summary(
        id: &str,
        family: &str,
        weighted_success_score: usize,
        total_weight: usize,
        succeeded_targets: usize,
        total_targets: usize,
        skipped: bool,
        outcome: &str,
    ) -> StrategyProbeCandidateSummary {
        StrategyProbeCandidateSummary {
            id: id.to_string(),
            label: id.replace('_', " "),
            family: family.to_string(),
            outcome: outcome.to_string(),
            rationale: "candidate result".to_string(),
            succeeded_targets,
            total_targets,
            weighted_success_score,
            total_weight,
            quality_score: weighted_success_score.saturating_mul(2),
            proxy_config_json: None,
            notes: Vec::new(),
            average_latency_ms: Some(200),
            skipped,
        }
    }

    fn recommendation() -> StrategyProbeRecommendation {
        StrategyProbeRecommendation {
            tcp_candidate_id: "tcp_winner".to_string(),
            tcp_candidate_label: "tcp winner".to_string(),
            quic_candidate_id: "quic_winner".to_string(),
            quic_candidate_label: "quic winner".to_string(),
            rationale: "best".to_string(),
            recommended_proxy_config_json: "{}".to_string(),
        }
    }

    fn baseline_https_result(outcome: &str, tls_ech_resolution_detail: &str) -> ProbeResult {
        ProbeResult {
            probe_type: "strategy_https".to_string(),
            target: "baseline_current · example.com".to_string(),
            outcome: outcome.to_string(),
            details: vec![
                ProbeDetail { key: "candidateId".to_string(), value: "baseline_current".to_string() },
                ProbeDetail { key: "tlsEchResolutionDetail".to_string(), value: tls_ech_resolution_detail.to_string() },
            ],
        }
    }

    #[test]
    fn resolve_recommended_proxy_config_json_prefers_winning_quic_summary_config() {
        let mut composed_config = ProxyUiConfig::default();
        composed_config.chains.tcp_steps = vec![crate::candidates::tcp_step("tlsrec", "extlen")];
        composed_config.quic.fake_profile = "realistic_initial".to_string();

        let mut fallback_config = ProxyUiConfig::default();
        fallback_config.quic.fake_profile = "compat_default".to_string();
        let fallback_quic_spec = crate::candidates::candidate_spec(
            "quic_realistic_burst",
            "QUIC realistic burst",
            "quic_burst",
            fallback_config,
        );

        let winning_quic_candidate =
            quic_candidate_summary(Some(crate::candidates::strategy_probe_config_json(&composed_config)));

        let recommended_proxy_config_json =
            resolve_recommended_proxy_config_json(&winning_quic_candidate, &fallback_quic_spec);

        match parse_proxy_config_json(&recommended_proxy_config_json).expect("parse ui config") {
            ProxyConfigPayload::Ui { config, .. } => {
                assert_eq!(config.chains.tcp_steps.len(), 1);
                assert_eq!(config.chains.tcp_steps[0].kind, "tlsrec");
                assert_eq!(config.quic.fake_profile, "realistic_initial");
            }
            ProxyConfigPayload::CommandLine { .. } => panic!("expected UI proxy config"),
        }
    }

    #[test]
    fn resolve_recommended_proxy_config_json_falls_back_to_quic_winner_spec_config() {
        let mut fallback_config = ProxyUiConfig::default();
        fallback_config.quic.fake_profile = "compat_default".to_string();
        let fallback_quic_spec =
            crate::candidates::candidate_spec("quic_compat_burst", "QUIC compat burst", "quic_burst", fallback_config);

        let winning_quic_candidate = quic_candidate_summary(None);

        let recommended_proxy_config_json =
            resolve_recommended_proxy_config_json(&winning_quic_candidate, &fallback_quic_spec);

        match parse_proxy_config_json(&recommended_proxy_config_json).expect("parse ui config") {
            ProxyConfigPayload::Ui { config, .. } => {
                assert_eq!(config.chains.tcp_steps, fallback_quic_spec.config.chains.tcp_steps);
                assert_eq!(config.quic.fake_profile, "compat_default");
            }
            ProxyConfigPayload::CommandLine { .. } => panic!("expected UI proxy config"),
        }
    }

    #[test]
    fn baseline_ech_detection_recognizes_resolution_detail_and_tls_ech_only() {
        assert!(baseline_supports_ech_candidates(&[baseline_https_result("tls_ok", "ech_config_available")]));
        assert!(baseline_supports_ech_candidates(&[baseline_https_result("tls_ech_only", "none")]));
        assert!(baseline_has_tls_ech_only(&[baseline_https_result("tls_ech_only", "none")]));
        assert!(!baseline_supports_ech_candidates(&[baseline_https_result("tls_ok", "none")]));
        assert!(!baseline_has_tls_ech_only(&[baseline_https_result("tls_ok", "ech_config_available")]));
    }

    #[test]
    fn ordered_follow_up_tcp_candidates_prioritize_ech_candidates_after_tls_ech_only_baseline() {
        let candidates = build_tcp_candidates(&ProxyUiConfig::default());

        let ordered = ordered_follow_up_tcp_candidates(
            &candidates,
            Some(FailureClass::TlsAlert),
            &[baseline_https_result("tls_ech_only", "ech_config_available")],
            7,
        );
        let ids = ordered.iter().take(2).map(|candidate| candidate.id).collect::<Vec<_>>();

        assert_eq!(ids, vec!["ech_split", "ech_tlsrec"]);
        assert!(ordered
            .iter()
            .take(2)
            .all(|candidate| candidate.eligibility == CandidateEligibility::RequiresEchCapability));
    }

    #[test]
    fn ordered_follow_up_tcp_candidates_keep_normal_order_without_tls_ech_only_baseline() {
        let candidates = build_tcp_candidates(&ProxyUiConfig::default());
        let expected = interleave_candidate_families(
            reorder_tcp_candidates_for_failure(&candidates, Some(FailureClass::TlsAlert)).into_iter().skip(1).collect(),
            7,
        );

        let ordered = ordered_follow_up_tcp_candidates(
            &candidates,
            Some(FailureClass::TlsAlert),
            &[baseline_https_result("tls_ok", "ech_config_available")],
            7,
        );

        assert_eq!(
            ordered.iter().map(|candidate| candidate.id).collect::<Vec<_>>(),
            expected.iter().map(|candidate| candidate.id).collect::<Vec<_>>()
        );
    }

    #[test]
    fn resolve_strategy_probe_audit_assessment_high_when_matrix_is_consistent() {
        let tcp_candidates = vec![
            strategy_candidate_summary("tcp_runner_up", "split", 40, 100, 2, 5, false, "partial"),
            strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
        ];
        let quic_candidates = vec![
            strategy_candidate_summary("quic_runner_up", "quic_disabled", 45, 100, 1, 2, false, "partial"),
            strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
        ];

        let assessment = resolve_strategy_probe_audit_assessment(
            STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
            &tcp_candidates,
            &quic_candidates,
            &recommendation(),
            2,
            2,
            false,
        )
        .expect("audit assessment");

        assert_eq!(assessment.confidence.level, StrategyProbeAuditConfidenceLevel::High);
        assert_eq!(assessment.confidence.score, 100);
        assert_eq!(assessment.coverage.matrix_coverage_percent, 100);
        assert_eq!(assessment.coverage.winner_coverage_percent, 100);
        assert!(assessment.confidence.warnings.is_empty());
    }

    #[test]
    fn resolve_strategy_probe_audit_assessment_low_when_dns_short_circuited() {
        let tcp_candidates = vec![strategy_candidate_summary("tcp_winner", "baseline", 0, 0, 0, 5, true, "skipped")];
        let quic_candidates =
            vec![strategy_candidate_summary("quic_winner", "quic_disabled", 0, 0, 0, 2, true, "skipped")];

        let assessment = resolve_strategy_probe_audit_assessment(
            STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
            &tcp_candidates,
            &quic_candidates,
            &recommendation(),
            3,
            2,
            true,
        )
        .expect("audit assessment");

        assert_eq!(assessment.confidence.level, StrategyProbeAuditConfidenceLevel::Low);
        assert!(assessment.dns_short_circuited);
        assert_eq!(
            assessment.confidence.rationale,
            "Baseline DNS tampering short-circuited the audit before fallback candidates ran"
        );
        assert!(assessment
            .confidence
            .warnings
            .contains(&"Baseline DNS tampering short-circuited the audit before fallback candidates ran.".to_string()));
    }

    #[test]
    fn resolve_strategy_probe_audit_assessment_penalizes_incomplete_lane_execution() {
        let tcp_candidates = vec![
            strategy_candidate_summary("tcp_runner_up", "split", 50, 100, 2, 5, false, "partial"),
            strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
        ];
        let quic_candidates = vec![
            strategy_candidate_summary("quic_runner_up", "quic_disabled", 55, 100, 1, 2, false, "partial"),
            strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
        ];

        let assessment = resolve_strategy_probe_audit_assessment(
            STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
            &tcp_candidates,
            &quic_candidates,
            &recommendation(),
            4,
            4,
            false,
        )
        .expect("audit assessment");

        assert_eq!(assessment.confidence.level, StrategyProbeAuditConfidenceLevel::Medium);
        assert_eq!(assessment.confidence.score, 70);
        assert!(assessment
            .confidence
            .warnings
            .contains(&"TCP matrix coverage stayed below 75% of applicable candidates.".to_string()));
        assert!(assessment
            .confidence
            .warnings
            .contains(&"QUIC matrix coverage stayed below 75% of applicable candidates.".to_string()));
    }

    #[test]
    fn resolve_strategy_probe_audit_assessment_excludes_not_applicable_candidates_from_coverage() {
        let tcp_candidates = vec![
            strategy_candidate_summary("tcp_runner_up", "split", 90, 100, 4, 5, false, "success"),
            strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
            strategy_candidate_summary("tcp_not_applicable", "split", 0, 0, 0, 0, false, "not_applicable"),
        ];
        let quic_candidates = vec![
            strategy_candidate_summary("quic_runner_up", "quic_disabled", 90, 100, 1, 2, false, "success"),
            strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
            strategy_candidate_summary("quic_not_applicable", "quic_burst", 0, 0, 0, 0, false, "not_applicable"),
        ];

        let assessment = resolve_strategy_probe_audit_assessment(
            STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
            &tcp_candidates,
            &quic_candidates,
            &recommendation(),
            3,
            3,
            false,
        )
        .expect("audit assessment");

        assert_eq!(assessment.coverage.tcp_candidates_planned, 3);
        assert_eq!(assessment.coverage.tcp_candidates_not_applicable, 1);
        assert_eq!(assessment.coverage.quic_candidates_planned, 3);
        assert_eq!(assessment.coverage.quic_candidates_not_applicable, 1);
        assert_eq!(assessment.coverage.matrix_coverage_percent, 100);
        assert_eq!(assessment.confidence.level, StrategyProbeAuditConfidenceLevel::High);
        assert!(!assessment.confidence.warnings.iter().any(|warning| warning.contains("applicable candidates")));
    }

    #[test]
    fn resolve_strategy_probe_audit_assessment_does_not_penalize_non_ech_baselines_for_ech_candidates() {
        let tcp_candidates = vec![
            strategy_candidate_summary("tcp_runner_up", "split", 90, 100, 4, 5, false, "success"),
            strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
            strategy_candidate_summary("ech_split", "ech_split", 0, 0, 0, 0, false, "not_applicable"),
            strategy_candidate_summary("ech_tlsrec", "ech_tlsrec", 0, 0, 0, 0, false, "not_applicable"),
        ];
        let quic_candidates = vec![
            strategy_candidate_summary("quic_runner_up", "quic_disabled", 90, 100, 1, 2, false, "success"),
            strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
        ];

        let assessment = resolve_strategy_probe_audit_assessment(
            STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
            &tcp_candidates,
            &quic_candidates,
            &recommendation(),
            4,
            2,
            false,
        )
        .expect("audit assessment");

        assert_eq!(assessment.coverage.tcp_candidates_not_applicable, 2);
        assert_eq!(assessment.coverage.matrix_coverage_percent, 100);
        assert_eq!(assessment.confidence.level, StrategyProbeAuditConfidenceLevel::High);
        assert_eq!(assessment.confidence.score, 100);
        assert_eq!(assessment.confidence.rationale, "Matrix coverage and winner strength are consistent");
    }

    #[test]
    fn resolve_strategy_probe_audit_assessment_penalizes_narrow_winner_margin() {
        let tcp_candidates = vec![
            strategy_candidate_summary("tcp_runner_up", "split", 92, 100, 4, 5, false, "success"),
            strategy_candidate_summary("tcp_winner", "hostfake", 96, 100, 5, 5, false, "success"),
        ];
        let quic_candidates = vec![
            strategy_candidate_summary("quic_runner_up", "quic_disabled", 89, 100, 2, 2, false, "success"),
            strategy_candidate_summary("quic_winner", "quic_burst", 95, 100, 2, 2, false, "success"),
        ];

        let assessment = resolve_strategy_probe_audit_assessment(
            STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
            &tcp_candidates,
            &quic_candidates,
            &recommendation(),
            2,
            2,
            false,
        )
        .expect("audit assessment");

        assert_eq!(assessment.confidence.level, StrategyProbeAuditConfidenceLevel::High);
        assert_eq!(assessment.confidence.score, 80);
        assert!(assessment
            .confidence
            .warnings
            .contains(&"TCP winner margin stayed below 10 points over the next candidate.".to_string()));
        assert!(assessment
            .confidence
            .warnings
            .contains(&"QUIC winner margin stayed below 10 points over the next candidate.".to_string()));
    }

    #[test]
    fn test_audit_assessment_penalizes_all_tied_candidates() {
        let tcp_candidates: Vec<_> = (0..15)
            .map(|i| strategy_candidate_summary(&format!("tcp_{i}"), "split", 2, 9, 1, 6, false, "partial"))
            .collect();
        let quic_candidates: Vec<_> = (0..3)
            .map(|i| strategy_candidate_summary(&format!("quic_{i}"), "quic", 0, 4, 0, 2, false, "failed"))
            .collect();
        let rec = StrategyProbeRecommendation {
            tcp_candidate_id: "tcp_0".to_string(),
            tcp_candidate_label: "tcp 0".to_string(),
            quic_candidate_id: "quic_0".to_string(),
            quic_candidate_label: "quic 0".to_string(),
            rationale: "all tied".to_string(),
            recommended_proxy_config_json: String::new(),
        };
        let assessment = resolve_strategy_probe_audit_assessment(
            STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
            &tcp_candidates,
            &quic_candidates,
            &rec,
            15,
            3,
            false,
        );
        let assessment = assessment.expect("should produce assessment for full_matrix_v1");
        assert_eq!(assessment.confidence.level, StrategyProbeAuditConfidenceLevel::Low);
        assert!(assessment.confidence.warnings.iter().any(|w| w.contains("TCP candidates produced identical results")));
        assert!(assessment
            .confidence
            .warnings
            .iter()
            .any(|w| w.contains("QUIC candidates produced identical results")));
    }
}
