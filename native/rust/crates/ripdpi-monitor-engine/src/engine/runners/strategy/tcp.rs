use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{
    candidate_pause_ms, probe_fake_ttl_capability, probe_ip_fragmentation_capabilities, probe_tcp_fast_open_capability,
    CandidateEligibility, StrategyCandidateSpec,
};
use crate::classification::{
    classified_failure_probe_result, classify_strategy_probe_baseline_observations, next_candidate_index,
};
use crate::execution::{
    eliminated_candidate_summary, execute_tcp_candidate, skipped_candidate_summary, CandidateExecution,
    CandidateRuntimeLauncher,
};
use crate::observations::observations_for_results;
use crate::types::StrategyProbeProgressLane;

use super::super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};
use super::support::{
    annotate_emitter_execution, baseline_supports_ech_candidates, capability_available, capability_suffix,
    compute_rst_adaptive_timeout, missing_capability_rationale, ordered_follow_up_tcp_candidates,
    record_not_applicable_tcp_candidate, strategy_probe_live_progress_with_targets, stratified_pilot_targets,
    FamilyFailureTracker, ECH_ELIGIBILITY_RATIONALE, FAKE_TTL_ELIGIBILITY_RATIONALE,
    TCP_FAST_OPEN_ELIGIBILITY_RATIONALE,
};

pub(in crate::engine::runners) struct StrategyTcpRunner {
    candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
}

impl StrategyTcpRunner {
    pub(in crate::engine::runners) fn new(candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>) -> Self {
        Self { candidate_runtime_launcher }
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
            self.candidate_runtime_launcher.as_ref(),
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
        let ipfrag_caps = probe_ip_fragmentation_capabilities();
        tracing::info!(
            fake_ttl_available,
            tcp_fast_open_available,
            tcp_repair = ipfrag_caps.tcp_repair,
            raw_ipv4 = ipfrag_caps.raw_ipv4,
            raw_ipv6 = ipfrag_caps.raw_ipv6,
            "strategy probe: capabilities probed"
        );
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
        // Stratified pilot evaluation: test each candidate against a small,
        // representative target slice before the full-matrix run.
        // This avoids permanently pruning candidates based on a single domain.
        // Candidates are tested in parallel batches of up to 3 to reduce wall-clock time.
        // Skipped when max_candidates is set (quick scan) since the list is already small.
        if strategy_plan.max_candidates.is_none() && domain_targets.len() > 1 {
            let qualifier_targets = stratified_pilot_targets(&domain_targets);
            let pilot_target_count = qualifier_targets.len();
            let qualifier_targets = qualifier_targets.as_slice();
            let mut qualified_specs: Vec<StrategyCandidateSpec> = Vec::with_capacity(pending_tcp_specs.len());
            let mut eliminated_count = 0usize;

            // Partition into pass-through and testable candidates.
            let mut testable_specs: Vec<StrategyCandidateSpec> = Vec::new();
            for spec in pending_tcp_specs.drain(..) {
                let pass_through = spec.id == "baseline_current"
                    || (spec.eligibility == CandidateEligibility::RequiresEchCapability && !baseline_ech_capable)
                    || (spec.requires_fake_ttl && !fake_ttl_available)
                    || spec
                        .requires_capabilities
                        .iter()
                        .any(|&capability| !capability_available(capability, fake_ttl_available, ipfrag_caps))
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
                                    self.candidate_runtime_launcher.as_ref(),
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
                    pilot_targets = pilot_target_count,
                    qualified = qualified_count,
                    eliminated = eliminated_count,
                    "strategy probe: stratified pilot evaluation complete"
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
                    } else if let Some(capability) = spec
                        .requires_capabilities
                        .iter()
                        .copied()
                        .find(|&capability| !capability_available(capability, fake_ttl_available, ipfrag_caps))
                    {
                        Some((missing_capability_rationale(&spec), capability_suffix(capability)))
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
                                self.candidate_runtime_launcher.as_ref(),
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
                let mut summary = execution.summary;
                annotate_emitter_execution(&mut summary, &spec, fake_ttl_available, ipfrag_caps);
                if summary.family == "hostfake" && summary.succeeded_targets == summary.total_targets {
                    hostfake_family_succeeded = true;
                }
                let failed = summary.outcome == "failed";
                runtime.record_step(
                    plan,
                    self.phase(),
                    format!("Tested {}", spec.label),
                    Some(spec.label.to_string()),
                    Some(summary.outcome.clone()),
                    Some(strategy_probe_live_progress_with_targets(
                        StrategyProbeProgressLane::Tcp,
                        candidate_index,
                        tcp_candidate_total,
                        spec.id,
                        spec.label,
                        summary.succeeded_targets,
                        summary.total_targets,
                    )),
                    RunnerArtifacts::from_results(
                        execution.results.clone(),
                        "strategy_probe",
                        if failed { "warn" } else { "info" },
                        format!("Testing TCP candidate {}", spec.label),
                    ),
                );
                runtime.strategy.tcp_candidates.push(summary);
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
        let skipped_count = planned_count.saturating_sub(executed_count);
        tracing::info!(
            executed = executed_count,
            planned = planned_count,
            skipped = skipped_count,
            "strategy probe: TCP suite completed"
        );
        RunnerOutcome::Completed
    }
}
