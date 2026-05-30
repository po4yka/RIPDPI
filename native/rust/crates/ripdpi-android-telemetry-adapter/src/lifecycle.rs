use std::sync::atomic::Ordering;

use super::state::ProxyTelemetryState;

fn is_transient_network_error(error: &std::io::Error) -> bool {
    matches!(
        error.raw_os_error(),
        Some(
            libc::ENETUNREACH
                | libc::EHOSTUNREACH
                | libc::ETIMEDOUT
                | libc::ECONNREFUSED
                | libc::ECONNRESET
                | libc::ECONNABORTED
                | libc::ENETDOWN
                | libc::EPIPE
        )
    )
}

impl ProxyTelemetryState {
    pub fn mark_running(&self, bind_addr: String, max_clients: usize, group_count: usize) {
        // Ordering: Release -- pairs with Acquire loads in snapshot() to publish the running
        // state transition; readers on other threads must see all preceding writes.
        self.running.store(true, Ordering::Release);
        // Ordering: Release -- pairs with Acquire load in snapshot(); signals override cleared.
        self.adaptive_override_active.store(false, Ordering::Release);
        let message = format!("listener started addr={bind_addr} maxClients={max_clients} groups={group_count}");
        self.emit_event("proxy", "info", &message, Some("runtime_ready"));
        // Push readiness to any installed observer (native readiness event,
        // ADR 0003) at the same point the `runtime_ready` telemetry fires, so
        // the Kotlin wrapper need not poll. No-op when no observer is set.
        self.notify_ready();
        self.update_strings(|s| {
            s.listener_address = Some(bind_addr.clone());
            s.adaptive_trigger_mask = None;
            s.adaptive_last_trigger = None;
            s.adaptive_override_reason = None;
            s.morph_hint_family = None;
            s.morph_rollback_reason = None;
        });
    }

    pub fn mark_stopped(&self) {
        // Ordering: Release -- pairs with Acquire load in snapshot(); publishes stopped state.
        self.running.store(false, Ordering::Release);
        // Ordering: Release -- active_sessions gates UI display of "N active"; Release ensures
        // readers that Acquire-load running=false also see active_sessions=0.
        self.active_sessions.store(0, Ordering::Release);
        // Ordering: Release -- pairs with Acquire load in snapshot(); signals override cleared.
        self.adaptive_override_active.store(false, Ordering::Release);
        let message = "listener stopped".to_string();
        self.emit_event("proxy", "info", &message, Some("runtime_stopped"));
    }

    pub fn on_client_accepted(&self) {
        // Ordering: AcqRel -- active_sessions gates display logic ("if active_sessions > 0");
        // AcqRel on fetch_add ensures the increment is globally visible on all cores.
        self.active_sessions.fetch_add(1, Ordering::AcqRel);
        // Ordering: Relaxed -- monotonic counter read for display only; no happens-before needed.
        self.total_sessions.fetch_add(1, Ordering::Relaxed);
    }

    pub fn on_client_finished(&self) {
        // Ordering: AcqRel/Acquire -- active_sessions gates display logic; use AcqRel on success
        // and Acquire on load so the decrement is visible to concurrent snapshot readers.
        self.active_sessions
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| Some(value.saturating_sub(1)))
            .ok();
    }

    pub fn on_client_error(&self, error: String) {
        // Ordering: Relaxed -- counter read for display only, no happens-before needed.
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        let message = format!("client error: {error}");
        self.emit_event("proxy", "warn", &message, None);
        self.update_strings(|s| s.last_error = Some(error.clone()));
    }

    pub fn on_client_io_error(&self, error: &std::io::Error) {
        // Ordering: Relaxed -- counter read for display only, no happens-before needed.
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        if is_transient_network_error(error) {
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            self.network_errors.fetch_add(1, Ordering::Relaxed);
        }
        let error_str = error.to_string();
        let message = format!("client error: {error_str}");
        self.emit_event("proxy", "warn", &message, None);
        self.update_strings(|s| s.last_error = Some(error_str.clone()));
    }

    pub fn on_upstream_connected(&self, upstream_address: String, upstream_rtt_ms: Option<u64>) {
        if let Some(rtt_ms) = upstream_rtt_ms {
            self.tcp_connect_histogram.record(rtt_ms);
        }
        self.update_strings(|s| {
            s.upstream_address = Some(upstream_address.clone());
            s.upstream_rtt_ms = upstream_rtt_ms;
        });
    }

    pub fn on_tls_handshake_completed(&self, latency_ms: u64) {
        self.tls_handshake_histogram.record(latency_ms);
    }
}
