use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_runtime::RuntimeTelemetrySink;
use serde::Serialize;

const MAX_PROXY_EVENTS: usize = 128;

/// Returns true for I/O errors caused by transient network conditions
/// (e.g., Doze mode, network handover, airplane mode toggle).
/// These are separated from permanent failures in telemetry so the UI
/// can distinguish recoverable connectivity blips from real bugs.
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

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NativeRuntimeEvent {
    pub(crate) source: String,
    pub(crate) level: String,
    pub(crate) message: String,
    pub(crate) created_at: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct TunnelStatsSnapshot {
    pub(crate) tx_packets: u64,
    pub(crate) tx_bytes: u64,
    pub(crate) rx_packets: u64,
    pub(crate) rx_bytes: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NativeRuntimeSnapshot {
    pub(crate) source: String,
    pub(crate) state: String,
    pub(crate) health: String,
    pub(crate) active_sessions: u64,
    pub(crate) total_sessions: u64,
    pub(crate) total_errors: u64,
    pub(crate) network_errors: u64,
    pub(crate) route_changes: u64,
    pub(crate) retry_paced_count: u64,
    pub(crate) last_retry_backoff_ms: Option<u64>,
    pub(crate) last_retry_reason: Option<String>,
    pub(crate) candidate_diversification_count: u64,
    pub(crate) last_route_group: Option<i32>,
    pub(crate) last_failure_class: Option<String>,
    pub(crate) last_fallback_action: Option<String>,
    pub(crate) listener_address: Option<String>,
    pub(crate) upstream_address: Option<String>,
    pub(crate) upstream_rtt_ms: Option<u64>,
    pub(crate) last_target: Option<String>,
    pub(crate) last_host: Option<String>,
    pub(crate) last_error: Option<String>,
    pub(crate) autolearn_enabled: bool,
    pub(crate) learned_host_count: i32,
    pub(crate) penalized_host_count: i32,
    pub(crate) last_autolearn_host: Option<String>,
    pub(crate) last_autolearn_group: Option<i32>,
    pub(crate) last_autolearn_action: Option<String>,
    pub(crate) tunnel_stats: TunnelStatsSnapshot,
    pub(crate) native_events: Vec<NativeRuntimeEvent>,
    pub(crate) captured_at: u64,
}

struct TelemetryStrings {
    listener_address: Option<String>,
    upstream_address: Option<String>,
    upstream_rtt_ms: Option<u64>,
    last_target: Option<String>,
    last_host: Option<String>,
    last_error: Option<String>,
    last_failure_class: Option<String>,
    last_fallback_action: Option<String>,
    last_retry_reason: Option<String>,
    last_autolearn_host: Option<String>,
    last_autolearn_action: Option<String>,
    events: VecDeque<NativeRuntimeEvent>,
}

pub(crate) struct ProxyTelemetryState {
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    total_errors: AtomicU64,
    network_errors: AtomicU64,
    route_changes: AtomicU64,
    retry_paced_count: AtomicU64,
    last_retry_backoff_ms: AtomicU64,
    candidate_diversification_count: AtomicU64,
    last_route_group: AtomicI64,
    autolearn_enabled: AtomicBool,
    learned_host_count: AtomicU64,
    penalized_host_count: AtomicU64,
    last_autolearn_group: AtomicI64,
    strings: Mutex<TelemetryStrings>,
}

impl ProxyTelemetryState {
    pub(crate) fn new() -> Self {
        Self {
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            total_errors: AtomicU64::new(0),
            network_errors: AtomicU64::new(0),
            route_changes: AtomicU64::new(0),
            retry_paced_count: AtomicU64::new(0),
            last_retry_backoff_ms: AtomicU64::new(0),
            candidate_diversification_count: AtomicU64::new(0),
            last_route_group: AtomicI64::new(-1),
            autolearn_enabled: AtomicBool::new(false),
            learned_host_count: AtomicU64::new(0),
            penalized_host_count: AtomicU64::new(0),
            last_autolearn_group: AtomicI64::new(-1),
            strings: Mutex::new(TelemetryStrings {
                listener_address: None,
                upstream_address: None,
                upstream_rtt_ms: None,
                last_target: None,
                last_host: None,
                last_error: None,
                last_failure_class: None,
                last_fallback_action: None,
                last_retry_reason: None,
                last_autolearn_host: None,
                last_autolearn_action: None,
                events: VecDeque::with_capacity(MAX_PROXY_EVENTS),
            }),
        }
    }

    pub(crate) fn mark_running(&self, bind_addr: String, max_clients: usize, group_count: usize) {
        self.running.store(true, Ordering::Relaxed);
        let message = format!("listener started addr={bind_addr} maxClients={max_clients} groups={group_count}");
        log::info!("{message}");
        if let Ok(mut guard) = self.strings.lock() {
            guard.listener_address = Some(bind_addr);
            Self::push_event_to(&mut guard.events, "proxy", "info", message);
        }
    }

    pub(crate) fn mark_stopped(&self) {
        self.running.store(false, Ordering::Relaxed);
        self.active_sessions.store(0, Ordering::Relaxed);
        let message = "listener stopped".to_string();
        log::info!("{message}");
        if let Ok(mut guard) = self.strings.lock() {
            Self::push_event_to(&mut guard.events, "proxy", "info", message);
        }
    }

    pub(crate) fn on_client_accepted(&self) {
        self.active_sessions.fetch_add(1, Ordering::Relaxed);
        self.total_sessions.fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn on_client_finished(&self) {
        self.active_sessions
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |value| Some(value.saturating_sub(1)))
            .ok();
    }

    pub(crate) fn on_client_error(&self, error: String) {
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        let message = format!("client error: {error}");
        log::warn!("{message}");
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_error = Some(error);
            Self::push_event_to(&mut guard.events, "proxy", "warn", message);
        }
    }

    pub(crate) fn on_client_io_error(&self, error: &std::io::Error) {
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        if is_transient_network_error(error) {
            self.network_errors.fetch_add(1, Ordering::Relaxed);
        }
        let error_str = error.to_string();
        let message = format!("client error: {error_str}");
        log::warn!("{message}");
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_error = Some(error_str);
            Self::push_event_to(&mut guard.events, "proxy", "warn", message);
        }
    }

    pub(crate) fn on_route_selected(&self, target: String, group_index: usize, host: Option<String>, phase: &str) {
        self.last_route_group.store(group_index.try_into().unwrap_or(i64::MAX), Ordering::Relaxed);
        let message = format!(
            "route selected phase={} group={} target={} host={}",
            phase,
            group_index,
            target,
            host.as_deref().unwrap_or("<none>")
        );
        log::info!("{message}");
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_target = Some(target);
            guard.last_host = host;
            Self::push_event_to(&mut guard.events, "proxy", "info", message);
        }
    }

    pub(crate) fn on_route_advanced(
        &self,
        target: String,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<String>,
    ) {
        self.route_changes.fetch_add(1, Ordering::Relaxed);
        self.last_route_group.store(to_group.try_into().unwrap_or(i64::MAX), Ordering::Relaxed);
        let message = format!(
            "route advanced target={} from={} to={} trigger={} host={}",
            target,
            from_group,
            to_group,
            trigger,
            host.as_deref().unwrap_or("<none>")
        );
        log::warn!("{message}");
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_target = Some(target);
            guard.last_host = host;
            Self::push_event_to(&mut guard.events, "proxy", "warn", message);
        }
    }

    pub(crate) fn on_failure_classified(&self, target: String, failure: &ClassifiedFailure, host: Option<String>) {
        let level = if failure.action.as_str() == "retry_with_matching_group" { "warn" } else { "info" };
        let message = format!(
            "failure classified target={} class={} stage={} action={} host={} evidence={}",
            target,
            failure.class.as_str(),
            failure.stage.as_str(),
            failure.action.as_str(),
            host.as_deref().unwrap_or("<none>"),
            failure.evidence.summary
        );
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_target = Some(target);
            guard.last_host = host;
            guard.last_error = Some(failure.evidence.summary.clone());
            guard.last_failure_class = Some(failure.class.as_str().to_string());
            guard.last_fallback_action = Some(failure.action.as_str().to_string());
            Self::push_event_to(&mut guard.events, "proxy", level, message);
        }
    }

    pub(crate) fn on_retry_paced(&self, target: String, group_index: usize, reason: &'static str, backoff_ms: u64) {
        if backoff_ms > 0 {
            self.retry_paced_count.fetch_add(1, Ordering::Relaxed);
        }
        self.last_retry_backoff_ms.store(backoff_ms, Ordering::Relaxed);
        if reason == "candidate_order_diversified" {
            self.candidate_diversification_count.fetch_add(1, Ordering::Relaxed);
        }
        let message =
            format!("retry pacing target={} group={} reason={} backoffMs={}", target, group_index, reason, backoff_ms);
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_target = Some(target);
            guard.last_retry_reason = Some(reason.to_string());
            Self::push_event_to(&mut guard.events, "proxy", "info", message);
        }
    }

    pub(crate) fn on_upstream_connected(&self, upstream_address: String, upstream_rtt_ms: Option<u64>) {
        if let Ok(mut guard) = self.strings.lock() {
            guard.upstream_address = Some(upstream_address);
            guard.upstream_rtt_ms = upstream_rtt_ms;
        }
    }

    pub(crate) fn set_autolearn_state(&self, enabled: bool, learned_host_count: usize, penalized_host_count: usize) {
        self.autolearn_enabled.store(enabled, Ordering::Relaxed);
        self.learned_host_count.store(learned_host_count as u64, Ordering::Relaxed);
        self.penalized_host_count.store(penalized_host_count as u64, Ordering::Relaxed);
    }

    pub(crate) fn on_autolearn_event(&self, action: &'static str, host: Option<String>, group_index: Option<usize>) {
        self.last_autolearn_group
            .store(group_index.and_then(|value| i64::try_from(value).ok()).unwrap_or(-1), Ordering::Relaxed);
        let level = if action == "group_penalized" { "warn" } else { "info" };
        let message = format!(
            "autolearn action={} host={} group={}",
            action,
            host.as_deref().unwrap_or("<none>"),
            group_index.map(|value| value.to_string()).unwrap_or_else(|| "<none>".to_string())
        );
        match level {
            "warn" => log::warn!("{message}"),
            _ => log::info!("{message}"),
        }
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_autolearn_host = host;
            guard.last_autolearn_action = Some(action.to_string());
            Self::push_event_to(&mut guard.events, "autolearn", level, message);
        }
    }

    pub(crate) fn snapshot(&self) -> NativeRuntimeSnapshot {
        let (
            listener_address,
            upstream_address,
            upstream_rtt_ms,
            last_target,
            last_host,
            last_error,
            last_failure_class,
            last_fallback_action,
            last_retry_reason,
            last_autolearn_host,
            last_autolearn_action,
            native_events,
        ) = if let Ok(mut guard) = self.strings.lock() {
            (
                guard.listener_address.clone(),
                guard.upstream_address.clone(),
                guard.upstream_rtt_ms,
                guard.last_target.clone(),
                guard.last_host.clone(),
                guard.last_error.clone(),
                guard.last_failure_class.clone(),
                guard.last_fallback_action.clone(),
                guard.last_retry_reason.clone(),
                guard.last_autolearn_host.clone(),
                guard.last_autolearn_action.clone(),
                guard.events.drain(..).collect(),
            )
        } else {
            (None, None, None, None, None, None, None, None, None, None, None, Vec::new())
        };

        NativeRuntimeSnapshot {
            source: "proxy".to_string(),
            state: if self.running.load(Ordering::Relaxed) { "running".to_string() } else { "idle".to_string() },
            health: if self.running.load(Ordering::Relaxed) {
                if self.total_errors.load(Ordering::Relaxed) == 0 {
                    "healthy".to_string()
                } else {
                    "degraded".to_string()
                }
            } else {
                "idle".to_string()
            },
            active_sessions: self.active_sessions.load(Ordering::Relaxed),
            total_sessions: self.total_sessions.load(Ordering::Relaxed),
            total_errors: self.total_errors.load(Ordering::Relaxed),
            network_errors: self.network_errors.load(Ordering::Relaxed),
            route_changes: self.route_changes.load(Ordering::Relaxed),
            retry_paced_count: self.retry_paced_count.load(Ordering::Relaxed),
            last_retry_backoff_ms: match self.last_retry_backoff_ms.load(Ordering::Relaxed) {
                0 => None,
                value => Some(value),
            },
            last_retry_reason,
            candidate_diversification_count: self.candidate_diversification_count.load(Ordering::Relaxed),
            last_route_group: match self.last_route_group.load(Ordering::Relaxed) {
                value if value >= 0 => i32::try_from(value).ok(),
                _ => None,
            },
            last_failure_class,
            last_fallback_action,
            listener_address,
            upstream_address,
            upstream_rtt_ms,
            last_target,
            last_host,
            last_error,
            autolearn_enabled: self.autolearn_enabled.load(Ordering::Relaxed),
            learned_host_count: i32::try_from(self.learned_host_count.load(Ordering::Relaxed)).unwrap_or(i32::MAX),
            penalized_host_count: i32::try_from(self.penalized_host_count.load(Ordering::Relaxed)).unwrap_or(i32::MAX),
            last_autolearn_host,
            last_autolearn_group: match self.last_autolearn_group.load(Ordering::Relaxed) {
                value if value >= 0 => i32::try_from(value).ok(),
                _ => None,
            },
            last_autolearn_action,
            tunnel_stats: TunnelStatsSnapshot { tx_packets: 0, tx_bytes: 0, rx_packets: 0, rx_bytes: 0 },
            native_events,
            captured_at: now_ms(),
        }
    }

    pub(crate) fn clear_last_error(&self) {
        if let Ok(mut guard) = self.strings.lock() {
            guard.last_error = None;
        }
    }

    pub(crate) fn push_event(&self, source: &str, level: &str, message: String) {
        match level {
            "warn" => log::warn!("{message}"),
            "error" => log::error!("{message}"),
            _ => log::info!("{message}"),
        }
        if let Ok(mut guard) = self.strings.lock() {
            Self::push_event_to(&mut guard.events, source, level, message);
        }
    }

    fn push_event_to(events: &mut VecDeque<NativeRuntimeEvent>, source: &str, level: &str, message: String) {
        if events.len() >= MAX_PROXY_EVENTS {
            events.pop_front();
        }
        events.push_back(NativeRuntimeEvent {
            source: source.to_string(),
            level: level.to_string(),
            message,
            created_at: now_ms(),
        });
    }
}

pub(crate) struct ProxyTelemetryObserver {
    pub(crate) state: Arc<ProxyTelemetryState>,
}

impl RuntimeTelemetrySink for ProxyTelemetryObserver {
    fn on_listener_started(&self, bind_addr: std::net::SocketAddr, max_clients: usize, group_count: usize) {
        self.state.mark_running(bind_addr.to_string(), max_clients, group_count);
    }

    fn on_listener_stopped(&self) {
        self.state.mark_stopped();
    }

    fn on_client_accepted(&self) {
        self.state.on_client_accepted();
    }

    fn on_client_finished(&self) {
        self.state.on_client_finished();
    }

    fn on_client_error(&self, error: &std::io::Error) {
        self.state.on_client_io_error(error);
    }

    fn on_route_selected(
        &self,
        target: std::net::SocketAddr,
        group_index: usize,
        host: Option<&str>,
        phase: &'static str,
    ) {
        self.state.on_route_selected(target.to_string(), group_index, host.map(ToOwned::to_owned), phase);
    }

    fn on_failure_classified(&self, target: std::net::SocketAddr, failure: &ClassifiedFailure, host: Option<&str>) {
        self.state.total_errors.fetch_add(1, Ordering::Relaxed);
        if failure.action.as_str() == "retry_with_matching_group" {
            self.state.network_errors.fetch_add(1, Ordering::Relaxed);
        }
        self.state.on_failure_classified(target.to_string(), failure, host.map(ToOwned::to_owned));
    }

    fn on_upstream_connected(&self, upstream_addr: std::net::SocketAddr, rtt_ms: Option<u64>) {
        self.state.on_upstream_connected(upstream_addr.to_string(), rtt_ms);
    }

    fn on_route_advanced(
        &self,
        target: std::net::SocketAddr,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<&str>,
    ) {
        self.state.on_route_advanced(target.to_string(), from_group, to_group, trigger, host.map(ToOwned::to_owned));
    }

    fn on_retry_paced(&self, target: std::net::SocketAddr, group_index: usize, reason: &'static str, backoff_ms: u64) {
        self.state.on_retry_paced(target.to_string(), group_index, reason, backoff_ms);
    }

    fn on_host_autolearn_state(&self, enabled: bool, learned_host_count: usize, penalized_host_count: usize) {
        self.state.set_autolearn_state(enabled, learned_host_count, penalized_host_count);
    }

    fn on_host_autolearn_event(&self, action: &'static str, host: Option<&str>, group_index: Option<usize>) {
        self.state.on_autolearn_event(action, host.map(ToOwned::to_owned), group_index);
    }
}

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}
