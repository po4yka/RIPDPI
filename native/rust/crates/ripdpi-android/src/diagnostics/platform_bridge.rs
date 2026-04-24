use android_support::{
    clear_android_log_scope_level, clear_diagnostics_events, drain_diagnostics_events, set_android_log_scope_level,
    NativeEventRecord,
};
use log::LevelFilter;
use ripdpi_monitor::{MonitorPlatformBridge, NativeSessionEvent, ScopedMonitorLogLevel};

pub(crate) struct AndroidMonitorPlatformBridge;

impl MonitorPlatformBridge for AndroidMonitorPlatformBridge {
    fn clear_passive_events(&self) {
        clear_diagnostics_events();
    }

    fn drain_passive_events(&self) -> Vec<NativeSessionEvent> {
        drain_diagnostics_events().into_iter().map(native_session_event_from).collect()
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
