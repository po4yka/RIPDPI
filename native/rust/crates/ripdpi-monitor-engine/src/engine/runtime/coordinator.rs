use std::collections::{BTreeMap, HashSet};
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use super::artifacts::RunnerArtifacts;
use super::parallel;
use super::plan::ExecutionPlan;
use super::recording::{CollectedStageOutcome, CollectedStep, record_steps};
use super::stage::{ExecutionStageId, ExecutionStageRunner, RunnerOutcome};
use super::state::ExecutionRuntime;
use crate::types::ProbeResult;

/// Outcome of joining a single parallel runner thread.
///
/// A panic in one runner must NOT abort the whole scan: it is converted into a
/// recorded failure for that stage so sibling runners still complete and the
/// failure is diagnosable in the scan report.
enum JoinedStageOutcome {
    /// The runner returned normally (completed or cancelled).
    Collected(CollectedStageOutcome),
    /// The runner thread panicked; carries a human-readable payload summary.
    Panicked(String),
}

/// Downcast a panic payload to a readable string for logging / report surfacing.
///
/// The payload is the value passed to `panic!`; the common shapes are
/// `&'static str` and `String`. Anything else is reported as a generic marker
/// so the panic still surfaces without exposing arbitrary `Debug` output.
fn panic_payload_message(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic payload".to_string()
    }
}

/// Build the recorded steps for a runner whose thread panicked.
///
/// The synthetic probe result keeps the panic diagnosable in the final report
/// while letting the scan continue with the surviving runners' results.
fn panicked_stage_steps(stage: &ExecutionStageId, message: &str) -> Vec<CollectedStep> {
    let probe_type = format!("{stage:?}_runner");
    let probe = ProbeResult {
        probe_type: probe_type.clone(),
        target: format!("{stage:?} stage runner"),
        outcome: "runner_panicked".to_string(),
        details: Vec::new(),
    };
    let summary = format!("{stage:?} runner thread panicked: {message}");
    let artifacts = RunnerArtifacts::from_results(vec![probe], &probe_type, "error", summary.clone());
    vec![CollectedStep {
        phase: "parallel_connectivity",
        message: summary,
        latest_probe_target: Some(format!("{stage:?} stage runner")),
        latest_probe_outcome: Some("runner_panicked".to_string()),
        artifacts,
    }]
}

pub(in crate::engine) struct ExecutionCoordinator {
    runners: BTreeMap<ExecutionStageId, Box<dyn ExecutionStageRunner + Send + Sync>>,
}

impl ExecutionCoordinator {
    pub(in crate::engine) fn new(runners: Vec<Box<dyn ExecutionStageRunner + Send + Sync>>) -> Self {
        let runners = runners.into_iter().map(|runner| (runner.id(), runner)).collect();
        Self { runners }
    }

    pub(in crate::engine) fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.stage_order
            .iter()
            .filter_map(|stage| self.runners.get(stage))
            .map(|runner| runner.total_steps(plan))
            .sum::<usize>()
            .max(1)
    }

    pub(in crate::engine) fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let mut parallel_done = HashSet::new();

        for stage in &plan.stage_order {
            if parallel_done.contains(stage) {
                continue;
            }
            if parallel::is_connectivity_parallel_plan_stage(plan, stage) {
                let parallel_runners = parallel::runnable_connectivity_parallel_stages(plan, &self.runners);

                if parallel_runners.len() > 1 {
                    if runtime.is_cancelled() || runtime.is_past_deadline() {
                        return RunnerOutcome::Cancelled;
                    }
                    let parallel_target_count = parallel::total_steps(&parallel_runners, &self.runners, plan);
                    runtime.publish_progress(
                        plan,
                        "parallel_connectivity",
                        runtime.completed_steps,
                        format!("Running DNS, TCP, and QUIC probes ({parallel_target_count} targets)"),
                        None,
                        None,
                        None,
                    );

                    let thread_results = std::thread::scope(|s| {
                        let mut handles = Vec::with_capacity(parallel_runners.len());
                        for parallel_stage in &parallel_runners {
                            let runner = self.runners.get(parallel_stage).expect("runner present");
                            let cancel = runtime.cancel_token();
                            handles.push(s.spawn(move || runner.run_collecting(plan, cancel, tls_verifier)));
                        }
                        // Recover from a panicking runner instead of propagating
                        // it: a single flaky probe must not abort the whole scan.
                        // `join()` returns `Err` only when the thread panicked.
                        handles
                            .into_iter()
                            .map(|handle| match handle.join() {
                                Ok(outcome) => JoinedStageOutcome::Collected(outcome),
                                Err(payload) => JoinedStageOutcome::Panicked(panic_payload_message(&*payload)),
                            })
                            .collect::<Vec<_>>()
                    });

                    let mut cancelled = false;
                    for (parallel_stage, joined) in parallel_runners.iter().zip(thread_results) {
                        parallel_done.insert(*parallel_stage);
                        let steps = match joined {
                            JoinedStageOutcome::Collected(CollectedStageOutcome::Completed(steps)) => steps,
                            JoinedStageOutcome::Collected(CollectedStageOutcome::Cancelled(steps)) => {
                                cancelled = true;
                                steps
                            }
                            JoinedStageOutcome::Panicked(message) => {
                                // Surface the panic for diagnosability but keep the
                                // scan alive; the failed runner is recorded as a
                                // failed step rather than cancelling siblings.
                                log::error!("diagnostics parallel runner {parallel_stage:?} panicked: {message}");
                                panicked_stage_steps(parallel_stage, &message)
                            }
                        };
                        record_steps(plan, runtime, steps);
                    }
                    if cancelled {
                        return RunnerOutcome::Cancelled;
                    }
                    continue;
                }
            }
            let Some(runner) = self.runners.get(stage) else {
                continue;
            };
            if runtime.is_cancelled() || runtime.is_past_deadline() {
                return RunnerOutcome::Cancelled;
            }
            if runner.total_steps(plan) == 0 {
                continue;
            }
            match runner.run(plan, runtime, tls_verifier) {
                RunnerOutcome::Completed => {}
                RunnerOutcome::Cancelled => return RunnerOutcome::Cancelled,
                RunnerOutcome::Finished => return RunnerOutcome::Finished,
            }
        }
        RunnerOutcome::Completed
    }
}
