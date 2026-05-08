use super::super::state::RuntimeState;

pub(crate) fn flush_runtime_cache_after_handover(state: &RuntimeState) {
    let cleared = state.clear_connection_cache();
    if cleared > 0 {
        tracing::info!("network_reprobe: cleared {cleared} adaptive route cache entries after network handover");
    }
}
