use std::sync::Arc;
use std::thread;
use std::time::Duration;

use ripdpi_failure_classifier::FailureClass;
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{build_quic_candidates_for_suite, build_strategy_probe_summary, candidate_pause_ms};
use crate::classification::{
    classified_failure_probe_result, classify_strategy_probe_baseline_observations, filter_quic_candidates_for_failure,
    interleave_candidate_families, next_candidate_index, reorder_tcp_candidates_for_failure,
};
use crate::connectivity::set_progress;
use crate::execution::{
    execute_quic_candidate, execute_tcp_candidate, skipped_candidate_summary, winning_candidate_index,
};
use crate::observations::observations_for_results;
use crate::strategy::detect_strategy_probe_dns_tampering;
use crate::types::{ScanProgress, StrategyProbeRecommendation, StrategyProbeReport};
use crate::util::stable_probe_hash;

use super::super::report::build_report;
use super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};

pub(super) struct StrategyDnsBaselineRunner;
pub(super) struct StrategyTcpRunner;
pub(super) struct StrategyQuicRunner;
pub(super) struct StrategyRecommendationRunner;

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
        let strategy_probe_report = StrategyProbeReport {
            suite_id: strategy_plan.suite_id.clone(),
            tcp_candidates,
            quic_candidates,
            recommendation,
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
        let baseline_spec = tcp_specs.first().expect("tcp candidate");
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
        runtime.record_step(
            plan,
            self.phase(),
            format!("Tested {}", baseline_spec.label),
            Some(baseline_spec.label.to_string()),
            Some(baseline_execution.summary.outcome.clone()),
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

        let ordered_tcp_specs = interleave_candidate_families(
            reorder_tcp_candidates_for_failure(
                tcp_specs,
                runtime.strategy.baseline_failure.as_ref().map(|value| value.class),
            )
            .into_iter()
            .skip(1)
            .collect(),
            strategy_plan.probe_seed,
        );
        let mut pending_tcp_specs = ordered_tcp_specs;
        let mut blocked_tcp_family = None::<&str>;
        let mut last_failed_tcp_family = None::<&str>;
        let mut consecutive_tcp_family_failures = 0usize;
        while !pending_tcp_specs.is_empty() {
            let spec = pending_tcp_specs.remove(next_candidate_index(&pending_tcp_specs, blocked_tcp_family));
            if runtime.is_cancelled() {
                return RunnerOutcome::Cancelled;
            }
            if strategy_plan.suite.short_circuit_hostfake && spec.family == "hostfake" && hostfake_family_succeeded {
                runtime.strategy.tcp_candidates.push(skipped_candidate_summary(
                    &spec,
                    plan.request.domain_targets.len() * 2,
                    6,
                    "Earlier hostfake candidate already achieved full success",
                ));
                runtime.completed_steps += 1;
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
            runtime.record_step(
                plan,
                self.phase(),
                format!("Tested {}", spec.label),
                Some(spec.label.to_string()),
                Some(execution.summary.outcome.clone()),
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
        let mut pending_quic_specs =
            interleave_candidate_families(quic_specs.clone(), stable_probe_hash(strategy_plan.probe_seed, "quic"));
        let mut quic_family_succeeded = false;
        let mut blocked_quic_family = None::<&str>;
        let mut last_failed_quic_family = None::<&str>;
        let mut consecutive_quic_family_failures = 0usize;
        while !pending_quic_specs.is_empty() {
            let spec = pending_quic_specs.remove(next_candidate_index(&pending_quic_specs, blocked_quic_family));
            if runtime.is_cancelled() {
                return RunnerOutcome::Cancelled;
            }
            if strategy_plan.suite.short_circuit_quic_burst && spec.family == "quic_burst" && quic_family_succeeded {
                runtime.strategy.quic_candidates.push(skipped_candidate_summary(
                    &spec,
                    plan.request.quic_targets.len(),
                    2,
                    "Earlier QUIC burst candidate already achieved full success",
                ));
                runtime.completed_steps += 1;
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
            runtime.record_step(
                plan,
                self.phase(),
                format!("Tested {}", spec.label),
                Some(spec.label.to_string()),
                Some(execution.summary.outcome.clone()),
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
            recommended_proxy_config_json: crate::candidates::strategy_probe_config_json(&quic_winner_spec.config),
        };
        let summary = build_strategy_probe_summary(
            &strategy_plan.suite_id,
            &runtime.strategy.tcp_candidates,
            &runtime.strategy.quic_candidates,
            &recommendation,
        );
        runtime.strategy.strategy_probe_report = Some(StrategyProbeReport {
            suite_id: strategy_plan.suite_id.clone(),
            tcp_candidates: runtime.strategy.tcp_candidates.clone(),
            quic_candidates: runtime.strategy.quic_candidates.clone(),
            recommendation,
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
            },
        );
        RunnerOutcome::Completed
    }
}
