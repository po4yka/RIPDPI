use crate::candidates::StrategyCandidateSpec;
use crate::types::{
    STRATEGY_PROBE_ATTEMPT_VERSION, StrategyProbeAttempt, StrategyProbeAttemptRound, StrategyProbeAttemptStatus,
    StrategyProbeProgressLane,
};

#[derive(Debug)]
pub struct ProbeAttemptMetadata {
    pub started_at_ms: u64,
    pub duration_ms: u64,
    pub retry_count: usize,
    pub protocol: String,
    pub reason: Option<String>,
}

impl ProbeAttemptMetadata {
    pub fn new(
        started_at_ms: u64,
        duration_ms: u64,
        retry_count: usize,
        protocol: &str,
        reason: Option<String>,
    ) -> Self {
        Self { started_at_ms, duration_ms, retry_count, protocol: protocol.to_string(), reason }
    }
}

#[derive(Debug)]
pub struct ProbeAttemptSample {
    pub target: String,
    pub is_control: bool,
    pub protocol: String,
    pub started_at_ms: u64,
    pub duration_ms: u64,
    pub retry_count: usize,
    pub outcome: String,
    pub reason: Option<String>,
}

pub(super) fn build_strategy_probe_attempts(
    spec: &StrategyCandidateSpec,
    samples: Vec<ProbeAttemptSample>,
) -> Vec<StrategyProbeAttempt> {
    let lane = if samples.iter().any(|attempt| attempt.protocol == "QUIC") {
        StrategyProbeProgressLane::Quic
    } else {
        StrategyProbeProgressLane::Tcp
    };
    samples
        .into_iter()
        .map(|attempt| {
            let timed_out = attempt.reason.as_ref().is_some_and(|reason| {
                let normalized = reason.to_ascii_lowercase();
                normalized.contains("timed out") || normalized.contains("timeout")
            });
            StrategyProbeAttempt {
                attempt_version: STRATEGY_PROBE_ATTEMPT_VERSION.to_string(),
                sequence: 0,
                candidate_index: 0,
                candidate_id: spec.id.to_string(),
                candidate_label: spec.label.to_string(),
                candidate_family: spec.family.to_string(),
                lane,
                target: attempt.target,
                target_index: 0,
                is_control: attempt.is_control,
                protocol: attempt.protocol,
                round: StrategyProbeAttemptRound::FullMatrix,
                status: if timed_out {
                    StrategyProbeAttemptStatus::TimedOut
                } else {
                    StrategyProbeAttemptStatus::Executed
                },
                started_at_ms: Some(attempt.started_at_ms),
                duration_ms: Some(attempt.duration_ms),
                retry_count: attempt.retry_count,
                outcome: Some(attempt.outcome),
                reason: attempt.reason,
            }
        })
        .collect()
}
