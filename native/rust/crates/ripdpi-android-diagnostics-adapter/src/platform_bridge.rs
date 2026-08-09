use android_support::{
    NativeEventRecord, clear_android_log_scope_level, clear_diagnostics_events_for_session,
    drain_diagnostics_events_for_session, set_android_log_scope_level,
};
use log::LevelFilter;
use ripdpi_diagnostics_contracts::NativeSessionEvent;
use ripdpi_monitor_engine::{MonitorPlatformBridge, ScopedMonitorLogLevel};

pub(crate) struct AndroidMonitorPlatformBridge;

impl MonitorPlatformBridge for AndroidMonitorPlatformBridge {
    fn clear_passive_events(&self, session_id: &str) {
        clear_diagnostics_events_for_session(session_id);
    }

    fn drain_passive_events(&self, session_id: &str) -> Vec<NativeSessionEvent> {
        drain_diagnostics_events_for_session(session_id).into_iter().map(native_session_event_from).collect()
    }

    fn scoped_log_level(&self, scope: String, level: LevelFilter) -> Box<dyn ScopedMonitorLogLevel> {
        Box::new(AndroidScopedLogLevel::new(scope, level))
    }
}

fn native_session_event_from(value: NativeEventRecord) -> NativeSessionEvent {
    NativeSessionEvent {
        source: value.source,
        level: value.level,
        message: value.message,
        created_at: value.created_at,
        runtime_id: value.runtime_id,
        mode: value.mode,
        policy_signature: value.policy_signature,
        fingerprint_hash: value.fingerprint_hash,
        subsystem: value.subsystem,
    }
}

struct AndroidScopedLogLevel {
    scope: String,
}

impl AndroidScopedLogLevel {
    fn new(scope: String, level: LevelFilter) -> Self {
        set_android_log_scope_level(scope.clone(), level);
        Self { scope }
    }
}

impl ScopedMonitorLogLevel for AndroidScopedLogLevel {}

impl Drop for AndroidScopedLogLevel {
    fn drop(&mut self) {
        clear_android_log_scope_level(&self.scope);
    }
}
