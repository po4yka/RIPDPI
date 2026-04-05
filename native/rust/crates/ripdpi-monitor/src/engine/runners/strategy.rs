use std::sync::Arc;
use std::thread;
use std::time::Duration;

use ripdpi_failure_classifier::FailureClass;
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{
    build_quic_candidates_for_suite, build_strategy_probe_summary, candidate_pause_ms, probe_fake_ttl_capability,
    probe_tcp_fast_open_capability, CandidateEligibility, StrategyCandidateSpec,
};
use crate::classification::{
    classified_failure_probe_result, classify_strategy_probe_baseline_observations, filter_quic_candidates_for_failure,
    interleave_candidate_families, next_candidate_index, reorder_tcp_candidates_for_failure,
};
use crate::connectivity::set_progress;
use crate::execution::{
    eliminated_candidate_summary, execute_quic_candidate, execute_tcp_candidate, not_applicable_candidate_execution,
    skipped_candidate_summary, winning_candidate_index,
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

use super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};

pub(super) struct StrategyDnsBaselineRunner;
pub(super) struct StrategyTcpRunner;
pub(super) struct StrategyQuicRunner;
pub(super) struct StrategyRecommendationRunner;

const ECH_ELIGIBILITY_RATIONALE: &str =
    "Baseline did not expose an ECH-capable HTTPS target, so ECH extension splitting would be a no-op";

const FAKE_TTL_ELIGIBILITY_RATIONALE: &str =
    "setsockopt(IP_TTL) is unavailable on this platform (Android VPN/tun mode); fake-packet strategies that rely on TTL manipulation are skipped";

const TCP_FAST_OPEN_ELIGIBILITY_RATIONALE: &str =
    "TCP Fast Open is unavailable on this device/kernel, so TFO probe variants are skipped";

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
        succeeded_targets: 0,
        total_targets: 0,
    }
}

fn strategy_probe_live_progress_with_targets(
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

/// Tracks consecutive failures within a candidate family, blocking the family
/// after `threshold` consecutive failures to avoid wasting probe budget.
struct FamilyFailureTracker<'a> {
    blocked: Option<&'a str>,
    last_failed: Option<&'a str>,
    consecutive: usize,
    threshold: usize,
}

impl<'a> FamilyFailureTracker<'a> {
    fn new(threshold: usize) -> Self {
        Self { blocked: None, last_failed: None, consecutive: 0, threshold }
    }

    fn record(&mut self, family: &'a str, failed: bool) {
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
}

fn probe_detail_value<'a>(result: &'a ProbeResult, key: &str) -> Option<&'a str> {
    result.details.iter().find(|detail| detail.key == key).map(|detail| detail.value.as_str())
}

/// If the baseline failure class is TcpReset, return a reduced connect timeout.
/// RSTs arrive fast (the censorship device sends them actively), so waiting the
/// full CONNECT_TIMEOUT is wasteful for subsequent strategy probes.
/// Returns None when the failure class suggests silent drops (need full wait).
fn compute_rst_adaptive_timeout(baseline_failure: &ripdpi_failure_classifier::ClassifiedFailure) -> Option<Duration> {
    if !matches!(baseline_failure.class, FailureClass::TcpReset) {
        return None;
    }
    Some(Duration::from_millis(1500))
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
    fake_ttl_available: bool,
) -> Vec<StrategyCandidateSpec> {
    let reordered = reorder_tcp_candidates_for_failure(tcp_specs, failure_class, fake_ttl_available)
        .into_iter()
        .skip(1)
        .collect::<Vec<_>>();
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

    let coverage = StrategyProbeAuditCoverage {
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
    };

    // Clean network: baseline works and all strategies tie with high coverage.
    let no_evasion_needed = !dns_short_circuited
        && all_tcp_tied
        && all_quic_tied
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

    let penalty_table: &[(bool, i32, &str)] = &[
        (dns_short_circuited, 45, "Baseline DNS tampering short-circuited the audit before fallback candidates ran."),
        (
            weak_winner_coverage,
            25,
            "The winning TCP or QUIC lane recovered too few weighted targets to trust the recommendation.",
        ),
        (low_tcp_execution, 15, "TCP matrix coverage stayed below 75% of applicable candidates."),
        (low_quic_execution, 15, "QUIC matrix coverage stayed below 75% of applicable candidates."),
        (narrow_tcp_margin, 10, "TCP winner margin stayed below 10 points over the next candidate."),
        (narrow_quic_margin, 10, "QUIC winner margin stayed below 10 points over the next candidate."),
        (all_tcp_tied, 20, "All TCP candidates produced identical results; the winner is arbitrary."),
        (all_quic_tied, 15, "All QUIC candidates produced identical results; the winner is arbitrary."),
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
        coverage,
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
            None,
            artifacts,
        );
        runtime.results.push(classified_failure_probe_result("Current strategy", &baseline.failure));

        // Store baseline failure for downstream runners.
        let class = baseline.failure.class;
        let action = baseline.failure.action;
        tracing::info!(failure_class = ?class, action = ?action, "strategy probe: baseline classified");
        runtime.strategy.baseline_failure = Some(baseline.failure);

        // If we have encrypted IP overrides, build override targets so TCP/QUIC
        // runners can probe using trusted IPs instead of poisoned system DNS.
        if !baseline.encrypted_ip_overrides.is_empty() {
            let domain_overrides: Vec<_> = plan
                .request
                .domain_targets
                .iter()
                .map(|target| {
                    let mut t = target.clone();
                    if t.connect_ip.is_none() {
                        if let Some((_, ip)) = baseline.encrypted_ip_overrides.iter().find(|(h, _)| h == &t.host) {
                            t.connect_ip = Some(ip.to_string());
                        }
                    }
                    t
                })
                .collect();
            let quic_overrides: Vec<_> = plan
                .request
                .quic_targets
                .iter()
                .map(|target| {
                    let mut t = target.clone();
                    if t.connect_ip.is_none() {
                        if let Some((_, ip)) = baseline.encrypted_ip_overrides.iter().find(|(h, _)| h == &t.host) {
                            t.connect_ip = Some(ip.to_string());
                        }
                    }
                    t
                })
                .collect();
            runtime.strategy.dns_override_domain_targets = Some(domain_overrides);
            runtime.strategy.dns_override_quic_targets = Some(quic_overrides);
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
        tracing::info!(fake_ttl_available = fake_ttl_available, "strategy probe: TTL capability probed");
        tracing::info!(
            tcp_fast_open_available = tcp_fast_open_available,
            "strategy probe: TCP Fast Open capability probed"
        );
        if !fake_ttl_available {
            tracing::debug!("TTL capability probe failed — fake-packet candidates will be marked not_applicable");
        }
        if !tcp_fast_open_available {
            tracing::debug!("TCP Fast Open capability probe failed — TFO candidates will be marked not_applicable");
        }
        if let Some(ref failure) = runtime.strategy.baseline_failure {
            if let Some(timeout) = compute_rst_adaptive_timeout(failure) {
                tracing::info!(
                    adaptive_timeout_ms = timeout.as_millis(),
                    reason = "rst_pattern",
                    "strategy probe: using adaptive timeout"
                );
            }
        }
        let ordered_tcp_specs = ordered_follow_up_tcp_candidates(
            tcp_specs,
            runtime.strategy.baseline_failure.as_ref().map(|value| value.class),
            &baseline_execution.results,
            strategy_plan.probe_seed,
            fake_ttl_available,
        );
        // TODO: Staggered 2-candidate parallelism (Psiphon-style) could reduce
        // total scan time by ~1.5x. Deferred due to DPI correlation risk when two
        // candidates probe the same blocked domain from different proxy ports
        // simultaneously. Mitigation would require 500ms stagger + different domain
        // ordering per candidate in the pair.
        let mut pending_tcp_specs = ordered_tcp_specs;
        // Round 1 qualifier: test each candidate against 1 domain first.
        // Eliminates candidates that fail completely before the full-matrix run.
        if domain_targets.len() > 1 {
            let qualifier_targets = &domain_targets[..1];
            let mut qualified_specs: Vec<StrategyCandidateSpec> = Vec::with_capacity(pending_tcp_specs.len());
            let mut eliminated_count = 0usize;
            for spec in pending_tcp_specs.drain(..) {
                if runtime.is_cancelled() || runtime.is_past_deadline() {
                    // Don't eliminate untested candidates on cancellation/deadline.
                    qualified_specs.push(spec);
                    continue;
                }
                // Baseline always qualifies; not-applicable candidates pass through.
                let pass_through = spec.id == "baseline_current"
                    || (spec.eligibility == CandidateEligibility::RequiresEchCapability && !baseline_ech_capable)
                    || (spec.requires_fake_ttl && !fake_ttl_available)
                    || (spec.requires_tcp_fast_open && !tcp_fast_open_available);
                if pass_through {
                    qualified_specs.push(spec);
                    continue;
                }
                let qualifier_execution = execute_tcp_candidate(
                    &spec,
                    qualifier_targets,
                    strategy_plan.runtime_context.as_ref(),
                    strategy_plan.probe_seed,
                    tls_verifier,
                    runtime.cancel_token(),
                );
                if qualifier_execution.cancelled {
                    // Treat as pass-through so the main loop handles cancellation.
                    qualified_specs.push(spec);
                    continue;
                }
                if qualifier_execution.summary.succeeded_targets > 0 {
                    qualified_specs.push(spec);
                } else {
                    let summary = eliminated_candidate_summary(
                        &spec,
                        qualifier_execution.summary.succeeded_targets,
                        qualifier_execution.summary.total_targets,
                        3,
                    );
                    runtime.strategy.tcp_candidates.push(summary);
                    eliminated_count += 1;
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
        let mut tcp_failure_tracker = FamilyFailureTracker::new(strategy_plan.suite.family_failure_threshold);
        let planned_count = tcp_specs.len();
        let mut executed_count = 1usize; // baseline already executed
        while !pending_tcp_specs.is_empty() {
            let candidate_index = runtime.strategy.tcp_candidates.len() + 1;
            let spec = pending_tcp_specs.remove(next_candidate_index(&pending_tcp_specs, tcp_failure_tracker.blocked));
            tracing::debug!(candidate = spec.id, label = spec.label, "strategy probe: testing TCP candidate");
            if runtime.is_cancelled() || runtime.is_past_deadline() {
                tracing::warn!(
                    executed = executed_count,
                    planned = planned_count,
                    "strategy probe: TCP suite terminated early"
                );
                break;
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
            if spec.eligibility == CandidateEligibility::RequiresEchCapability && !baseline_ech_capable {
                tracing::debug!(
                    candidate = spec.id,
                    reason = ECH_ELIGIBILITY_RATIONALE,
                    "strategy probe: candidate not_applicable"
                );
                let execution =
                    not_applicable_candidate_execution(&spec, domain_targets.len() * 2, 3, ECH_ELIGIBILITY_RATIONALE);
                runtime.record_step(
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
                        "debug",
                        format!("Skipped execution for {}", spec.label),
                    ),
                );
                runtime.strategy.tcp_candidates.push(execution.summary);
                continue;
            }
            if spec.requires_fake_ttl && !fake_ttl_available {
                tracing::debug!(
                    candidate = spec.id,
                    reason = FAKE_TTL_ELIGIBILITY_RATIONALE,
                    "strategy probe: candidate not_applicable"
                );
                let execution = not_applicable_candidate_execution(
                    &spec,
                    domain_targets.len() * 2,
                    3,
                    FAKE_TTL_ELIGIBILITY_RATIONALE,
                );
                runtime.record_step(
                    plan,
                    self.phase(),
                    format!("Marked {} as not applicable (no TTL capability)", spec.label),
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
                        "debug",
                        format!("Skipped execution for {} — TTL manipulation unavailable", spec.label),
                    ),
                );
                runtime.strategy.tcp_candidates.push(execution.summary);
                continue;
            }
            if spec.requires_tcp_fast_open && !tcp_fast_open_available {
                tracing::debug!(
                    candidate = spec.id,
                    reason = TCP_FAST_OPEN_ELIGIBILITY_RATIONALE,
                    "strategy probe: candidate not_applicable"
                );
                let execution = not_applicable_candidate_execution(
                    &spec,
                    domain_targets.len() * 2,
                    3,
                    TCP_FAST_OPEN_ELIGIBILITY_RATIONALE,
                );
                runtime.record_step(
                    plan,
                    self.phase(),
                    format!("Marked {} as not applicable (no TCP Fast Open support)", spec.label),
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
                        "debug",
                        format!("Skipped execution for {} — TCP Fast Open unavailable", spec.label),
                    ),
                );
                runtime.strategy.tcp_candidates.push(execution.summary);
                continue;
            }

            let execution = execute_tcp_candidate(
                &spec,
                &domain_targets,
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
            // Break out with partial results if the scan deadline has passed,
            // so the recommendation runner can still process completed candidates.
            if runtime.is_past_deadline() {
                tracing::warn!(
                    executed = executed_count,
                    planned = planned_count,
                    "strategy probe: TCP suite deadline-terminated"
                );
                break;
            }
            tcp_failure_tracker.record(spec.family, failed);
            if tcp_failure_tracker.blocked.is_some() {
                tracing::debug!(
                    candidate = spec.id,
                    family = spec.family,
                    "strategy probe: candidate skipped, family blocked"
                );
            }
            if !pending_tcp_specs.is_empty() {
                thread::sleep(Duration::from_millis(candidate_pause_ms(strategy_plan.probe_seed, &spec, failed)));
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
        let mut quic_family_succeeded = false;
        let mut quic_failure_tracker = FamilyFailureTracker::new(strategy_plan.suite.family_failure_threshold);
        while !pending_quic_specs.is_empty() {
            let candidate_index = runtime.strategy.quic_candidates.len() + 1;
            let spec =
                pending_quic_specs.remove(next_candidate_index(&pending_quic_specs, quic_failure_tracker.blocked));
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
            completion_kind: if runtime.strategy.dns_override_domain_targets.is_some() {
                StrategyProbeCompletionKind::DnsTamperingWithFallback
            } else if runtime.strategy.tcp_candidates.len() < strategy_plan.suite.tcp_candidates.len()
                || runtime.strategy.quic_candidates.len() < strategy_plan.suite.quic_candidates.len()
            {
                StrategyProbeCompletionKind::PartialResults
            } else {
                StrategyProbeCompletionKind::Normal
            },
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

    use super::FamilyFailureTracker;

    #[test]
    fn family_tracker_blocks_after_threshold_2() {
        let mut tracker = FamilyFailureTracker::new(2);
        tracker.record("hostfake", true);
        assert!(tracker.blocked != Some("hostfake"));
        tracker.record("hostfake", true);
        assert!(tracker.blocked == Some("hostfake"));
    }

    #[test]
    fn family_tracker_blocks_after_threshold_4() {
        let mut tracker = FamilyFailureTracker::new(4);
        tracker.record("hostfake", true);
        tracker.record("hostfake", true);
        assert!(tracker.blocked != Some("hostfake"));
        tracker.record("hostfake", true);
        assert!(tracker.blocked != Some("hostfake"));
        tracker.record("hostfake", true);
        assert!(tracker.blocked == Some("hostfake"));
    }

    #[test]
    fn family_tracker_resets_on_success() {
        let mut tracker = FamilyFailureTracker::new(2);
        tracker.record("hostfake", true);
        tracker.record("hostfake", false); // success resets consecutive count and blocked
        tracker.record("hostfake", true);
        assert!(tracker.blocked != Some("hostfake")); // only 1 consecutive failure after reset
    }

    #[test]
    fn family_tracker_resets_on_different_family() {
        let mut tracker = FamilyFailureTracker::new(2);
        tracker.record("hostfake", true);
        tracker.record("split", true); // different family resets hostfake consecutive counter
        tracker.record("hostfake", true);
        assert!(tracker.blocked != Some("hostfake")); // only 1 consecutive for hostfake
    }

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
            true,
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
            reorder_tcp_candidates_for_failure(&candidates, Some(FailureClass::TlsAlert), true)
                .into_iter()
                .skip(1)
                .collect(),
            7,
        );

        let ordered = ordered_follow_up_tcp_candidates(
            &candidates,
            Some(FailureClass::TlsAlert),
            &[baseline_https_result("tls_ok", "ech_config_available")],
            7,
            true,
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

    #[test]
    fn test_audit_assessment_high_when_baseline_tied_high_coverage() {
        let tcp_candidates = vec![
            strategy_candidate_summary("baseline_current", "baseline", 90, 100, 4, 5, false, "success"),
            strategy_candidate_summary("tcp_split", "split", 90, 100, 4, 5, false, "success"),
            strategy_candidate_summary("tcp_hostfake", "hostfake", 90, 100, 4, 5, false, "success"),
        ];
        let quic_candidates = vec![
            strategy_candidate_summary("baseline_current", "baseline", 80, 100, 2, 2, false, "success"),
            strategy_candidate_summary("quic_burst", "quic_burst", 80, 100, 2, 2, false, "success"),
        ];
        let rec = StrategyProbeRecommendation {
            tcp_candidate_id: "baseline_current".to_string(),
            tcp_candidate_label: "Current strategy".to_string(),
            quic_candidate_id: "baseline_current".to_string(),
            quic_candidate_label: "Current QUIC strategy".to_string(),
            rationale: "all tied".to_string(),
            recommended_proxy_config_json: String::new(),
        };
        let assessment = resolve_strategy_probe_audit_assessment(
            STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
            &tcp_candidates,
            &quic_candidates,
            &rec,
            3,
            2,
            false,
        )
        .expect("should produce assessment");

        assert_eq!(assessment.confidence.level, StrategyProbeAuditConfidenceLevel::High);
        assert_eq!(assessment.confidence.score, 100);
        assert!(assessment.confidence.rationale.contains("no evasion needed"));
        assert!(assessment.confidence.warnings.is_empty());
    }

    #[test]
    fn test_audit_assessment_low_when_baseline_tied_low_coverage() {
        let tcp_candidates = vec![
            strategy_candidate_summary("baseline_current", "baseline", 20, 100, 1, 5, false, "partial"),
            strategy_candidate_summary("tcp_split", "split", 20, 100, 1, 5, false, "partial"),
        ];
        let quic_candidates = vec![
            strategy_candidate_summary("baseline_current", "baseline", 20, 100, 0, 2, false, "failed"),
            strategy_candidate_summary("quic_burst", "quic_burst", 20, 100, 0, 2, false, "failed"),
        ];
        let rec = StrategyProbeRecommendation {
            tcp_candidate_id: "baseline_current".to_string(),
            tcp_candidate_label: "Current strategy".to_string(),
            quic_candidate_id: "baseline_current".to_string(),
            quic_candidate_label: "Current QUIC strategy".to_string(),
            rationale: "all tied".to_string(),
            recommended_proxy_config_json: String::new(),
        };
        let assessment = resolve_strategy_probe_audit_assessment(
            STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
            &tcp_candidates,
            &quic_candidates,
            &rec,
            2,
            2,
            false,
        )
        .expect("should produce assessment");

        assert_eq!(assessment.confidence.level, StrategyProbeAuditConfidenceLevel::Low);
        assert!(assessment.confidence.warnings.iter().any(|w| w.contains("identical results")));
    }
}
