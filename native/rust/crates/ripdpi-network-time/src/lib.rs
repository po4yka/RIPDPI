#![forbid(unsafe_code)]

//! Shared **network-time provider** for replay-resistant outbound transports.
//!
//! Several outbound protocols derive a replay/anti-tamper key (or stamp a
//! freshness timestamp) from "now". Mieru rotates its AEAD key on a 2-minute
//! `timeSalt`; Shadowsocks SIP022 stamps a timestamp the peer checks against a
//! tolerance window. The security property both rely on is that *the value used
//! is the network's notion of time*, **not** the device wall clock — a phone
//! whose clock is wrong (or attacker-influenced) must not silently shift the
//! replay window.
//!
//! Historically each transport called [`std::time::SystemTime::now`] directly at
//! its relay-integration boundary, with a TODO to wire a "canonical network-time
//! source" that did not exist anywhere in the workspace. This crate is that
//! source. It is deliberately **bounded** (see *Residual risk* below): RIPDPI
//! ships no backend and must work fully offline, so there is no SNTP query to a
//! public NTP pool (that would be a remote dependency, forbidden by the project's
//! no-backend rule). Instead the provider is a *monotonic-from-trusted-anchor*
//! clock: it is calibrated from the timestamps the relay servers themselves emit
//! during a session (a source RIPDPI already talks to), and advances by a
//! monotonic counter so an in-session device-clock jump cannot drag the replay
//! window with it.
//!
//! # Model
//!
//! - Uncalibrated: [`NetworkTimeProvider::now_unix`] returns the device wall
//!   clock (the previous behaviour, now centralised and injectable).
//! - Calibrated via [`NetworkTimeProvider::calibrate`] (fed an `observed_unix`
//!   from a server's wire timestamp): `now = anchor_net_unix + monotonic_elapsed`.
//!   Once an anchor is set the device wall clock is no longer read, so a wrong or
//!   adjusted device clock cannot move the replay window.
//!
//! All transports share one process-wide instance via [`NetworkTimeProvider::shared`],
//! so a calibration observed on *any* transport (e.g. a Shadowsocks SIP022 UDP
//! reply) improves the replay clock for *all* of them (e.g. the next Mieru
//! handshake). Tests construct isolated providers with an injected [`Clock`].
//!
//! # Residual risk (documented, not deferred)
//!
//! - **First contact still uses the device clock.** Before any calibration the
//!   very first handshake derives its key from the device wall clock. Both Mieru
//!   (±~3 min skew tolerance from its 2-minute window probing) and Shadowsocks
//!   SIP022 (its timestamp tolerance) accept a moderately-wrong clock, so first
//!   contact succeeds unless the device clock is grossly off; thereafter the
//!   session's own server timestamps calibrate the provider for subsequent
//!   sessions.
//! - **Calibration precision is bounded by the wire source.** Mieru stamps time
//!   at *minute* granularity (±60 s); Shadowsocks SIP022 at *second* granularity.
//!   The provider stores whatever precision it is fed; both are far inside the
//!   protocols' tolerance windows.
//! - **Sleep time counts.** On Linux and Android the monotonic reading is
//!   `CLOCK_BOOTTIME`, which keeps running while the device is suspended
//!   (`CLOCK_MONOTONIC`, behind [`Instant`], stops). Other hosts use [`Instant`].
//! - **A wrong anchor recovers.** [`RECALIBRATION_VOTES`] consecutive rejected
//!   observations that agree with each other re-anchor the clock, so an
//!   estimate error past [`MAX_PLAUSIBLE_DRIFT_SECS`] cannot wedge it.
//! - **No SNTP.** A true external time source is intentionally not used — it
//!   would be a remote dependency, which the project's offline/no-backend rule
//!   forbids. The anchor comes only from servers the user already connects to.

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
}

/// The production clock: device wall clock + a process-monotonic [`Instant`].
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
        #[cfg(any(target_os = "linux", target_os = "android"))]
        if let Ok(now) = nix::time::clock_gettime(nix::time::ClockId::CLOCK_BOOTTIME) {
            let secs = u128::try_from(now.tv_sec()).unwrap_or(0);
            let nanos = u128::try_from(now.tv_nsec()).unwrap_or(0);
            return secs * 1_000_000_000 + nanos;
        }
        // CLOCK_BOOTTIME exists since Linux 2.6.39, so only other hosts reach this.
        static BASE: OnceLock<Instant> = OnceLock::new();
        let base = *BASE.get_or_init(Instant::now);
        base.elapsed().as_nanos()
    }
}

/// Maximum gap (seconds) between a new calibration observation and the already-
/// established network time before the observation is rejected as implausible.
/// 10 minutes comfortably exceeds real intra-session drift and both Mieru's
/// (~3 min) and Shadowsocks SIP022's (30 s) own freshness windows, while bounding
/// how far a rogue/misconfigured server can move the shared clock.
const MAX_PLAUSIBLE_DRIFT_SECS: u64 = 600;

/// Consecutive rejected observations, each within [`RECALIBRATION_AGREEMENT_SECS`]
/// of the previous one's offset, that re-anchor the clock. One rogue reply cannot
/// move the shared clock; a consistent stream of server times can.
const RECALIBRATION_VOTES: u8 = 3;

/// How closely rejected observations must agree to count as one vote stream.
/// Covers Mieru's minute-granularity stamps.
const RECALIBRATION_AGREEMENT_SECS: u64 = 120;

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

#[derive(Debug, Default)]
struct ClockState {
    anchor: Option<Anchor>,
    /// Offset from the estimate of the last rejected observation and the number
    /// of consecutive rejected observations that agreed with it.
    rejected: Option<(i64, u8)>,
}

/// A calibratable, monotonic-from-anchor network-time source. Cheap to share
/// behind an [`std::sync::Arc`]; all methods are `&self`.
pub struct NetworkTimeProvider {
    clock: Box<dyn Clock>,
    state: Mutex<ClockState>,
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
        Self { clock, state: Mutex::new(ClockState::default()) }
    }

    /// Build a provider over the production [`SystemClock`].
    #[must_use]
    pub fn system() -> Self {
        Self::with_clock(Box::new(SystemClock))
    }

    /// The process-wide shared provider. All outbound transports read and
    /// calibrate this single instance so a calibration on one improves the
    /// replay clock for all. Returned as an [`Arc`] so callers can move an owned
    /// handle into spawned tasks; every call returns a clone of the same inner.
    #[must_use]
    pub fn shared() -> Arc<Self> {
        static SHARED: OnceLock<Arc<NetworkTimeProvider>> = OnceLock::new();
        Arc::clone(SHARED.get_or_init(|| Arc::new(NetworkTimeProvider::system())))
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

    /// Network time as Unix seconds, clamped to a non-negative `u64` (the shape
    /// Shadowsocks SIP022 stamps on the wire).
    #[must_use]
    pub fn now_unix_u64(&self) -> u64 {
        u64::try_from(self.now_unix()).unwrap_or(0)
    }

    /// Calibrate the anchor to a server-observed `observed_network_unix`,
    /// captured against the current monotonic reading. Returns whether the
    /// observation was accepted.
    ///
    /// The **first** observation is always accepted (bootstrap — it may correct a
    /// grossly-wrong device clock). Once anchored, an observation that disagrees
    /// with the established network time by more than [`MAX_PLAUSIBLE_DRIFT_SECS`]
    /// is **rejected**, so a single misconfigured or hostile (but key-holding)
    /// server cannot yank the process-wide clock — and thus the replay window of
    /// *other* transports sharing this provider — to an arbitrary value. Real
    /// drift over a session is far inside this window; in-window observations
    /// re-anchor (refining toward the freshest server time). If the estimate
    /// itself is wrong, [`RECALIBRATION_VOTES`] consecutive rejected observations
    /// that agree with each other re-anchor it.
    pub fn calibrate(&self, observed_network_unix: i64) -> bool {
        let mono = self.clock.monotonic_nanos();
        let mut guard = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        // Compute the current estimate under the same lock (calling `now_unix`
        // here would re-lock the non-reentrant mutex and deadlock).
        if let Some(anchor) = guard.anchor {
            let offset = observed_network_unix.saturating_sub(anchor.estimate(mono));
            if offset.unsigned_abs() > MAX_PLAUSIBLE_DRIFT_SECS {
                let votes = match guard.rejected {
                    Some((previous, votes))
                        if offset.saturating_sub(previous).unsigned_abs() <= RECALIBRATION_AGREEMENT_SECS =>
                    {
                        votes.saturating_add(1)
                    }
                    _ => 1,
                };
                if votes < RECALIBRATION_VOTES {
                    guard.rejected = Some((offset, votes));
                    return false;
                }
            }
        }
        guard.anchor = Some(Anchor { net_unix: observed_network_unix, mono_nanos: mono });
        guard.rejected = None;
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
        // Server says it is 1_700_000_000 right now.
        provider.calibrate(1_700_000_000);
        assert!(provider.is_calibrated());
        assert_eq!(provider.now_unix(), 1_700_000_000);

        // A device clock stepped back to a wildly wrong value must not move network time.
        clock.set_wall(42);
        assert_eq!(provider.now_unix(), 1_700_000_000, "calibrated time ignores the device wall clock");

        // Monotonic progress advances network time second-for-second.
        clock.advance_secs(30);
        assert_eq!(provider.now_unix(), 1_700_000_030);
    }

    #[test]
    fn calibration_rejects_implausible_drift_after_anchoring() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        // First observation always accepted (bootstrap — may correct a wrong clock).
        assert!(provider.calibrate(1_000_000_000));
        assert_eq!(provider.now_unix(), 1_000_000_000);
        // A wild jump from a rogue/misconfigured server (> MAX_PLAUSIBLE_DRIFT) is
        // rejected and must not move the shared clock.
        assert!(!provider.calibrate(1_000_000_000 + 3_600));
        assert_eq!(provider.now_unix(), 1_000_000_000, "implausible calibration must not move the clock");
        // A small in-window correction re-anchors.
        assert!(provider.calibrate(1_000_000_010));
        assert_eq!(provider.now_unix(), 1_000_000_010);
    }

    #[test]
    fn recalibration_reanchors_to_latest_observation() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        provider.calibrate(1_000);
        clock.advance_secs(10);
        assert_eq!(provider.now_unix(), 1_010);
        // A fresh, in-window observation re-anchors from the new monotonic point.
        provider.calibrate(1_400);
        assert_eq!(provider.now_unix(), 1_400);
        clock.advance_secs(5);
        assert_eq!(provider.now_unix(), 1_405);
    }

    #[test]
    fn wall_clock_step_after_calibration_does_not_move_network_time() {
        // The device clock is an hour slow; the server corrects it.
        let clock = std::sync::Arc::new(TestClock::new(1_700_000_000));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(1_700_003_600));
        clock.advance_secs(10);
        // Automatic time then steps the device clock forward to the right value.
        clock.set_wall(1_700_003_610);
        assert_eq!(provider.now_unix(), 1_700_003_610, "a wall-clock step must not move network time");
        assert!(provider.calibrate(1_700_003_611), "honest recalibration stays inside the drift window");
        assert_eq!(provider.now_unix(), 1_700_003_611);
    }

    #[test]
    fn consistent_rejected_observations_reanchor_a_wrong_estimate() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(1_000_000));
        // The estimate is now two hours behind the servers, which keep reporting the same time.
        assert!(!provider.calibrate(1_007_200));
        clock.advance_secs(30);
        assert!(!provider.calibrate(1_007_230));
        assert_eq!(provider.now_unix(), 1_000_030, "two votes must not move the clock");
        clock.advance_secs(30);
        assert!(provider.calibrate(1_007_290), "the third consistent observation re-anchors");
        assert_eq!(provider.now_unix(), 1_007_290);
    }

    #[test]
    fn inconsistent_rejected_observations_do_not_reanchor() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(1_000_000));
        for observed in [1_007_200, 1_003_600, 1_010_800, 1_007_200, 1_003_600] {
            assert!(!provider.calibrate(observed));
        }
        // An accepted in-window observation also clears pending votes.
        assert!(provider.calibrate(1_000_005));
        assert!(!provider.calibrate(1_007_200));
        assert!(!provider.calibrate(1_007_200));
        assert_eq!(provider.now_unix(), 1_000_005);
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

    #[test]
    fn shared_is_a_single_instance() {
        let a = NetworkTimeProvider::shared();
        let b = NetworkTimeProvider::shared();
        assert!(Arc::ptr_eq(&a, &b), "shared() must return one process-wide instance");
    }
}
