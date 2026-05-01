use super::super::state::{flush_autolearn_updates, RuntimeState};

pub(crate) fn flush_runtime_cache_after_handover(state: &RuntimeState) {
    if let Ok(mut cache) = state.cache.write() {
        let cleared = cache.clear_connection_cache(&state.config);
        flush_autolearn_updates(state, &mut cache);
        if cleared > 0 {
            tracing::info!("network_reprobe: cleared {cleared} adaptive route cache entries after network handover");
        }
    }
}
