use std::collections::BTreeMap;

use crate::types::ProbeResult;

#[derive(Default)]
pub struct CandidateScore {
    pub results: Vec<ProbeResult>,
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
    pub fn add(&mut self, sample: ProbeSample) {
        if let Some(ref domain) = sample.domain {
            *self.domain_totals.entry(domain.clone()).or_default() += 1;
            self.domain_controls
                .entry(domain.clone())
                .and_modify(|is_control| *is_control |= sample.is_control)
                .or_insert(sample.is_control);
            if sample.success {
                *self.domain_successes.entry(domain.clone()).or_default() += 1;
            }
        }

        self.results.push(sample.result);
        self.total_targets += 1;
        self.total_weight += sample.weight;
        self.quality_score += sample.quality * sample.weight;

        if sample.success {
            self.succeeded_targets += 1;
            self.weighted_success_score += sample.weight;
            self.latency_sum_ms += sample.latency_ms;
            self.latency_count += 1;
        }
    }

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
    pub latency_ms: u64,
    /// The domain this sample was probed against, for per-domain outcome tracking.
    pub domain: Option<String>,
    /// Whether the exact planned domain target is a neutral control.
    pub is_control: bool,
}
