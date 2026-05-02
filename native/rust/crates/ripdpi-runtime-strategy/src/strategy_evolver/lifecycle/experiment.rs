use super::initialization::clear_pending_experiment;
use crate::strategy_evolver::StrategyEvolver;

pub(in crate::strategy_evolver) fn drop_expired_experiment(evolver: &mut StrategyEvolver, now_ms: u64) {
    if evolver.experiment_ttl_ms == 0 {
        return;
    }
    let Some(started_ms) = evolver.current_experiment_started_ms else {
        return;
    };
    let elapsed_ms = now_ms.saturating_sub(started_ms);
    if elapsed_ms < evolver.experiment_ttl_ms {
        return;
    }

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
