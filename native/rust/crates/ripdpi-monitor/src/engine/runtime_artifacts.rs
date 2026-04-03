use crate::connectivity::summarize_probe_event;
use crate::observations::{observation_for_probe, observations_for_results};
use crate::types::NativeSessionEvent;
use crate::util::{event_level_for_outcome, now_ms};

pub(super) struct RunnerArtifacts {
    pub(super) probe_results: Vec<ProbeResult>,
    pub(super) observations: Vec<ProbeObservation>,
    pub(super) events: Vec<NativeSessionEvent>,
}

impl RunnerArtifacts {
    pub(super) fn from_probe(probe: ProbeResult, source: &str, path_mode: &crate::types::ScanPathMode) -> Self {
        let probe_type = probe.probe_type.clone();
        let outcome = probe.outcome.clone();
        let message = summarize_probe_event(&probe);
        let level = event_level_for_outcome(&probe_type, path_mode, &outcome).to_string();
        Self {
            observations: observation_for_probe(&probe).into_iter().collect(),
            probe_results: vec![probe],
            events: vec![diagnostics_event(source, &level, message)],
        }
    }

    pub(super) fn from_results(results: Vec<ProbeResult>, source: &str, level: &str, message: String) -> Self {
        Self {
            observations: observations_for_results(&results),
            probe_results: results,
            events: vec![diagnostics_event(source, level, message)],
        }
    }

    pub(super) fn empty() -> Self {
        Self { probe_results: Vec::new(), observations: Vec::new(), events: Vec::new() }
    }
}

fn diagnostics_event(source: &str, level: &str, message: String) -> NativeSessionEvent {
    NativeSessionEvent {
        source: source.to_string(),
        level: level.to_string(),
        message,
        created_at: now_ms(),
        runtime_id: None,
        mode: None,
        policy_signature: None,
        fingerprint_hash: None,
        subsystem: Some("diagnostics".to_string()),
    }
}

/// A single recorded step collected outside of `ExecutionRuntime`, used by the
/// parallel runner path to accumulate results without shared mutable state.
pub(super) struct CollectedStep {
    pub(super) phase: &'static str,
    pub(super) message: String,
    pub(super) latest_probe_target: Option<String>,
    pub(super) latest_probe_outcome: Option<String>,
    pub(super) artifacts: RunnerArtifacts,
}
