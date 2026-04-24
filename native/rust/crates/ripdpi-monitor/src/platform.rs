use log::LevelFilter;

use crate::NativeSessionEvent;

pub trait MonitorPlatformBridge: Send + Sync {
    fn clear_passive_events(&self) {}

    fn drain_passive_events(&self) -> Vec<NativeSessionEvent> {
        Vec::new()
    }

    fn scoped_log_level(&self, _scope: String, _level: LevelFilter) -> Box<dyn ScopedMonitorLogLevel> {
        Box::new(NoopScopedMonitorLogLevel)
    }
}

pub trait ScopedMonitorLogLevel: Send {}

pub(crate) struct NoopMonitorPlatformBridge;

impl MonitorPlatformBridge for NoopMonitorPlatformBridge {}

struct NoopScopedMonitorLogLevel;

impl ScopedMonitorLogLevel for NoopScopedMonitorLogLevel {}
