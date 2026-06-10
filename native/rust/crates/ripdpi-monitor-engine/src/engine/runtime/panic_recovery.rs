use super::artifacts::RunnerArtifacts;
use super::recording::{CollectedStageOutcome, CollectedStep};
use super::stage::ExecutionStageId;
use crate::types::ProbeResult;

/// Outcome of joining a single parallel runner thread.
///
/// A panic in one runner must NOT abort the whole scan: it is converted into a
/// recorded failure for that stage so sibling runners still complete and the
/// failure is diagnosable in the scan report.
pub(super) enum JoinedStageOutcome {
    /// The runner returned normally (completed or cancelled).
    Collected(CollectedStageOutcome),
    /// The runner thread panicked; carries a human-readable payload summary.
    Panicked(String),
}

/// Classify a `thread::JoinHandle::join()` result into a `JoinedStageOutcome`.
///
/// `join()` returns `Err` only when the thread panicked; the payload is
/// downcasted to a readable string so the failure is diagnosable.
pub(super) fn classify(result: std::thread::Result<CollectedStageOutcome>) -> JoinedStageOutcome {
    match result {
        Ok(outcome) => JoinedStageOutcome::Collected(outcome),
        Err(payload) => JoinedStageOutcome::Panicked(panic_payload_message(&*payload)),
    }
}

/// Log the panic and build the synthetic recorded steps for a panicked runner.
///
/// The synthetic probe result keeps the panic diagnosable in the final report
/// while letting the scan continue with the surviving runners' results.
pub(super) fn handle_panicked_runner(stage: &ExecutionStageId, message: &str) -> Vec<CollectedStep> {
    log::error!("diagnostics parallel runner {stage:?} panicked: {message}");
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

/// Downcast a panic payload to a readable string for logging / report surfacing.
///
/// The payload is the value passed to `panic!`; the common shapes are
/// `&'static str` and `String`. Anything else is reported as a generic marker
/// so the panic still surfaces without exposing arbitrary `Debug` output.
pub(crate) fn panic_payload_message(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic payload".to_string()
    }
}
