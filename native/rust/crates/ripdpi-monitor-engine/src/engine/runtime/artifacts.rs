use crate::connectivity::summarize_probe_event;
use crate::observations::{observation_for_probe, observations_for_results};
use crate::types::{NativeSessionEvent, ProbeObservation, ProbeResult};
use crate::util::{event_level_for_outcome, now_ms};

pub(in crate::engine) struct RunnerArtifacts {
    pub(in crate::engine) probe_results: Vec<ProbeResult>,
    pub(in crate::engine) observations: Vec<ProbeObservation>,
    pub(in crate::engine) events: Vec<NativeSessionEvent>,
}

impl RunnerArtifacts {
    pub(in crate::engine) fn from_probe(
        probe: ProbeResult,
        source: &str,
        path_mode: &crate::types::ScanPathMode,
    ) -> Self {
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

    pub(in crate::engine) fn from_results(
        results: Vec<ProbeResult>,
        source: &str,
        level: &str,
        message: String,
    ) -> Self {
        Self {
            observations: observations_for_results(&results),
            probe_results: results,
            events: vec![diagnostics_event(source, level, message)],
        }
    }

    pub(in crate::engine) fn empty() -> Self {
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
