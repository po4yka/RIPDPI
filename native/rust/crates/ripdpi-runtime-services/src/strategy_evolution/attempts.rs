use super::*;

impl StrategyEvolutionResolver {
    pub(super) fn hints_for_attempt(
        &mut self,
        context: LearningContext,
        existing: Option<EvolutionAttempt>,
        host: Option<&str>,
    ) -> Option<(AdaptivePlannerHints, EvolutionAttempt)> {
        self.pending.retain(|_, experiment| !self.evolver.experiment_expired(experiment));
        // A held identity can resume only its own live context. Never replace it
        // while a response from its earlier send can still arrive.
        if let Some(attempt) = existing {
            let experiment = self.pending.get(&attempt)?;
            if !same_observation_context(experiment.context(), context) {
                self.pending.remove(&attempt);
                return None;
            }
            let hints = experiment.hints();
            let owned_context = experiment.context().clone();
            let bucket = owned_context.target_bucket;
            self.evolver.set_learning_context(owned_context);
            self.apply_latest_probe_results(bucket, host);
            return Some((hints, attempt));
        }
        let bucket = context.target_bucket;
        self.evolver.set_learning_context(context.clone());
        self.apply_latest_probe_results(bucket, host);
        let generation = self.generation?;
        let sequence = self.next_attempt;
        self.next_attempt = sequence.checked_add(1)?;
        let experiment = self.evolver.start_experiment(context)?;
        let hints = experiment.hints();
        let attempt = EvolutionAttempt::new(generation, sequence);
        if self.pending.len() >= MAX_PENDING_EVOLUTION_ATTEMPTS {
            self.pending.pop_first();
        }
        self.pending.insert(attempt, experiment);
        Some((hints, attempt))
    }

    pub(crate) fn record_success(&mut self, attempt: EvolutionAttempt, latency_ms: u64) {
        if let Some(experiment) = self.pending.remove(&attempt) {
            self.evolver.record_experiment_success(experiment, latency_ms);
        }
    }

    pub(crate) fn record_failure(&mut self, attempt: EvolutionAttempt, class: FailureClass) {
        if let Some(experiment) = self.pending.remove(&attempt) {
            self.evolver.record_experiment_failure(experiment, class);
        }
    }

    pub(crate) fn discard(&mut self, attempt: EvolutionAttempt) {
        self.pending.remove(&attempt);
    }
}

// The first send owns protocol classification. Continuation bytes can lose their
// TLS/QUIC header, but cannot change the network, capabilities, or transport.
fn same_observation_context(owned: &LearningContext, mut current: LearningContext) -> bool {
    if (owned.transport == LearningTransportKind::Tcp) != (current.transport == LearningTransportKind::Tcp) {
        return false;
    }
    current.target_bucket = owned.target_bucket;
    current.alpn_class = owned.alpn_class;
    current.ech_capable = owned.ech_capable;
    current.transport = owned.transport;
    current == *owned
}
