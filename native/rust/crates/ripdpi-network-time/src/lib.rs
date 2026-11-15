#![forbid(unsafe_code)]

//! Network time for replay-resistant outbound relays.
//!
//! Each relay factory shares one provider with its connections. Until the
//! first accepted observation it reads the device wall clock. A calibrated
//! provider advances its anchor with the monotonic clock.
//!
//! Callers submit only authenticated, fresh timestamps bound to a request or
//! association. Inside the observation's uncertainty the estimate stays
//! unchanged. Larger adjustments are bounded relative to the first anchor.
//! Restart the relay after correcting the device clock when the error exceeds
//! the protocol's wire tolerance.
//!
//! A UDP association can pin a timeline so a backward correction does not
//! reopen the freshness window of an already retired replay filter.
//!
//! Android and Linux normally use BOOTTIME so suspend counts. If selection
//! starts with a failed BOOTTIME read, Instant remains the source until process
//! restart. After BOOTTIME is selected, a failed read holds the last tick.
//! New anchors require a current reading.

use std::sync::{Arc, Mutex, OnceLock, PoisonError};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

/// An injectable source of the two clocks the provider needs.
///
/// `wall_unix_secs` is the (untrusted) device wall clock; `monotonic_nanos` is a
/// never-decreasing counter used to advance time from a calibrated anchor without
/// re-reading the wall clock. Production uses [`SystemClock`]; tests inject a
/// deterministic fake.
pub trait Clock: Send + Sync + 'static {
    /// Device wall-clock time in Unix seconds. May be wrong or adjusted.
    fn wall_unix_secs(&self) -> i64;

    /// Monotonic nanoseconds since an arbitrary, fixed, process-stable epoch.
    /// Must never decrease across calls.
    fn monotonic_nanos(&self) -> u128;

    /// A current tick for a new anchor, if the monotonic source can be read.
    fn capture_nanos(&self) -> Option<u128> {
        Some(self.monotonic_nanos())
    }
}

/// The device wall clock and a process-stable monotonic source.
#[derive(Debug, Default, Clone, Copy)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn wall_unix_secs(&self) -> i64 {
        match SystemTime::now().duration_since(UNIX_EPOCH) {
            Ok(d) => i64::try_from(d.as_secs()).unwrap_or(i64::MAX),
            // Pre-1970 device clock: treat as 0 rather than panic.
            Err(_) => 0,
        }
    }

    fn monotonic_nanos(&self) -> u128 {
        MonotonicClock::system().read().0
    }

    fn capture_nanos(&self) -> Option<u128> {
        MonotonicClock::system().read().1
    }
}

enum MonotonicClock {
    Instant(Instant),
    #[cfg(any(target_os = "linux", target_os = "android"))]
    BootTime(BootReading),
}

impl MonotonicClock {
    fn system() -> &'static Self {
        static CLOCK: OnceLock<MonotonicClock> = OnceLock::new();
        CLOCK.get_or_init(Self::new)
    }

    fn new() -> Self {
        #[cfg(any(target_os = "linux", target_os = "android"))]
        if let Some(initial) = boot_time_nanos() {
            return Self::BootTime(BootReading(Mutex::new(initial)));
        }
        Self::Instant(Instant::now())
    }

    fn read(&self) -> (u128, Option<u128>) {
        match self {
            Self::Instant(base) => {
                let tick = base.elapsed().as_nanos();
                (tick, Some(tick))
            }
            #[cfg(any(target_os = "linux", target_os = "android"))]
            Self::BootTime(previous) => {
                let sample = boot_time_nanos();
                (previous.keep_latest(sample), sample)
            }
        }
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn boot_time_nanos() -> Option<u128> {
    let now = nix::time::clock_gettime(nix::time::ClockId::CLOCK_BOOTTIME).ok()?;
    let secs = u128::try_from(now.tv_sec()).ok()?;
    let nanos = u128::try_from(now.tv_nsec()).ok()?;
    Some(secs * 1_000_000_000 + nanos)
}

#[cfg(any(target_os = "linux", target_os = "android", test))]
struct BootReading(Mutex<u128>);

#[cfg(any(target_os = "linux", target_os = "android", test))]
impl BootReading {
    fn keep_latest(&self, sample: Option<u128>) -> u128 {
        let mut previous = self.0.lock().unwrap_or_else(PoisonError::into_inner);
        // An error must not switch to a different monotonic epoch.
        if let Some(sample) = sample {
            *previous = (*previous).max(sample);
        }
        *previous
    }
}

/// Maximum gap (seconds) between a new calibration observation and the already-
/// established network time before the observation is rejected as implausible.
/// 10 minutes comfortably exceeds real intra-session drift and both Mieru's
/// (~3 min) and Shadowsocks SIP022's (30 s) own freshness windows, while bounding
/// how far incorrect observations can move the relay clock.
const MAX_PLAUSIBLE_DRIFT_SECS: u64 = 600;

/// Earliest plausible network time (2023-01-01T00:00:00Z). An observation before
/// it is rejected, even as the first anchor, so a zero or garbage stamp cannot
/// set the relay clock.
const MIN_PLAUSIBLE_UNIX_SECS: i64 = 1_672_531_200;

/// Latest second representable by the supported minute-based wire timestamps.
const MAX_PLAUSIBLE_UNIX_SECS: i64 = u32::MAX as i64 * 60 + 59;

#[derive(Debug, Clone, Copy)]
struct Anchor {
    /// Network Unix seconds at the moment of calibration.
    net_unix: i64,
    /// Monotonic reading captured at the same moment.
    mono_nanos: u128,
}

impl Anchor {
    fn estimate(&self, mono_nanos: u128) -> i64 {
        let elapsed_nanos = mono_nanos.saturating_sub(self.mono_nanos);
        let elapsed_secs = i64::try_from(elapsed_nanos / 1_000_000_000).unwrap_or(i64::MAX);
        self.net_unix.saturating_add(elapsed_secs)
    }
}

fn sample_wall(clock: &dyn Clock) -> Option<Anchor> {
    for _ in 0..2 {
        let before = clock.capture_nanos()?;
        let net_unix = clock.wall_unix_secs();
        let mono_nanos = clock.capture_nanos()?;
        // A suspend or long deschedule can separate the readings.
        if mono_nanos.checked_sub(before).is_some_and(|elapsed| elapsed < 1_000_000_000) {
            return Some(Anchor { net_unix, mono_nanos });
        }
    }
    None
}

#[derive(Debug, Default)]
struct ClockState {
    anchor: Option<Anchor>,
    /// Corrections cannot move time more than the drift bound from the first anchor.
    reference: Option<Anchor>,
}

/// A calibratable, monotonic-from-anchor network-time source. Cheap to share
/// behind an [`std::sync::Arc`]; all methods are `&self`.
pub struct NetworkTimeProvider {
    clock: Arc<dyn Clock>,
    state: Mutex<ClockState>,
}

/// A monotonic time source fixed to the provider's estimate at capture.
/// Later provider calibrations and wall-clock changes do not affect this timeline.
pub struct NetworkTimeSnapshot {
    clock: Arc<dyn Clock>,
    anchor: Anchor,
}

impl NetworkTimeSnapshot {
    /// Current snapshot time as non-negative Unix seconds.
    #[must_use]
    pub fn now_unix_u64(&self) -> u64 {
        u64::try_from(self.anchor.estimate(self.clock.monotonic_nanos())).unwrap_or(0)
    }
}

impl std::fmt::Debug for NetworkTimeProvider {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let calibrated = self.state.lock().is_ok_and(|state| state.anchor.is_some());
        f.debug_struct("NetworkTimeProvider").field("calibrated", &calibrated).finish_non_exhaustive()
    }
}

impl NetworkTimeProvider {
    /// Build a provider over a custom [`Clock`] (used by tests).
    #[must_use]
    pub fn with_clock(clock: Box<dyn Clock>) -> Self {
        Self { clock: Arc::from(clock), state: Mutex::new(ClockState::default()) }
    }

    /// Build a provider over the production [`SystemClock`].
    #[must_use]
    pub fn system() -> Self {
        Self::with_clock(Box::new(SystemClock))
    }

    /// A provider pinned to a fixed Unix time (deterministic tests). Returns
    /// `fixed` until/unless [`calibrate`](Self::calibrate) re-anchors it.
    #[must_use]
    pub fn fixed(unix_secs: i64) -> Self {
        Self::with_clock(Box::new(FixedClock(unix_secs)))
    }

    /// Network time as Unix seconds. Calibrated: `anchor + monotonic_elapsed`,
    /// never re-reading the device clock. Uncalibrated: the device wall clock.
    #[must_use]
    pub fn now_unix(&self) -> i64 {
        let guard = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        match guard.anchor {
            Some(anchor) => anchor.estimate(self.clock.monotonic_nanos()),
            None => self.clock.wall_unix_secs(),
        }
    }

    /// Pin one timeline for timestamp freshness and replay age. Returns `None`
    /// if an uncalibrated clock cannot pair the wall time with a current tick.
    #[must_use]
    pub fn snapshot(&self) -> Option<NetworkTimeSnapshot> {
        let guard = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        let anchor = guard.anchor.or_else(|| sample_wall(self.clock.as_ref()))?;
        Some(NetworkTimeSnapshot { clock: Arc::clone(&self.clock), anchor })
    }

    /// Network time as Unix seconds, clamped to a non-negative `u64` (the shape
    /// Shadowsocks SIP022 stamps on the wire).
    #[must_use]
    pub fn now_unix_u64(&self) -> u64 {
        u64::try_from(self.now_unix()).unwrap_or(0)
    }

    /// Calibrate from a validated observation for this relay. The uncertainty
    /// describes the timestamp's quantization. Returns whether it was accepted.
    pub fn calibrate(&self, observed_network_unix: i64, uncertainty_secs: u64) -> bool {
        if !(MIN_PLAUSIBLE_UNIX_SECS..=MAX_PLAUSIBLE_UNIX_SECS).contains(&observed_network_unix) {
            return false;
        }
        let Some(mono) = self.clock.capture_nanos() else { return false };
        let observation = Anchor { net_unix: observed_network_unix, mono_nanos: mono };
        let mut guard = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        // Compute the current estimate under the same lock (calling `now_unix`
        // here would re-lock the non-reentrant mutex and deadlock).
        if let Some(anchor) = guard.anchor {
            let offset = observed_network_unix.saturating_sub(anchor.estimate(mono));
            let from_reference = guard
                .reference
                .map_or(offset, |reference| observed_network_unix.saturating_sub(reference.estimate(mono)));
            if offset.unsigned_abs() > MAX_PLAUSIBLE_DRIFT_SECS
                || from_reference.unsigned_abs() > MAX_PLAUSIBLE_DRIFT_SECS
            {
                return false;
            } else if offset.unsigned_abs() <= uncertainty_secs {
                return true;
            }
        } else {
            // Bootstrap. A device clock inside the observation's error bound is an
            // equally valid estimate and is finer than a coarse stamp, so keep it.
            let wall = self.clock.wall_unix_secs();
            let observation = if (MIN_PLAUSIBLE_UNIX_SECS..=MAX_PLAUSIBLE_UNIX_SECS).contains(&wall)
                && wall.abs_diff(observed_network_unix) <= uncertainty_secs
            {
                Anchor { net_unix: wall, mono_nanos: mono }
            } else {
                observation
            };
            guard.reference = Some(observation);
            guard.anchor = Some(observation);
            return true;
        }
        guard.anchor = Some(observation);
        true
    }

    /// Whether an anchor has been set (the replay clock is network-derived).
    #[must_use]
    pub fn is_calibrated(&self) -> bool {
        self.state.lock().unwrap_or_else(PoisonError::into_inner).anchor.is_some()
    }
}

impl Default for NetworkTimeProvider {
    fn default() -> Self {
        Self::system()
    }
}

/// A [`Clock`] frozen at a fixed wall time and zero monotonic — backs
/// [`NetworkTimeProvider::fixed`].
struct FixedClock(i64);

impl Clock for FixedClock {
    fn wall_unix_secs(&self) -> i64 {
        self.0
    }

    fn monotonic_nanos(&self) -> u128 {
        0
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};

    use super::*;

    /// Added to every network time in these tests so they pass the plausibility floor.
    const T0: i64 = MIN_PLAUSIBLE_UNIX_SECS;

    /// A deterministic clock whose wall and monotonic values are set by the test.
    struct TestClock {
        wall: AtomicI64,
        mono_nanos: AtomicU64,
    }

    impl TestClock {
        fn new(wall: i64) -> Self {
            Self { wall: AtomicI64::new(wall), mono_nanos: AtomicU64::new(0) }
        }

        fn set_wall(&self, wall: i64) {
            self.wall.store(wall, Ordering::SeqCst);
        }

        fn advance_secs(&self, secs: u64) {
            self.mono_nanos.fetch_add(secs * 1_000_000_000, Ordering::SeqCst);
        }
    }

    impl Clock for std::sync::Arc<TestClock> {
        fn wall_unix_secs(&self) -> i64 {
            self.wall.load(Ordering::SeqCst)
        }

        fn monotonic_nanos(&self) -> u128 {
            u128::from(self.mono_nanos.load(Ordering::SeqCst))
        }
    }

    #[test]
    fn coarse_bootstrap_keeps_an_agreeing_device_clock() {
        // A minute-granular source (midpoint estimate, uncertainty 30) that lands 21 s
        // behind an accurate device clock must not drag the anchor back.
        let wall = T0 + 1_000;
        let clock = std::sync::Arc::new(TestClock::new(wall));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock));
        assert!(provider.calibrate(wall - 21, 30));
        assert!(provider.is_calibrated());
        assert!((provider.now_unix() - wall).abs() <= 1, "anchor stays on the accurate wall clock");
    }

    #[test]
    fn coarse_bootstrap_follows_observation_when_device_clock_is_skewed() {
        let wall = T0 + 1_000;
        let clock = std::sync::Arc::new(TestClock::new(wall + 90));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock));
        assert!(provider.calibrate(wall, 30));
        assert_eq!(provider.now_unix(), wall, "a skewed device clock does not win");
    }

    #[test]
    fn uncalibrated_returns_device_wall_clock() {
        let clock = std::sync::Arc::new(TestClock::new(1_700_000_000));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(!provider.is_calibrated());
        assert_eq!(provider.now_unix(), 1_700_000_000);
        clock.set_wall(1_700_000_500);
        assert_eq!(provider.now_unix(), 1_700_000_500, "uncalibrated tracks device clock");
    }

    #[test]
    fn calibrated_ignores_device_clock_and_advances_monotonically() {
        let clock = std::sync::Arc::new(TestClock::new(1_000_000));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        // Server says it is T0 right now.
        provider.calibrate(T0, 0);
        assert!(provider.is_calibrated());
        assert_eq!(provider.now_unix(), T0);

        // A device clock stepped back to a wildly wrong value must not move network time.
        clock.set_wall(42);
        assert_eq!(provider.now_unix(), T0, "calibrated time ignores the device wall clock");

        // Monotonic progress advances network time second-for-second.
        clock.advance_secs(30);
        assert_eq!(provider.now_unix(), T0 + 30);
    }

    #[test]
    fn calibration_rejects_implausible_drift_after_anchoring() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        // First observation always accepted (bootstrap — may correct a wrong clock).
        assert!(provider.calibrate(T0 + 1_000_000_000, 0));
        assert_eq!(provider.now_unix(), T0 + 1_000_000_000);
        // A wild jump from a rogue/misconfigured server (> MAX_PLAUSIBLE_DRIFT) is
        // rejected and must not move the relay clock.
        assert!(!provider.calibrate(T0 + 1_000_000_000 + 3_600, 0));
        assert_eq!(provider.now_unix(), T0 + 1_000_000_000, "implausible calibration must not move the clock");
        // A small in-window correction re-anchors.
        assert!(provider.calibrate(T0 + 1_000_000_010, 0));
        assert_eq!(provider.now_unix(), T0 + 1_000_000_010);
    }

    #[test]
    fn recalibration_reanchors_to_latest_observation() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        provider.calibrate(T0 + 1_000, 0);
        clock.advance_secs(10);
        assert_eq!(provider.now_unix(), T0 + 1_010);
        // A fresh, in-window observation re-anchors from the new monotonic point.
        provider.calibrate(T0 + 1_400, 0);
        assert_eq!(provider.now_unix(), T0 + 1_400);
        clock.advance_secs(5);
        assert_eq!(provider.now_unix(), T0 + 1_405);
    }

    #[test]
    fn wall_clock_step_after_calibration_does_not_move_network_time() {
        // The device clock is an hour slow; the server corrects it.
        let clock = std::sync::Arc::new(TestClock::new(T0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(T0 + 3_600, 0));
        clock.advance_secs(10);
        // Automatic time then steps the device clock forward to the right value.
        clock.set_wall(T0 + 3_610);
        assert_eq!(provider.now_unix(), T0 + 3_610, "a wall-clock step must not move network time");
        assert!(provider.calibrate(T0 + 3_611, 0), "honest recalibration stays inside the drift window");
        assert_eq!(provider.now_unix(), T0 + 3_611);
    }

    #[test]
    fn one_source_repeating_a_wrong_time_never_moves_the_clock() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(T0 + 1_000_000, 0));
        for _ in 0..10 {
            clock.advance_secs(10);
            assert!(!provider.calibrate(provider.now_unix() + 7_200, 0));
        }
        assert_eq!(provider.now_unix(), T0 + 1_000_100);
    }

    #[test]
    fn live_snapshot_keeps_its_timeline_after_backward_calibration() {
        let wall = T0 + 1_000;
        let clock = std::sync::Arc::new(TestClock::new(wall));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        let snapshot = provider.snapshot().expect("current tick");

        clock.advance_secs(15);
        clock.set_wall(wall - 20);
        assert!(provider.calibrate(wall - 20, 0));
        assert_eq!(provider.now_unix(), wall - 20);
        assert_eq!(snapshot.now_unix_u64(), u64::try_from(wall + 15).unwrap());

        clock.advance_secs(1);
        assert_eq!(snapshot.now_unix_u64(), u64::try_from(wall + 16).unwrap());
    }

    #[test]
    fn coarse_source_within_its_uncertainty_does_not_move_the_clock() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(T0 + 1_000_000, 0));
        let mieru = 30;
        // A minute-granular stamp 25 s behind is consistent: no backward step.
        assert!(provider.calibrate(T0 + 999_975, mieru));
        assert_eq!(provider.now_unix(), T0 + 1_000_000);
        // 45 s behind is outside its uncertainty: a real correction, stepping backwards.
        assert!(provider.calibrate(T0 + 999_955, mieru));
        assert_eq!(provider.now_unix(), T0 + 999_955);
    }

    #[test]
    fn calibration_rejects_observations_before_the_plausibility_floor() {
        let provider = NetworkTimeProvider::with_clock(Box::new(std::sync::Arc::new(TestClock::new(T0))));
        assert!(!provider.calibrate(0, 0), "a zero timestamp must not become the first anchor");
        assert!(!provider.calibrate(MIN_PLAUSIBLE_UNIX_SECS - 1, 0));
        assert!(!provider.is_calibrated());
        assert!(provider.calibrate(MIN_PLAUSIBLE_UNIX_SECS, 0));
    }

    #[test]
    fn repeated_out_of_range_observations_keep_the_first_reference() {
        let wall = T0 + 1_000;
        let time = NetworkTimeProvider::fixed(wall);
        assert!(time.calibrate(wall, 1));
        assert!(!time.calibrate(wall + 601, 1));
        assert!(!time.calibrate(wall + 620, 30));
        assert_eq!(time.now_unix(), wall);
    }

    #[test]
    fn one_source_cannot_walk_the_clock_in_steps() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(T0, 0));
        // Each step is inside the drift window of the previous anchor...
        assert!(provider.calibrate(T0 + 500, 0));
        // ...but the total distance from the trusted anchor is bounded.
        assert!(!provider.calibrate(T0 + 1_000, 0));
        assert!(!provider.calibrate(T0 + 1_500, 0));
        assert_eq!(provider.now_unix(), T0 + 500);
    }

    #[test]
    fn bootstrap_rejects_unrepresentable_times_and_keeps_anchor_in_range() {
        let provider = NetworkTimeProvider::fixed(T0 - 1);
        let coarse = 30;
        assert!(!provider.calibrate(i64::MAX, coarse));
        assert!(!provider.is_calibrated());
        assert!(provider.calibrate(T0, coarse));
        assert_eq!(provider.now_unix(), T0);
    }

    #[test]
    fn a_frozen_tick_cannot_start_a_fresh_timeline() {
        struct FrozenClock;
        impl Clock for FrozenClock {
            fn wall_unix_secs(&self) -> i64 {
                T0 + 1_000
            }

            fn monotonic_nanos(&self) -> u128 {
                42
            }

            fn capture_nanos(&self) -> Option<u128> {
                None
            }
        }

        let time = NetworkTimeProvider::with_clock(Box::new(FrozenClock));
        assert_eq!(time.now_unix(), T0 + 1_000);
        assert!(time.snapshot().is_none());
        assert!(!time.calibrate(T0 + 1_000, 0));
        assert!(!time.is_calibrated());
    }

    #[test]
    fn boot_read_failure_preserves_epoch_and_last_tick() {
        let boot = BootReading(Mutex::new(1_000_000_000));
        assert_eq!(boot.keep_latest(None), 1_000_000_000);
        assert_eq!(boot.keep_latest(Some(500_000_000)), 1_000_000_000);
        assert_eq!(boot.keep_latest(Some(2_000_000_000)), 2_000_000_000);
    }

    #[test]
    fn system_clock_monotonic_reading_never_decreases() {
        let first = SystemClock.monotonic_nanos();
        assert!(SystemClock.monotonic_nanos() >= first);
    }

    #[test]
    fn fixed_provider_is_stable_for_handshake_determinism() {
        let provider = NetworkTimeProvider::fixed(1_700_000_000);
        assert_eq!(provider.now_unix(), 1_700_000_000);
        assert_eq!(provider.now_unix(), 1_700_000_000, "fixed time does not drift");
    }

    #[test]
    fn now_unix_u64_clamps_negative_to_zero() {
        let provider = NetworkTimeProvider::fixed(-5);
        assert_eq!(provider.now_unix_u64(), 0);
    }
}
