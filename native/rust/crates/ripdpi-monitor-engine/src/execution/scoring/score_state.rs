use std::collections::BTreeMap;

use crate::types::ProbeResult;

use super::attempts::{ProbeAttemptMetadata, ProbeAttemptSample};

#[derive(Default)]
pub struct CandidateScore {
    pub results: Vec<ProbeResult>,
    pub attempt_samples: Vec<ProbeAttemptSample>,
    pub succeeded_targets: usize,
    pub total_targets: usize,
    pub weighted_success_score: usize,
    pub total_weight: usize,
    pub quality_score: usize,
    pub latency_sum_ms: u64,
    pub latency_count: usize,
    /// Per-domain success tracking for autolearn seeding.
    /// Key: normalized domain, Value: number of successful probes for that domain.
    pub domain_successes: BTreeMap<String, usize>,
    /// Per-domain total probe count for autolearn seeding.
    pub domain_totals: BTreeMap<String, usize>,
    /// Per-domain control classification copied from the exact scan target.
    pub domain_controls: BTreeMap<String, bool>,
}

impl CandidateScore {
    pub fn average_latency_ms(&self) -> Option<u64> {
        (self.latency_count > 0).then(|| self.latency_sum_ms / self.latency_count as u64)
    }

    pub fn is_full_success(&self) -> bool {
        self.total_targets > 0 && self.succeeded_targets == self.total_targets
    }
}

pub struct ProbeSample {
    pub result: ProbeResult,
    pub success: bool,
    pub weight: usize,
    pub quality: usize,
    pub attempt: ProbeAttemptMetadata,
    /// The domain this sample was probed against, for per-domain outcome tracking.
    pub domain: Option<String>,
    /// Whether the exact planned domain target is a neutral control.
    pub is_control: bool,
}
