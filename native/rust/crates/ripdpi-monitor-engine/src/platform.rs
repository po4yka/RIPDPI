use log::LevelFilter;

use crate::contracts::NativeSessionEvent;

pub trait MonitorPlatformBridge: Send + Sync {
    fn clear_passive_events(&self, _session_id: &str) {}

    fn drain_passive_events(&self, _session_id: &str) -> Vec<NativeSessionEvent> {
        Vec::new()
    }

    fn scoped_log_level(&self, _scope: String, _level: LevelFilter) -> Box<dyn ScopedMonitorLogLevel> {
        Box::new(NoopScopedMonitorLogLevel)
    }
}

pub trait ScopedMonitorLogLevel: Send {}

pub struct NoopMonitorPlatformBridge;

impl MonitorPlatformBridge for NoopMonitorPlatformBridge {}

struct NoopScopedMonitorLogLevel;

impl ScopedMonitorLogLevel for NoopScopedMonitorLogLevel {}
