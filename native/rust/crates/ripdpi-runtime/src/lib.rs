use std::io;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex, OnceLock};

pub mod platform;
pub mod process;
pub mod runtime;
pub mod runtime_policy;

pub trait RuntimeTelemetrySink: Send + Sync {
    fn on_listener_started(&self, bind_addr: SocketAddr, max_clients: usize, group_count: usize);

    fn on_listener_stopped(&self);

    fn on_client_accepted(&self);

    fn on_client_finished(&self);

    fn on_client_error(&self, error: &io::Error);

    fn on_route_selected(&self, target: SocketAddr, group_index: usize, host: Option<&str>, phase: &'static str);

    fn on_route_advanced(
        &self,
        target: SocketAddr,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<&str>,
    );
}

static TELEMETRY_SINK: OnceLock<Mutex<Option<Arc<dyn RuntimeTelemetrySink>>>> = OnceLock::new();

fn telemetry_slot() -> &'static Mutex<Option<Arc<dyn RuntimeTelemetrySink>>> {
    TELEMETRY_SINK.get_or_init(|| Mutex::new(None))
}

pub fn install_runtime_telemetry(sink: Arc<dyn RuntimeTelemetrySink>) {
    if let Ok(mut slot) = telemetry_slot().lock() {
        *slot = Some(sink);
    }
}

pub fn clear_runtime_telemetry() {
    if let Ok(mut slot) = telemetry_slot().lock() {
        *slot = None;
    }
}

pub(crate) fn current_runtime_telemetry() -> Option<Arc<dyn RuntimeTelemetrySink>> {
    telemetry_slot().lock().ok().and_then(|slot| slot.clone())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct CountingSink {
        accepted: AtomicUsize,
    }

    impl CountingSink {
        fn new() -> Self {
            Self { accepted: AtomicUsize::new(0) }
        }
    }

    impl RuntimeTelemetrySink for CountingSink {
        fn on_listener_started(&self, _bind_addr: SocketAddr, _max_clients: usize, _group_count: usize) {}

        fn on_listener_stopped(&self) {}

        fn on_client_accepted(&self) {
            self.accepted.fetch_add(1, Ordering::Relaxed);
        }

        fn on_client_finished(&self) {}

        fn on_client_error(&self, _error: &io::Error) {}

        fn on_route_selected(
            &self,
            _target: SocketAddr,
            _group_index: usize,
            _host: Option<&str>,
            _phase: &'static str,
        ) {
        }

        fn on_route_advanced(
            &self,
            _target: SocketAddr,
            _from_group: usize,
            _to_group: usize,
            _trigger: u32,
            _host: Option<&str>,
        ) {
        }
    }

    #[test]
    fn install_runtime_telemetry_exposes_current_sink_until_cleared() {
        clear_runtime_telemetry();
        let first = Arc::new(CountingSink::new());
        install_runtime_telemetry(first.clone());

        let current = current_runtime_telemetry().expect("installed sink");
        current.on_client_accepted();
        assert_eq!(first.accepted.load(Ordering::Relaxed), 1);

        clear_runtime_telemetry();
        assert!(current_runtime_telemetry().is_none());
    }

    #[test]
    fn installing_new_runtime_telemetry_replaces_previous_sink() {
        clear_runtime_telemetry();
        let first = Arc::new(CountingSink::new());
        let second = Arc::new(CountingSink::new());

        install_runtime_telemetry(first.clone());
        install_runtime_telemetry(second.clone());

        let current = current_runtime_telemetry().expect("replacement sink");
        current.on_client_accepted();
        assert_eq!(first.accepted.load(Ordering::Relaxed), 0);
        assert_eq!(second.accepted.load(Ordering::Relaxed), 1);

        clear_runtime_telemetry();
    }
}
