use super::*;

#[derive(Default)]
struct ActiveSocketRegistryState {
    next_id: u64,
    sockets: BTreeMap<u64, TcpStream>,
}

#[derive(Clone, Default)]
pub(in crate::runtime) struct ActiveSocketRegistry {
    inner: std::sync::Arc<std::sync::Mutex<ActiveSocketRegistryState>>,
}

impl ActiveSocketRegistry {
    fn register(&self, socket: &TcpStream) -> io::Result<ActiveSocketGuard> {
        let owned = socket.try_clone()?;
        let mut state = self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let id = state.next_id;
        state.next_id = state.next_id.checked_add(1).ok_or_else(|| io::Error::other("active socket id exhausted"))?;
        state.sockets.insert(id, owned);
        Ok(ActiveSocketGuard { id, registry: self.clone() })
    }

    fn shutdown_all(&self) {
        let state = self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        for socket in state.sockets.values() {
            let _ = socket.shutdown(std::net::Shutdown::Both);
        }
    }

    #[cfg(test)]
    fn len(&self) -> usize {
        self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner).sockets.len()
    }
}

pub(in crate::runtime) struct ActiveSocketGuard {
    id: u64,
    registry: ActiveSocketRegistry,
}

impl Drop for ActiveSocketGuard {
    fn drop(&mut self) {
        let mut state = self.registry.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        state.sockets.remove(&self.id);
    }
}

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
    pub(in crate::runtime) fn register_active_tcp_socket(&self, socket: &TcpStream) -> io::Result<ActiveSocketGuard> {
        self.active_tcp_sockets.register(socket)
    }
    /// Keeps the upstream half cancellable while a connection handler owns it.
    /// The registry stores only duplicated descriptors, never endpoint data.
    pub(in crate::runtime) fn register_active_upstream_tcp_socket(
        &self,
        socket: &TcpStream,
    ) -> io::Result<ActiveSocketGuard> {
        self.active_upstream_tcp_sockets.register(socket)
    }
    pub(in crate::runtime) fn shutdown_active_tcp_sockets(&self) {
        self.active_tcp_sockets.shutdown_all();
        self.active_upstream_tcp_sockets.shutdown_all();
    }

    #[cfg(test)]
    pub(in crate::runtime) fn active_upstream_socket_count(&self) -> usize {
        self.active_upstream_tcp_sockets.len()
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

#[cfg(test)]
mod tests {
    use std::io::Read;
    use std::net::{Ipv4Addr, TcpListener, TcpStream};
    use std::time::Duration;

    use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;

    use super::RuntimeState;

    #[test]
    fn forced_shutdown_closes_tracked_upstream_socket() {
        let state = RuntimeState::new(RuntimeConfig::default(), None);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream pair");
        let mut peer = TcpStream::connect(listener.local_addr().expect("listener address")).expect("connect peer");
        let (upstream, _) = listener.accept().expect("accept upstream");
        let _guard = state.register_active_upstream_tcp_socket(&upstream).expect("register upstream");

        assert_eq!(state.active_upstream_socket_count(), 1);
        state.shutdown_active_tcp_sockets();

        peer.set_read_timeout(Some(Duration::from_secs(1))).expect("set peer timeout");
        let mut byte = [0_u8; 1];
        assert_eq!(peer.read(&mut byte).unwrap_or(0), 0, "forced shutdown must close upstream socket");
    }
}
