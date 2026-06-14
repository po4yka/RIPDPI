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
        static BASE: OnceLock<Instant> = OnceLock::new();
        let base = *BASE.get_or_init(Instant::now);
        base.elapsed().as_nanos()
    }
}

#[derive(Debug, Clone, Copy)]
struct Anchor {
    /// Network Unix seconds at the moment of calibration.
    net_unix: i64,
    /// Monotonic reading captured at the same moment.
    mono_nanos: u128,
}

/// A calibratable, monotonic-from-anchor network-time source. Cheap to share
/// behind an [`std::sync::Arc`]; all methods are `&self`.
pub struct NetworkTimeProvider {
    clock: Box<dyn Clock>,
    anchor: Mutex<Option<Anchor>>,
}

impl std::fmt::Debug for NetworkTimeProvider {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let calibrated = self.anchor.lock().is_ok_and(|a| a.is_some());
        f.debug_struct("NetworkTimeProvider").field("calibrated", &calibrated).finish_non_exhaustive()
    }
}

impl NetworkTimeProvider {
    /// Build a provider over a custom [`Clock`] (used by tests).
    #[must_use]
    pub fn with_clock(clock: Box<dyn Clock>) -> Self {
        Self { clock, anchor: Mutex::new(None) }
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
        let guard = self.anchor.lock().unwrap_or_else(PoisonError::into_inner);
        match *guard {
            Some(anchor) => {
                let elapsed_nanos = self.clock.monotonic_nanos().saturating_sub(anchor.mono_nanos);
                let elapsed_secs = i64::try_from(elapsed_nanos / 1_000_000_000).unwrap_or(i64::MAX);
                anchor.net_unix.saturating_add(elapsed_secs)
            }
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
    /// captured against the current monotonic reading. The latest trusted
    /// observation wins (re-anchoring is cheap and handshakes are infrequent).
    pub fn calibrate(&self, observed_network_unix: i64) {
        let mono = self.clock.monotonic_nanos();
        let mut guard = self.anchor.lock().unwrap_or_else(PoisonError::into_inner);
        *guard = Some(Anchor { net_unix: observed_network_unix, mono_nanos: mono });
    }

    /// Whether an anchor has been set (the replay clock is network-derived).
    #[must_use]
    pub fn is_calibrated(&self) -> bool {
        self.anchor.lock().unwrap_or_else(PoisonError::into_inner).is_some()
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
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        // Server says it is 1_700_000_000 right now.
        provider.calibrate(1_700_000_000);
        assert!(provider.is_calibrated());
        assert_eq!(provider.now_unix(), 1_700_000_000);

        // A wildly wrong device clock must not move network time.
        clock.set_wall(42);
        assert_eq!(provider.now_unix(), 1_700_000_000, "calibrated time ignores the device wall clock");

        // Monotonic progress advances network time second-for-second.
        clock.advance_secs(30);
        assert_eq!(provider.now_unix(), 1_700_000_030);
    }

    #[test]
    fn recalibration_reanchors_to_latest_observation() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        provider.calibrate(1_000);
        clock.advance_secs(10);
        assert_eq!(provider.now_unix(), 1_010);
        // A fresh, corrected observation re-anchors from the new monotonic point.
        provider.calibrate(2_000);
        assert_eq!(provider.now_unix(), 2_000);
        clock.advance_secs(5);
        assert_eq!(provider.now_unix(), 2_005);
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
