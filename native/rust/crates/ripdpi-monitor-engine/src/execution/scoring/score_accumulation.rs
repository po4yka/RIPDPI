use super::attempts::ProbeAttemptSample;
use super::score_state::{CandidateScore, ProbeSample};

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

        self.attempt_samples.push(ProbeAttemptSample {
            target: sample.domain.clone().unwrap_or_else(|| sample.result.target.clone()),
            is_control: sample.is_control,
            protocol: sample.attempt.protocol,
            started_at_ms: sample.attempt.started_at_ms,
            duration_ms: sample.attempt.duration_ms,
            retry_count: sample.attempt.retry_count,
            outcome: sample.result.outcome.clone(),
            reason: sample.attempt.reason,
        });
        self.results.push(sample.result);
        self.total_targets += 1;
        self.total_weight += sample.weight;
        self.quality_score += sample.quality * sample.weight;

        if sample.success {
            self.succeeded_targets += 1;
            self.weighted_success_score += sample.weight;
            self.latency_sum_ms += sample.attempt.duration_ms;
            self.latency_count += 1;
        }
    }
}
