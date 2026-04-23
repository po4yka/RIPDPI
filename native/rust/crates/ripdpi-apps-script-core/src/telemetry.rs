use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::Serialize;

use crate::AppsScriptRuntimeConfig;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RelayTelemetry {
    pub source: &'static str,
    pub state: String,
    pub health: String,
    pub active_sessions: u64,
    pub total_sessions: u64,
    pub total_errors: u64,
    pub listener_address: Option<String>,
    pub upstream_address: Option<String>,
    pub profile_id: Option<String>,
    pub protocol_kind: Option<String>,
    pub tcp_capable: Option<bool>,
    pub udp_capable: Option<bool>,
    pub last_target: Option<String>,
    pub last_error: Option<String>,
    pub last_host: Option<String>,
    pub captured_at: u64,
}

pub struct RelayTelemetryState {
    state: Mutex<String>,
    health: Mutex<String>,
    listener_address: Mutex<Option<String>>,
    last_target: Mutex<Option<String>>,
    last_host: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    total_errors: AtomicU64,
    total_bytes: AtomicU64,
    profile_id: String,
    kind: String,
}

impl RelayTelemetryState {
    pub fn new(config: &AppsScriptRuntimeConfig) -> Self {
        Self {
            state: Mutex::new("idle".to_string()),
            health: Mutex::new("idle".to_string()),
            listener_address: Mutex::new(None),
            last_target: Mutex::new(None),
            last_host: Mutex::new(None),
            last_error: Mutex::new(None),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            total_errors: AtomicU64::new(0),
            total_bytes: AtomicU64::new(0),
            profile_id: config.profile_id.clone(),
            kind: config.kind.clone(),
        }
    }

    pub fn mark_starting(&self) {
        self.set_state("starting", "starting");
    }

    pub fn mark_listener_bound(&self, listener_address: String) {
        *self.listener_address.lock().expect("listener telemetry mutex") = Some(listener_address);
        self.set_state("running", "healthy");
    }

    pub fn mark_stopping(&self) {
        self.set_state("stopping", "degraded");
    }

    pub fn mark_stopped(&self) {
        self.set_state("stopped", "idle");
        self.active_sessions.store(0, Ordering::Relaxed);
    }

    pub fn record_target(&self, target: &str) {
        *self.last_target.lock().expect("target telemetry mutex") = Some(target.to_string());
        let host = target.split(':').next().unwrap_or(target).to_string();
        *self.last_host.lock().expect("host telemetry mutex") = Some(host);
    }

    pub fn session_opened(&self) {
        self.total_sessions.fetch_add(1, Ordering::Relaxed);
        self.active_sessions.fetch_add(1, Ordering::Relaxed);
    }

    pub fn session_closed(&self) {
        self.active_sessions.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |value| value.checked_sub(1)).ok();
    }

    pub fn record_success(&self, byte_count: usize) {
        self.total_bytes.fetch_add(u64::try_from(byte_count).unwrap_or(u64::MAX), Ordering::Relaxed);
        if self.total_errors.load(Ordering::Relaxed) == 0 {
            self.set_state("running", "healthy");
        }
    }

    pub fn record_error(&self, message: String) {
        *self.last_error.lock().expect("error telemetry mutex") = Some(message);
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        self.set_state("running", "degraded");
    }

    pub fn snapshot(&self, config: &AppsScriptRuntimeConfig) -> RelayTelemetry {
        RelayTelemetry {
            source: "relay",
            state: self.state.lock().expect("state telemetry mutex").clone(),
            health: self.health.lock().expect("health telemetry mutex").clone(),
            active_sessions: self.active_sessions.load(Ordering::Relaxed),
            total_sessions: self.total_sessions.load(Ordering::Relaxed),
            total_errors: self.total_errors.load(Ordering::Relaxed),
            listener_address: self.listener_address.lock().expect("listener telemetry mutex").clone(),
            upstream_address: Some(config.upstream_address()),
            profile_id: (!self.profile_id.is_empty()).then(|| self.profile_id.clone()),
            protocol_kind: Some(self.kind.clone()),
            tcp_capable: Some(true),
            udp_capable: Some(false),
            last_target: self.last_target.lock().expect("target telemetry mutex").clone(),
            last_error: self.last_error.lock().expect("error telemetry mutex").clone(),
            last_host: self.last_host.lock().expect("host telemetry mutex").clone(),
            captured_at: now_epoch_millis(),
        }
    }

    fn set_state(&self, state: &str, health: &str) {
        *self.state.lock().expect("state telemetry mutex") = state.to_string();
        *self.health.lock().expect("health telemetry mutex") = health.to_string();
    }
}

fn now_epoch_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| u64::try_from(value.as_millis()).unwrap_or(u64::MAX))
        .unwrap_or(0)
}

pub(crate) type SharedTelemetryState = Arc<RelayTelemetryState>;
