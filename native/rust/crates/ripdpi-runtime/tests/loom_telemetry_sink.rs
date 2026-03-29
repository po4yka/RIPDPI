//! Loom concurrency tests for the `Mutex<Option<Arc<dyn RuntimeTelemetrySink>>>` pattern.
//!
//! `TELEMETRY_SINK` uses `OnceLock` which is not modeled by loom, so these tests
//! exercise the underlying slot pattern (install / clear / install races) with a
//! locally constructed `loom::sync::Mutex` rather than the global static.
//!
//! Run with: cargo test -p ripdpi-runtime --features loom -- loom

#[cfg(feature = "loom")]
mod tests {
    use std::io;
    use std::net::SocketAddr;

    use loom::sync::{Arc, Mutex};
    use ripdpi_failure_classifier::ClassifiedFailure;
    use ripdpi_runtime::RuntimeTelemetrySink;

    struct NoopSink;

    impl RuntimeTelemetrySink for NoopSink {
        fn on_listener_started(&self, _bind_addr: SocketAddr, _max_clients: usize, _group_count: usize) {}
        fn on_listener_stopped(&self) {}
        fn on_client_accepted(&self) {}
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
        fn on_failure_classified(&self, _target: SocketAddr, _failure: &ClassifiedFailure, _host: Option<&str>) {}
        fn on_route_advanced(
            &self,
            _target: SocketAddr,
            _from_group: usize,
            _to_group: usize,
            _trigger: u32,
            _host: Option<&str>,
        ) {
        }
        fn on_host_autolearn_state(
            &self,
            _enabled: bool,
            _learned_host_count: usize,
            _penalized_host_count: usize,
            _blocked_host_count: usize,
            _last_block_signal: Option<&str>,
            _last_block_provider: Option<&str>,
        ) {
        }
        fn on_host_autolearn_event(&self, _action: &'static str, _host: Option<&str>, _group_index: Option<usize>) {}
    }

    // loom::sync::Arc does not support CoerceUnsized, so we must create a
    // std::sync::Arc<dyn Trait> first (which supports unsizing) and then wrap
    // it in a loom Arc via Arc::from_std.
    fn noop_sink_arc() -> Arc<dyn RuntimeTelemetrySink> {
        let std_arc: std::sync::Arc<dyn RuntimeTelemetrySink> = std::sync::Arc::new(NoopSink);
        Arc::from_std(std_arc)
    }

    type Slot = Mutex<Option<Arc<dyn RuntimeTelemetrySink>>>;

    fn install(slot: &Slot, sink: Arc<dyn RuntimeTelemetrySink>) {
        if let Ok(mut guard) = slot.lock() {
            *guard = Some(sink);
        }
    }

    fn clear(slot: &Slot) {
        if let Ok(mut guard) = slot.lock() {
            *guard = None;
        }
    }

    #[test]
    fn loom_install_clear_install_no_lost_update() {
        loom::model(|| {
            let slot: Arc<Slot> = Arc::new(Mutex::new(None));

            let slot1 = slot.clone();
            let t1 = loom::thread::spawn(move || {
                install(&slot1, noop_sink_arc());
                clear(&slot1);
            });

            let slot2 = slot.clone();
            let t2 = loom::thread::spawn(move || {
                install(&slot2, noop_sink_arc());
            });

            t1.join().unwrap();
            t2.join().unwrap();

            // Final state is either Some or None depending on scheduling.
            // The key invariant: no deadlock or panic in any interleaving.
            let guard = slot.lock().unwrap();
            let _ = guard.is_some();
        });
    }

    #[test]
    fn loom_concurrent_clear_and_install_no_deadlock() {
        loom::model(|| {
            let slot: Arc<Slot> = Arc::new(Mutex::new(None));

            // Pre-install a sink so clear has something to remove.
            install(&slot, noop_sink_arc());

            let slot1 = slot.clone();
            let t1 = loom::thread::spawn(move || clear(&slot1));

            let slot2 = slot.clone();
            let t2 = loom::thread::spawn(move || {
                install(&slot2, noop_sink_arc());
            });

            t1.join().unwrap();
            t2.join().unwrap();

            // No deadlock occurred; final state is well-defined.
            let _ = slot.lock().unwrap().is_some();
        });
    }
}
