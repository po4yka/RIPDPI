use crate::types::StrategyProbeDomainOutcome;

use super::score_state::CandidateScore;

impl CandidateScore {
    /// Build per-domain outcome list. A domain is considered successful if all
    /// of its probes (HTTP + HTTPS) passed.
    pub fn domain_outcomes(&self) -> Vec<StrategyProbeDomainOutcome> {
        self.domain_totals
            .iter()
            .map(|(domain, &total)| {
                let successes = self.domain_successes.get(domain).copied().unwrap_or(0);
                StrategyProbeDomainOutcome {
                    domain: domain.clone(),
                    succeeded: successes == total && total > 0,
                    is_control: self.domain_controls.get(domain).copied().unwrap_or(false),
                }
            })
            .collect()
    }
}
