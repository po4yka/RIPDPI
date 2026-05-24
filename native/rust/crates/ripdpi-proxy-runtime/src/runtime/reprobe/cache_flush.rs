use super::super::state::RuntimeState;

pub(crate) fn flush_runtime_cache_after_handover(state: &RuntimeState) {
    let cleared = state.clear_connection_cache();
    if cleared > 0 {
        tracing::info!("network_reprobe: cleared {cleared} adaptive route cache entries after network handover");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::config::RuntimeConfig;

    #[test]
    fn flush_runtime_cache_after_handover_is_safe_when_cache_is_empty() {
        let state = RuntimeState::test(RuntimeConfig::default());

        flush_runtime_cache_after_handover(&state);
    }
}
