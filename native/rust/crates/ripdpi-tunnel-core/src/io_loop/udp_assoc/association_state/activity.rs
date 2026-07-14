use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// Returns milliseconds since the Unix epoch, or 0 on clock failure.
pub(in crate::io_loop::udp_assoc) fn now_millis() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

pub(in crate::io_loop::udp_assoc) fn touch_udp_activity(last_activity: &Arc<AtomicU64>) {
    // Ordering: Relaxed -- timestamp staleness of <1ms is acceptable; no happens-before needed.
    last_activity.store(now_millis(), Ordering::Relaxed);
}

pub(in crate::io_loop::udp_assoc) fn udp_association_is_idle(
    last_activity: &Arc<AtomicU64>,
    idle_timeout: Duration,
) -> bool {
    // Ordering: Relaxed -- timestamp staleness of <1ms is acceptable; no happens-before needed.
    now_millis().saturating_sub(last_activity.load(Ordering::Relaxed)) >= idle_timeout.as_millis() as u64
}
