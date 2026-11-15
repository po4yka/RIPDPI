use super::ExecutionPlan;

impl ExecutionPlan {
    pub(in crate::engine) fn strategy_candidate_steps(&self) -> (usize, usize) {
        let Some(strategy) = &self.strategy else {
            return (0, 0);
        };
        let cap = strategy.max_candidates.unwrap_or(usize::MAX);
        (
            if self.request.domain_targets.is_empty() { 0 } else { strategy.suite.tcp_candidates.len().min(cap) },
            if self.request.quic_targets.is_empty() { 0 } else { strategy.suite.quic_candidates.len().min(cap) },
        )
    }
}
