use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn clear_connection_cache(&self) -> usize {
        PolicySelectionPort::clear_connection_cache(&self.services)
    }
    pub(in crate::runtime) fn drain_autolearn_events(&self) {
        let _ = PolicyLearningPort::drain_autolearn_events(&self.services);
    }
    pub(in crate::runtime) fn flush_autolearn_telemetry(&self) {
        if let Some(telemetry) = &self.telemetry {
            let autolearn = PolicyLearningPort::autolearn_state(&self.services);
            telemetry.on_host_autolearn_state(
                autolearn.enabled,
                autolearn.learned_host_count,
                autolearn.penalized_host_count,
                autolearn.blocked_host_count,
                autolearn.last_block_signal.as_deref(),
                autolearn.last_block_provider.as_deref(),
            );
            for event in PolicyLearningPort::drain_autolearn_events(&self.services) {
                telemetry.on_host_autolearn_event(event.action, event.host.as_deref(), event.group_index);
            }
        } else {
            self.drain_autolearn_events();
        }
    }
    pub(in crate::runtime) fn flush_host_store(&self) {
        PolicyLearningPort::flush_host_store(&self.services);
    }
    pub(in crate::runtime) fn reprobe_reset_handle(&self) -> ReprobeResetHandle {
        reprobe_reset_handle(&self.services)
    }
}
