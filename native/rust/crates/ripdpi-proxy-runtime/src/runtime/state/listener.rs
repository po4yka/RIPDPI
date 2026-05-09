use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn listener_client_capacity(&self) -> usize {
        self.listener_settings.client_capacity
    }
    pub(in crate::runtime) fn listener_route_group_count(&self) -> usize {
        self.listener_settings.route_group_count
    }
    pub(in crate::runtime) fn acquire_client_slot(&self, client_capacity: usize) -> Option<ClientSlotGuard> {
        ClientSlotGuard::acquire(self.active_clients.clone(), client_capacity)
    }
    pub(in crate::runtime) fn note_listener_started(
        &self,
        bind_addr: SocketAddr,
        max_clients: usize,
        group_count: usize,
    ) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_listener_started(bind_addr, max_clients, group_count);
        }
    }
    pub(in crate::runtime) fn note_listener_stopped(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_listener_stopped();
        }
    }
    pub(in crate::runtime) fn note_client_slot_exhausted(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_client_slot_exhausted();
        }
    }
    pub(in crate::runtime) fn note_client_accepted(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_client_accepted();
        }
    }
    pub(in crate::runtime) fn note_client_finished(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_client_finished();
        }
    }
    pub(in crate::runtime) fn note_client_error(&self, error: &io::Error) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_client_error(error);
        }
    }
}

pub(in crate::runtime) struct ClientSlotGuard {
    active: Arc<AtomicUsize>,
}

impl ClientSlotGuard {
    pub(in crate::runtime) fn acquire(active: Arc<AtomicUsize>, limit: usize) -> Option<Self> {
        loop {
            let current = active.load(Ordering::Relaxed);
            if current >= limit {
                return None;
            }
            if active.compare_exchange(current, current + 1, Ordering::AcqRel, Ordering::Relaxed).is_ok() {
                return Some(Self { active });
            }
        }
    }
}

impl Drop for ClientSlotGuard {
    fn drop(&mut self) {
        self.active.fetch_sub(1, Ordering::AcqRel);
    }
}
