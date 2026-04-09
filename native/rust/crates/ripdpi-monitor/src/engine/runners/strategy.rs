#[path = "strategy_support.rs"]
mod strategy_support;

use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{
    build_quic_candidates_for_suite, build_strategy_probe_summary, candidate_pause_ms, probe_fake_ttl_capability,
    probe_tcp_fast_open_capability, CandidateEligibility, StrategyCandidateSpec,
};
use crate::classification::{
    classified_failure_probe_result, classify_strategy_probe_baseline_observations, filter_quic_candidates_for_failure,
    interleave_candidate_families, next_candidate_index,
};
use crate::connectivity::set_progress;
use crate::execution::{
    eliminated_candidate_summary, execute_quic_candidate, execute_tcp_candidate, skipped_candidate_summary,
    winning_candidate_index, CandidateExecution,
};
use crate::observations::observations_for_results;
use crate::strategy::detect_strategy_probe_dns_tampering;
use crate::types::{
    ScanProgress, StrategyProbeAuditAssessment, StrategyProbeAuditConfidence, StrategyProbeAuditConfidenceLevel,
    StrategyProbeAuditCoverage, StrategyProbeCandidateSummary, StrategyProbeCompletionKind, StrategyProbeProgressLane,
    StrategyProbeRecommendation, StrategyProbeReport,
};
use crate::util::{stable_probe_hash, STRATEGY_PROBE_SUITE_FULL_MATRIX_V1};

#[cfg(test)]
use self::strategy_support::baseline_has_tls_ech_only;
use self::strategy_support::{
    baseline_supports_ech_candidates, compute_rst_adaptive_timeout, ordered_follow_up_tcp_candidates,
    record_not_applicable_tcp_candidate, resolve_recommended_proxy_config_json, round_percent,
    strategy_audit_lane_counts, strategy_probe_live_progress_with_targets, FamilyFailureTracker,
    StrategyAuditLaneCounts, ECH_ELIGIBILITY_RATIONALE, FAKE_TTL_ELIGIBILITY_RATIONALE,
    TCP_FAST_OPEN_ELIGIBILITY_RATIONALE,
};
use super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};

pub(super) struct StrategyDnsBaselineRunner;
pub(super) struct StrategyTcpRunner;
pub(super) struct StrategyQuicRunner;
pub(super) struct StrategyRecommendationRunner;

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

    // Clean network: baseline works and all strategies tie with high coverage.
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
            None,
            artifacts,
        );
        runtime.results.push(classified_failure_probe_result("Current strategy", &baseline.failure));
        tracing::info!(failure_class = ?baseline.failure.class, action = ?baseline.failure.action, "strategy probe: baseline classified");
        runtime.strategy.baseline_failure = Some(baseline.failure);

        // If we have encrypted IP overrides, build override targets so TCP/QUIC
        // runners can probe using trusted IPs instead of poisoned system DNS.
        if !baseline.encrypted_ip_overrides.is_empty() {
            let overrides = &baseline.encrypted_ip_overrides;
            let mut domain_ov = plan.request.domain_targets.clone();
            for t in &mut domain_ov {
                if t.connect_ip.is_none() {
                    t.connect_ip = overrides.iter().find(|(h, _)| h == &t.host).map(|(_, ip)| ip.to_string());
                }
            }
            let mut quic_ov = plan.request.quic_targets.clone();
            for t in &mut quic_ov {
                if t.connect_ip.is_none() {
                    t.connect_ip = overrides.iter().find(|(h, _)| h == &t.host).map(|(_, ip)| ip.to_string());
                }
            }
            runtime.strategy.dns_override_domain_targets = Some(domain_ov);
            runtime.strategy.dns_override_quic_targets = Some(quic_ov);
        }

        // Continue to TCP/QUIC runners instead of short-circuiting, so we get
        // actual strategy effectiveness data even on DNS-tampered networks.
        RunnerOutcome::Completed
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
        // Use encrypted-DNS-resolved targets when DNS tampering was detected.
        // Clone to avoid holding an immutable borrow on `runtime` across mutable calls.
        let domain_targets =
            runtime.strategy.dns_override_domain_targets.clone().unwrap_or_else(|| plan.request.domain_targets.clone());
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
            &domain_targets,
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
            // Publish the DPI failure class so the UI can show it as a badge.
            runtime.publish_progress(
                plan,
                self.phase(),
                runtime.completed_steps,
                format!("DPI: {}", failure.class.as_str()),
                Some("baseline_failure_class".to_string()),
                Some(failure.class.as_str().to_string()),
                None,
            );
        }
        runtime.record_step(
            plan,
            self.phase(),
            format!("Tested {}", baseline_spec.label),
            Some(baseline_spec.label.to_string()),
            Some(baseline_execution.summary.outcome.clone()),
            Some(strategy_probe_live_progress_with_targets(
                StrategyProbeProgressLane::Tcp,
                1,
                tcp_candidate_total,
                baseline_spec.id,
                baseline_spec.label,
                baseline_execution.summary.succeeded_targets,
                baseline_execution.summary.total_targets,
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
        let fake_ttl_available = probe_fake_ttl_capability();
        let tcp_fast_open_available = probe_tcp_fast_open_capability();
        tracing::info!(fake_ttl_available, tcp_fast_open_available, "strategy probe: capabilities probed");
        if let Some(ref failure) = runtime.strategy.baseline_failure {
            if let Some(timeout) = compute_rst_adaptive_timeout(failure) {
                tracing::info!(adaptive_timeout_ms = timeout.as_millis(), "strategy probe: adaptive timeout (rst)");
            }
        }
        let mut pending_tcp_specs = ordered_follow_up_tcp_candidates(
            tcp_specs,
            runtime.strategy.baseline_failure.as_ref().map(|value| value.class),
            &baseline_execution.results,
            strategy_plan.probe_seed,
            fake_ttl_available,
        );
        // Quick scan: truncate candidate list when max_candidates is set.
        if let Some(max) = strategy_plan.max_candidates {
            let remaining = max.saturating_sub(1); // baseline already counted
            if pending_tcp_specs.len() > remaining {
                pending_tcp_specs.truncate(remaining);
            }
        }
        // Round 1 qualifier: test each candidate against 1 domain first.
        // Eliminates candidates that fail completely before the full-matrix run.
        // Candidates are tested in parallel batches of up to 3 to reduce wall-clock time.
        // Skipped when max_candidates is set (quick scan) since the list is already small.
        if strategy_plan.max_candidates.is_none() && domain_targets.len() > 1 {
            let qualifier_targets = &domain_targets[..1];
            let mut qualified_specs: Vec<StrategyCandidateSpec> = Vec::with_capacity(pending_tcp_specs.len());
            let mut eliminated_count = 0usize;

            // Partition into pass-through and testable candidates.
            let mut testable_specs: Vec<StrategyCandidateSpec> = Vec::new();
            for spec in pending_tcp_specs.drain(..) {
                let pass_through = spec.id == "baseline_current"
                    || (spec.eligibility == CandidateEligibility::RequiresEchCapability && !baseline_ech_capable)
                    || (spec.requires_fake_ttl && !fake_ttl_available)
                    || (spec.requires_tcp_fast_open && !tcp_fast_open_available);
                if pass_through {
                    qualified_specs.push(spec);
                } else {
                    testable_specs.push(spec);
                }
            }

            // Test in parallel batches of up to 3, grouped by family so each
            // family gets at least one representative tested early.
            const QUALIFIER_PARALLELISM: usize = 3;
            for batch in testable_specs.chunks(QUALIFIER_PARALLELISM) {
                if runtime.is_cancelled() || runtime.is_past_deadline() {
                    // Don't eliminate untested candidates on cancellation/deadline.
                    for spec in batch {
                        qualified_specs.push(spec.clone());
                    }
                    continue;
                }
                let cancel_token = runtime.cancel_token();
                let batch_results: Vec<(StrategyCandidateSpec, Option<CandidateExecution>)> = thread::scope(|s| {
                    let handles: Vec<_> = batch
                        .iter()
                        .map(|spec| {
                            let spec_clone = spec.clone();
                            s.spawn(move || {
                                if cancel_token.load(Ordering::Acquire) {
                                    return (spec_clone, None);
                                }
                                let execution = execute_tcp_candidate(
                                    &spec_clone,
                                    qualifier_targets,
                                    strategy_plan.runtime_context.as_ref(),
                                    strategy_plan.probe_seed,
                                    tls_verifier,
                                    cancel_token,
                                );
                                (spec_clone, Some(execution))
                            })
                        })
                        .collect();
                    handles.into_iter().map(|h| h.join().expect("qualifier thread panicked")).collect()
                });

                for (spec, maybe_execution) in batch_results {
                    let Some(execution) = maybe_execution else {
                        // Cancelled before starting -- pass through.
                        qualified_specs.push(spec);
                        continue;
                    };
                    if execution.cancelled {
                        qualified_specs.push(spec);
                        continue;
                    }
                    if execution.summary.succeeded_targets > 0 {
                        qualified_specs.push(spec);
                    } else {
                        let summary = eliminated_candidate_summary(
                            &spec,
                            execution.summary.succeeded_targets,
                            execution.summary.total_targets,
                            3,
                        );
                        runtime.strategy.tcp_candidates.push(summary);
                        eliminated_count += 1;
                    }
                }
            }
            // Safety: if all candidates were eliminated (shouldn't happen since
            // baseline always qualifies), skip elimination to avoid empty run.
            if qualified_specs.is_empty() {
                tracing::warn!("strategy probe: Round 1 qualifier eliminated all candidates — skipping elimination");
                // pending_tcp_specs was drained; leave it empty and let the main loop exit cleanly.
            } else {
                let qualified_count = qualified_specs.len();
                tracing::info!(
                    qualified = qualified_count,
                    eliminated = eliminated_count,
                    "strategy probe: Round 1 qualifier complete"
                );
                pending_tcp_specs = qualified_specs;
            }
        }
        let tcp_failure_tracker = Mutex::new(FamilyFailureTracker::new(strategy_plan.suite.family_failure_threshold));
        let planned_count = tcp_specs.len();
        let mut executed_count = 1usize; // baseline already executed

        // Round 2: test up to 2 candidates concurrently to reduce wall-clock time.
        const ROUND2_PARALLELISM: usize = 2;
        while !pending_tcp_specs.is_empty() {
            if runtime.is_cancelled() || runtime.is_past_deadline() {
                tracing::warn!(
                    executed = executed_count,
                    planned = planned_count,
                    "strategy probe: TCP suite terminated early"
                );
                break;
            }

            // Pick up to ROUND2_PARALLELISM candidates, skipping blocked families.
            let mut batch: Vec<(usize, StrategyCandidateSpec)> = Vec::with_capacity(ROUND2_PARALLELISM);
            {
                let tracker = tcp_failure_tracker.lock().unwrap();
                while batch.len() < ROUND2_PARALLELISM && !pending_tcp_specs.is_empty() {
                    let idx = next_candidate_index(&pending_tcp_specs, tracker.blocked_family());
                    let spec = pending_tcp_specs.remove(idx);
                    let candidate_index = runtime.strategy.tcp_candidates.len() + batch.len() + 1;
                    batch.push((candidate_index, spec));
                }
            }

            // Pre-filter: handle skip/not-applicable candidates synchronously,
            // collect candidates that need actual execution for parallel testing.
            let mut to_execute: Vec<(usize, StrategyCandidateSpec)> = Vec::new();
            for (candidate_index, spec) in batch {
                tracing::debug!(candidate = spec.id, label = spec.label, "strategy probe: testing TCP candidate");
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
                if strategy_plan.suite.short_circuit_hostfake && spec.family == "hostfake" && hostfake_family_succeeded
                {
                    let summary = skipped_candidate_summary(
                        &spec,
                        domain_targets.len() * 2,
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
                let na_check: Option<(&str, &str)> =
                    if spec.eligibility == CandidateEligibility::RequiresEchCapability && !baseline_ech_capable {
                        Some((ECH_ELIGIBILITY_RATIONALE, ""))
                    } else if spec.requires_fake_ttl && !fake_ttl_available {
                        Some((FAKE_TTL_ELIGIBILITY_RATIONALE, " — TTL manipulation unavailable"))
                    } else if spec.requires_tcp_fast_open && !tcp_fast_open_available {
                        Some((TCP_FAST_OPEN_ELIGIBILITY_RATIONALE, " — TCP Fast Open unavailable"))
                    } else {
                        None
                    };
                if let Some((reason, suffix)) = na_check {
                    tracing::debug!(candidate = spec.id, reason, "strategy probe: candidate not_applicable");
                    record_not_applicable_tcp_candidate(
                        runtime,
                        plan,
                        self.phase(),
                        &spec,
                        candidate_index,
                        tcp_candidate_total,
                        reason,
                        suffix,
                    );
                    continue;
                }
                to_execute.push((candidate_index, spec));
            }

            if to_execute.is_empty() {
                continue;
            }

            // Execute candidates in parallel using thread::scope.
            let cancel_token = runtime.cancel_token();
            let domain_targets_ref = &domain_targets;
            let exec_results: Vec<(usize, StrategyCandidateSpec, CandidateExecution)> = thread::scope(|s| {
                let handles: Vec<_> = to_execute
                    .into_iter()
                    .map(|(candidate_index, spec)| {
                        s.spawn(move || {
                            let execution = execute_tcp_candidate(
                                &spec,
                                domain_targets_ref,
                                strategy_plan.runtime_context.as_ref(),
                                strategy_plan.probe_seed,
                                tls_verifier,
                                cancel_token,
                            );
                            (candidate_index, spec, execution)
                        })
                    })
                    .collect();
                handles.into_iter().map(|h| h.join().expect("tcp candidate thread panicked")).collect()
            });

            // Merge results back into the runtime sequentially.
            let mut any_cancelled = false;
            for (candidate_index, spec, execution) in exec_results {
                if execution.cancelled {
                    any_cancelled = true;
                    continue;
                }
                if execution.summary.family == "hostfake"
                    && execution.summary.succeeded_targets == execution.summary.total_targets
                {
                    hostfake_family_succeeded = true;
                }
                let failed = execution.summary.outcome == "failed";
                runtime.record_step(
                    plan,
                    self.phase(),
                    format!("Tested {}", spec.label),
                    Some(spec.label.to_string()),
                    Some(execution.summary.outcome.clone()),
                    Some(strategy_probe_live_progress_with_targets(
                        StrategyProbeProgressLane::Tcp,
                        candidate_index,
                        tcp_candidate_total,
                        spec.id,
                        spec.label,
                        execution.summary.succeeded_targets,
                        execution.summary.total_targets,
                    )),
                    RunnerArtifacts::from_results(
                        execution.results.clone(),
                        "strategy_probe",
                        if failed { "warn" } else { "info" },
                        format!("Testing TCP candidate {}", spec.label),
                    ),
                );
                runtime.strategy.tcp_candidates.push(execution.summary);
                executed_count += 1;
                tcp_failure_tracker.lock().unwrap().record(spec.family, failed);
                if tcp_failure_tracker.lock().unwrap().blocked_family().is_some() {
                    tracing::debug!(
                        candidate = spec.id,
                        family = spec.family,
                        "strategy probe: candidate skipped, family blocked"
                    );
                }
            }
            if any_cancelled {
                return RunnerOutcome::Cancelled;
            }
            // Break out with partial results if the scan deadline has passed.
            if runtime.is_past_deadline() {
                tracing::warn!(
                    executed = executed_count,
                    planned = planned_count,
                    "strategy probe: TCP suite deadline-terminated"
                );
                break;
            }
            if !pending_tcp_specs.is_empty() {
                // Brief pause between batches to avoid overwhelming the network.
                thread::sleep(Duration::from_millis(candidate_pause_ms(
                    strategy_plan.probe_seed,
                    // Use the first spec's seed for pause calculation.
                    tcp_specs.first().expect("tcp candidate"),
                    false,
                )));
            }
        }
        tracing::info!(executed = executed_count, planned = planned_count, "strategy probe: TCP suite completed");
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
        let tcp_winner_id = winning_candidate_index(&runtime.strategy.tcp_candidates)
            .map(|i| runtime.strategy.tcp_candidates[i].id.as_str());
        let tcp_winner_spec = tcp_winner_id
            .and_then(|id| strategy_plan.suite.tcp_candidates.iter().find(|s| s.id == id))
            .or_else(|| strategy_plan.suite.tcp_candidates.first());
        let Some(tcp_winner_spec) = tcp_winner_spec else {
            return RunnerOutcome::Completed;
        };
        let quic_specs = filter_quic_candidates_for_failure(
            build_quic_candidates_for_suite(&strategy_plan.suite_id, &tcp_winner_spec.config)
                .unwrap_or_else(|_| strategy_plan.suite.quic_candidates.clone()),
            runtime.strategy.baseline_failure.as_ref().map(|value| value.class),
        );
        // Use encrypted-DNS-resolved targets when DNS tampering was detected.
        // Clone to avoid holding an immutable borrow on `runtime` across mutable calls.
        let quic_targets =
            runtime.strategy.dns_override_quic_targets.clone().unwrap_or_else(|| plan.request.quic_targets.clone());
        let quic_candidate_total = quic_specs.len();
        if quic_candidate_total == 0 {
            return RunnerOutcome::Completed;
        }
        let mut pending_quic_specs =
            interleave_candidate_families(quic_specs.clone(), stable_probe_hash(strategy_plan.probe_seed, "quic"));
        if let Some(max) = strategy_plan.max_candidates {
            if pending_quic_specs.len() > max {
                pending_quic_specs.truncate(max);
            }
        }
        let mut quic_family_succeeded = false;
        let mut quic_failure_tracker = FamilyFailureTracker::new(strategy_plan.suite.family_failure_threshold);
        while !pending_quic_specs.is_empty() {
            let candidate_index = runtime.strategy.quic_candidates.len() + 1;
            let spec = pending_quic_specs
                .remove(next_candidate_index(&pending_quic_specs, quic_failure_tracker.blocked_family()));
            if runtime.is_cancelled() || runtime.is_past_deadline() {
                tracing::warn!("strategy probe: QUIC suite terminated early");
                break;
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
                    quic_targets.len(),
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
                &quic_targets,
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
            runtime.record_step(
                plan,
                self.phase(),
                format!("Tested {}", spec.label),
                Some(spec.label.to_string()),
                Some(execution.summary.outcome.clone()),
                Some(strategy_probe_live_progress_with_targets(
                    StrategyProbeProgressLane::Quic,
                    candidate_index,
                    quic_candidate_total,
                    spec.id,
                    spec.label,
                    execution.summary.succeeded_targets,
                    execution.summary.total_targets,
                )),
                RunnerArtifacts::from_results(
                    execution.results.clone(),
                    "strategy_probe",
                    if failed { "warn" } else { "info" },
                    format!("Testing QUIC candidate {}", spec.label),
                ),
            );
            runtime.strategy.quic_candidates.push(execution.summary);
            if runtime.is_past_deadline() {
                tracing::warn!("strategy probe: QUIC suite deadline-terminated");
                break;
            }
            quic_failure_tracker.record(spec.family, failed);
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
        let wi_tcp = winning_candidate_index(&runtime.strategy.tcp_candidates).unwrap_or(0);
        let wi_quic = winning_candidate_index(&runtime.strategy.quic_candidates).unwrap_or(0);
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
            return RunnerOutcome::Completed;
        };
        let recommendation = StrategyProbeRecommendation {
            tcp_candidate_id: tcp_w.id.clone(),
            tcp_candidate_label: tcp_w.label.clone(),
            quic_candidate_id: quic_w.id.clone(),
            quic_candidate_label: quic_w.label.clone(),
            rationale: format!(
                "{} with {} weighted TCP success and {} weighted QUIC success",
                tcp_w.label, tcp_w.weighted_success_score, quic_w.weighted_success_score,
            ),
            recommended_proxy_config_json: resolve_recommended_proxy_config_json(quic_w, quic_winner_spec),
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
        let is_dns_tampered = runtime.strategy.dns_override_domain_targets.is_some();
        let is_partial = runtime.strategy.tcp_candidates.len() < strategy_plan.suite.tcp_candidates.len()
            || runtime.strategy.quic_candidates.len() < strategy_plan.suite.quic_candidates.len();
        runtime.strategy.strategy_probe_report = Some(StrategyProbeReport {
            suite_id: strategy_plan.suite_id.clone(),
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
            domain_strategy_seeds: Vec::new(),
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
#[path = "strategy_tests.rs"]
mod tests;
