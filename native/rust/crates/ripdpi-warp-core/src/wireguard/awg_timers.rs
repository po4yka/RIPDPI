//! Lock-free timestamps for the AWG 3.x timers that run beside boringtun:
//! `RekeyAfterTime` and the `PersistentKeepalive` range.

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use crate::amneziawg::rand_u32;

/// `rekeyMinTimeout` (`send.go:112-118`): no early rekey within this time of the last initiation.
pub(super) const REKEY_MIN_INTERVAL: Duration = Duration::from_secs(5);

/// Milliseconds since `origin`, plus one; `0` means "never".
///
/// Relaxed ordering on every stamp: each is an independent timing heuristic and
/// publishes no other memory, so no happens-before edge is needed.
pub(super) struct AwgTimers {
    origin: Instant,
    last_traffic: AtomicU64,
    last_initiation: AtomicU64,
    session_started: AtomicU64,
}

impl AwgTimers {
    pub(super) fn new() -> Self {
        Self {
            origin: Instant::now(),
            last_traffic: AtomicU64::new(0),
            last_initiation: AtomicU64::new(0),
            session_started: AtomicU64::new(0),
        }
    }

    fn now(&self) -> u64 {
        u64::try_from(self.origin.elapsed().as_millis()).unwrap_or(u64::MAX - 1) + 1
    }

    fn since(&self, stamp: &AtomicU64) -> Option<Duration> {
        match stamp.load(Ordering::Relaxed) {
            0 => None,
            at => Some(Duration::from_millis(self.now().saturating_sub(at))),
        }
    }

    /// An authenticated packet was sent or received (`timersAnyAuthenticatedPacketTraversal`).
    pub(super) fn mark_traffic(&self) {
        self.last_traffic.store(self.now(), Ordering::Relaxed);
    }

    pub(super) fn mark_initiation(&self) {
        self.last_initiation.store(self.now(), Ordering::Relaxed);
    }

    pub(super) fn mark_session(&self) {
        self.session_started.store(self.now(), Ordering::Relaxed);
    }

    /// The stamp of the last authenticated packet; a change re-arms the keepalive timer.
    pub(super) fn traffic_stamp(&self) -> u64 {
        self.last_traffic.load(Ordering::Relaxed)
    }

    /// Cheap pre-check for an early rekey: our session is older than `limit`
    /// and no initiation went out in the last [`REKEY_MIN_INTERVAL`].
    pub(super) fn early_rekey_due(&self, limit: Duration) -> bool {
        self.since(&self.session_started).is_some_and(|age| age > limit)
            && self.since(&self.last_initiation).is_none_or(|elapsed| elapsed >= REKEY_MIN_INTERVAL)
    }
}

/// A uniform draw from `[low, high]` seconds (`UintRange.PickOne`).
pub(super) fn keepalive_interval((low, high): (u16, u16)) -> Duration {
    let span = u32::from(high - low) + 1;
    Duration::from_secs(u64::from(u32::from(low) + rand_u32() % span))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn early_rekey_needs_an_old_session_and_a_quiet_initiation_window() {
        let timers = AwgTimers::new();
        assert!(!timers.early_rekey_due(Duration::ZERO), "no session yet");
        timers.mark_session();
        std::thread::sleep(Duration::from_millis(5));
        assert!(timers.early_rekey_due(Duration::from_millis(1)));
        assert!(!timers.early_rekey_due(Duration::from_secs(60)), "session younger than the limit");
        timers.mark_initiation();
        assert!(!timers.early_rekey_due(Duration::from_millis(1)), "rate limited for 5 s after an initiation");
    }

    #[test]
    fn traffic_changes_the_stamp_and_keepalive_draws_stay_in_range() {
        let timers = AwgTimers::new();
        assert_eq!(timers.traffic_stamp(), 0);
        timers.mark_traffic();
        let first = timers.traffic_stamp();
        assert_ne!(first, 0);
        std::thread::sleep(Duration::from_millis(3));
        timers.mark_traffic();
        assert_ne!(timers.traffic_stamp(), first, "new traffic must re-arm the keepalive");
        for _ in 0..64 {
            let interval = keepalive_interval((25, 35));
            assert!((Duration::from_secs(25)..=Duration::from_secs(35)).contains(&interval), "{interval:?}");
        }
    }
}
