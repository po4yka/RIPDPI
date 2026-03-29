use std::io;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_runtime::RuntimeTelemetrySink;

pub struct TracingTelemetrySink {
    total_sessions: AtomicU64,
    total_errors: AtomicU64,
}

impl TracingTelemetrySink {
    pub fn new() -> Self {
        Self { total_sessions: AtomicU64::new(0), total_errors: AtomicU64::new(0) }
    }

    pub fn print_summary(&self) {
        let sessions = self.total_sessions.load(Ordering::Relaxed);
        let errors = self.total_errors.load(Ordering::Relaxed);
        tracing::info!(sessions, errors, "proxy shutdown complete");
    }
}

impl RuntimeTelemetrySink for TracingTelemetrySink {
    fn on_listener_started(&self, bind_addr: SocketAddr, max_clients: usize, group_count: usize) {
        tracing::info!(%bind_addr, max_clients, group_count, "listener started");
    }

    fn on_listener_stopped(&self) {
        tracing::info!("listener stopped");
    }

    fn on_client_accepted(&self) {
        self.total_sessions.fetch_add(1, Ordering::Relaxed);
    }

    fn on_client_finished(&self) {}

    fn on_client_error(&self, error: &io::Error) {
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        tracing::warn!(%error, "client error");
    }

    fn on_route_selected(&self, target: SocketAddr, group_index: usize, host: Option<&str>, phase: &'static str) {
        tracing::debug!(%target, group_index, host, phase, "route selected");
    }

    fn on_failure_classified(&self, target: SocketAddr, failure: &ClassifiedFailure, host: Option<&str>) {
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        tracing::warn!(%target, class = failure.class.as_str(), host, "failure classified");
    }

    fn on_route_advanced(
        &self,
        target: SocketAddr,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<&str>,
    ) {
        tracing::info!(%target, from_group, to_group, trigger, host, "route advanced");
    }

    fn on_host_autolearn_state(
        &self,
        enabled: bool,
        learned: usize,
        penalized: usize,
        blocked: usize,
        last_block_signal: Option<&str>,
        last_block_provider: Option<&str>,
    ) {
        tracing::debug!(
            enabled,
            learned,
            penalized,
            blocked,
            last_block_signal,
            last_block_provider,
            "autolearn state"
        );
    }

    fn on_host_autolearn_event(&self, action: &'static str, host: Option<&str>, group: Option<usize>) {
        tracing::debug!(action, host, group, "autolearn event");
    }
}
