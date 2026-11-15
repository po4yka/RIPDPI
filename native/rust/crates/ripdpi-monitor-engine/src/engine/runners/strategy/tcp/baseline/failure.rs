use crate::classification::{classified_failure_probe_result, classify_strategy_probe_baseline_observations};
use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime};
use crate::observations::observations_for_results;
use crate::types::ProbeResult;

pub(super) fn classify_baseline(
    plan: &ExecutionPlan,
    runtime: &mut ExecutionRuntime,
    phase: &str,
    baseline_label: &str,
    mut baseline_results: Vec<ProbeResult>,
) -> Vec<ProbeResult> {
    let baseline_observations = observations_for_results(&baseline_results);
    runtime.strategy.baseline_failure = classify_strategy_probe_baseline_observations(&baseline_observations);
    if let Some(failure) = &runtime.strategy.baseline_failure {
        baseline_results.push(classified_failure_probe_result(baseline_label, failure));
        // Publish the DPI failure class so the UI can show it as a badge.
        runtime.publish_progress(
            plan,
            phase,
            runtime.completed_steps,
            format!("DPI: {}", failure.class.as_str()),
            Some("baseline_failure_class".to_string()),
            Some(failure.class.as_str().to_string()),
            None,
        );
    }
    baseline_results
}
