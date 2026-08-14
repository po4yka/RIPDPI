use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::thread;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::{CandidateExecution, StrategyLaneExecutor, eliminated_candidate_summary};
use crate::types::DomainTarget;

use super::super::super::super::runtime::{ExecutionPlan, ExecutionRuntime, RunnerArtifacts};
use super::super::support::stratified_pilot_targets;
use super::StrategyTcpRunner;
use super::capability_gating::{TcpCapabilities, candidate_passes_pilot_without_execution};

pub(super) fn qualify_pilot_candidates(
    runner: &StrategyTcpRunner,
    plan: &ExecutionPlan,
    runtime: &mut ExecutionRuntime,
    mut pending_tcp_specs: Vec<StrategyCandidateSpec>,
    domain_targets: &[DomainTarget],
    capabilities: TcpCapabilities,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Vec<StrategyCandidateSpec> {
    let strategy_plan = plan.strategy.as_ref().expect("strategy plan");
    // Stratified pilot evaluation: test each candidate against a small,
    // representative target slice before the full-matrix run.
    // This avoids permanently pruning candidates based on a single domain.
    // Candidates are tested in parallel batches of up to 3 to reduce wall-clock time.
    // Skipped when max_candidates is set (quick scan) since the list is already small.
    if strategy_plan.max_candidates.is_some() || domain_targets.len() <= 1 {
        return pending_tcp_specs;
    }

    let qualifier_targets = stratified_pilot_targets(domain_targets);
    let pilot_target_count = qualifier_targets.len();
    let qualifier_targets = qualifier_targets.as_slice();
    let mut qualified_specs = Vec::with_capacity(pending_tcp_specs.len());
    let mut eliminated_count = 0usize;

    // Partition into pass-through and testable candidates.
    let mut testable_specs = Vec::new();
    for spec in pending_tcp_specs.drain(..) {
        if candidate_passes_pilot_without_execution(&spec, capabilities) {
            qualified_specs.push(spec);
        } else {
            testable_specs.push(spec);
        }
    }

    // Test in parallel batches of up to 3, grouped by family so each
    // family gets at least one representative tested early.
    for batch in testable_specs.chunks(3) {
        if runtime.is_cancelled() || runtime.is_past_deadline() {
            // Don't eliminate untested candidates on cancellation/deadline.
            for spec in batch {
                qualified_specs.push(spec.clone());
            }
            continue;
        }
        let cancel_token = runtime.cancel_token();
        let deadline = ripdpi_diagnostics_contracts::util::active_scan_io_deadline();
        let batch_results: Vec<(StrategyCandidateSpec, Option<CandidateExecution>)> = thread::scope(|s| {
            let handles: Vec<_> = batch
                .iter()
                .map(|spec| {
                    let spec_clone = spec.clone();
                    s.spawn(move || {
                        ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
                            if cancel_token.load(Ordering::Acquire) {
                                return (spec_clone, None);
                            }
                            let execution = runner.lane_executor.execute_tcp_candidate(
                                &spec_clone,
                                qualifier_targets,
                                strategy_plan.runtime_context.as_ref(),
                                strategy_plan.probe_seed,
                                tls_verifier,
                                plan.request.diagnostic_tls_keylog_path.as_deref(),
                                cancel_token,
                            );
                            (spec_clone, Some(execution))
                        })
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
            if execution.cancelled || execution.summary.succeeded_targets > 0 {
                qualified_specs.push(spec);
            } else {
                let summary = eliminated_candidate_summary(
                    &spec,
                    execution.summary.succeeded_targets,
                    execution.summary.total_targets,
                    3,
                );
                let label = summary.label.clone();
                let outcome = summary.outcome.clone();
                runtime.strategy.tcp_candidates.push(summary);
                // `total_steps` counts every suite candidate, so a candidate eliminated here still
                // owes the plan one step. Without this the run finishes short and the final
                // progress publish snaps the bar from partway to 100%.
                //
                // No live progress payload: the pilot runs before the main loop, and feeding it a
                // pilot-local index would make the "n/N" candidate counter count up and then
                // restart. Empty artifacts keep this a counter-only step, so an eliminated
                // candidate contributes no probe results or session events beyond its summary.
                runtime.record_step(
                    plan,
                    "tcp",
                    format!("Eliminated {label}"),
                    Some(label),
                    Some(outcome),
                    None,
                    RunnerArtifacts::empty(),
                );
                eliminated_count += 1;
            }
        }
    }

    // Safety: if all candidates were eliminated (shouldn't happen since
    // baseline always qualifies), skip elimination to avoid empty run.
    if qualified_specs.is_empty() {
        tracing::warn!("strategy probe: Round 1 qualifier eliminated all candidates — skipping elimination");
        return Vec::new();
    }

    let qualified_count = qualified_specs.len();
    tracing::info!(
        pilot_targets = pilot_target_count,
        qualified = qualified_count,
        eliminated = eliminated_count,
        "strategy probe: stratified pilot evaluation complete"
    );
    qualified_specs
}
