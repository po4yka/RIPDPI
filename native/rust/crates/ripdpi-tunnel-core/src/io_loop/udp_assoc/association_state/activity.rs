use std::sync::OnceLock;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

static ACTIVITY_ORIGIN: OnceLock<Instant> = OnceLock::new();

/// Returns monotonic milliseconds since the process activity clock started.
pub(in crate::io_loop::udp_assoc) fn now_millis() -> u64 {
    ACTIVITY_ORIGIN.get_or_init(Instant::now).elapsed().as_millis() as u64
}

pub(in crate::io_loop::udp_assoc) fn touch_udp_activity(last_activity: &AtomicU64) {
    touch_activity_at(last_activity, now_millis());
}

pub(in crate::io_loop::udp_assoc) fn touch_activity_at(last_activity: &AtomicU64, now: u64) {
    // Ordering: Relaxed -- timestamp staleness of <1ms is acceptable; no happens-before needed.
    last_activity.fetch_max(now, Ordering::Relaxed);
}

pub(in crate::io_loop::udp_assoc) fn udp_association_is_idle(
    last_activity: &AtomicU64,
    idle_timeout: Duration,
) -> bool {
    activity_is_idle_at(last_activity, idle_timeout, now_millis())
}

pub(in crate::io_loop::udp_assoc) fn activity_is_idle_at(
    last_activity: &AtomicU64,
    idle_timeout: Duration,
    now: u64,
) -> bool {
    // Ordering: Relaxed -- timestamp staleness of <1ms is acceptable; no happens-before needed.
    now.saturating_sub(last_activity.load(Ordering::Relaxed)) >= idle_timeout.as_millis() as u64
}
