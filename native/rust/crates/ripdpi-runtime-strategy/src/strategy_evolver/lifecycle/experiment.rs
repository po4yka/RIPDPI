use super::initialization::clear_pending_experiment;
use crate::strategy_evolver::StrategyEvolver;

pub(in crate::strategy_evolver) fn expired_experiment_age_ms(evolver: &StrategyEvolver, now_ms: u64) -> Option<u64> {
    evolver.expired_experiment_age(evolver.current_experiment_started_ms?, now_ms)
}

pub(in crate::strategy_evolver) fn drop_expired_experiment(evolver: &mut StrategyEvolver, now_ms: u64) {
    let Some(elapsed_ms) = expired_experiment_age_ms(evolver, now_ms) else {
        return;
    };

    let dropped = evolver.current_experiment.take();
    evolver.current_experiment_context = None;
    evolver.current_experiment_family = None;
    evolver.current_experiment_started_ms = None;
    tracing::debug!(
        combo = ?dropped,
        elapsed_ms,
        ttl_ms = evolver.experiment_ttl_ms,
        "strategy evolution dropped experiment due to TTL expiry",
    );
}

pub(in crate::strategy_evolver) fn discard_pending_experiment(evolver: &mut StrategyEvolver) {
    clear_pending_experiment(evolver);
}
