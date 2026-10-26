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
//!   at *minute* granularity (the midpoint estimate is within ±30 s); Shadowsocks
//!   SIP022 at *second* granularity. Each [`TimeSource`] states that uncertainty,
//!   and an in-window observation that agrees with the estimate within it does
//!   not move the clock, so a coarse source cannot degrade a fine anchor.
//! - **Network time can step backwards.** An in-window observation that is
//!   behind the estimate by more than its source's uncertainty re-anchors the
//!   clock backwards by that amount (at most [`MAX_PLAUSIBLE_DRIFT_SECS`], or a
//!   voted correction). This keeps replay windows correct: SIP022 (±30 s), VMess
//!   (±120 s) and Mieru (±1 min) check that a stamp is close to the server's
//!   clock, not that stamps increase, so the step moves stamps toward the server.
//! - **Sleep time counts.** On Linux and Android the monotonic reading is
//!   `CLOCK_BOOTTIME`, which keeps running while the device is suspended
//!   (`CLOCK_MONOTONIC`, behind [`Instant`], stops). Other hosts use [`Instant`].
//! - **One source moves the clock a bounded distance.** In-window corrections
//!   re-anchor, but never further than [`MAX_PLAUSIBLE_DRIFT_SECS`] from the
//!   reference (the first anchor or the last voted one), so repeated small steps
//!   from one server cannot walk the shared clock away. Stamps before
//!   [`MIN_PLAUSIBLE_UNIX_SECS`] are never accepted. A wrong *first* anchor is
//!   still accepted and needs a vote of distinct sources to correct.
//! - **A wrong anchor recovers.** Rejected observations from at least
//!   [`RECALIBRATION_SOURCES`] distinct sources that agree with the first such
//!   observation, within [`RECALIBRATION_WINDOW_SECS`], re-anchor the clock, so an
//!   estimate error past [`MAX_PLAUSIBLE_DRIFT_SECS`] cannot wedge it. One server,
//!   however often it repeats a wrong time, cannot move the shared clock.
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

/// Earliest plausible network time (2023-01-01T00:00:00Z). An observation before
/// it is rejected, even as the first anchor, so a zero or garbage stamp cannot
/// set the shared clock.
const MIN_PLAUSIBLE_UNIX_SECS: i64 = 1_672_531_200;

/// Distinct sources whose rejected observations must agree before they re-anchor
/// the clock. One rogue or misconfigured server cannot move it alone.
const RECALIBRATION_SOURCES: usize = 2;

/// How closely a rejected observation's offset must match the first vote's offset.
/// Covers Mieru's minute-granularity stamps.
const RECALIBRATION_AGREEMENT_SECS: u64 = 120;

/// How long (monotonic seconds) a vote stays valid after the first vote.
const RECALIBRATION_WINDOW_SECS: u128 = 600;

/// Identifies where a calibration observation came from, and how precise it is.
///
/// The identity is an opaque hash of the transport kind and server endpoint, so the
/// provider can tell servers apart without storing or logging their addresses.
#[derive(Clone, Copy, PartialEq, Eq)]
pub struct TimeSource {
    id: u64,
    uncertainty_secs: u64,
}

impl TimeSource {
    /// A source for `kind` (for example `"mieru"`) at `host:port` whose stamps are
    /// within `uncertainty_secs` of the server's clock.
    #[must_use]
    pub fn new(kind: &str, host: &str, port: u16, uncertainty_secs: u64) -> Self {
        use std::hash::{Hash, Hasher};
        let mut hasher = std::collections::hash_map::DefaultHasher::new();
        (kind, host, port).hash(&mut hasher);
        Self { id: hasher.finish(), uncertainty_secs }
    }
}

impl std::fmt::Debug for TimeSource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TimeSource").field("uncertainty_secs", &self.uncertainty_secs).finish_non_exhaustive()
    }
}

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

/// Rejected observations that agree with the first one, from distinct sources.
#[derive(Debug)]
struct Votes {
    /// The first vote's offset from the estimate; later votes must match it.
    offset: i64,
    /// Monotonic reading of the first vote; votes expire after the window.
    mono_nanos: u128,
    sources: Vec<u64>,
}

#[derive(Debug, Default)]
struct ClockState {
    anchor: Option<Anchor>,
    /// The first anchor, or the last one set by a vote of distinct sources. No
    /// single source can move the clock more than the drift bound away from it.
    reference: Option<Anchor>,
    votes: Option<Votes>,
}

impl ClockState {
    /// Records a rejected observation; returns whether enough sources now agree.
    fn vote(&mut self, offset: i64, mono_nanos: u128, source: u64) -> bool {
        let current = self.votes.take().filter(|votes| {
            mono_nanos.saturating_sub(votes.mono_nanos) / 1_000_000_000 <= RECALIBRATION_WINDOW_SECS
                && offset.saturating_sub(votes.offset).unsigned_abs() <= RECALIBRATION_AGREEMENT_SECS
        });
        let mut votes = current.unwrap_or(Votes { offset, mono_nanos, sources: Vec::new() });
        if !votes.sources.contains(&source) {
            votes.sources.push(source);
        }
        if votes.sources.len() >= RECALIBRATION_SOURCES {
            return true;
        }
        self.votes = Some(votes);
        false
    }
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

    /// Calibrate the anchor to a server-observed `observed_network_unix` from
    /// `source`, captured against the current monotonic reading. Returns whether
    /// the observation was accepted.
    ///
    /// An observation before [`MIN_PLAUSIBLE_UNIX_SECS`] is always rejected.
    /// Otherwise the **first** observation is accepted (bootstrap — it may correct
    /// a grossly-wrong device clock) and becomes the reference. Once anchored, an
    /// observation that disagrees with the established network time, or with the
    /// reference, by more than [`MAX_PLAUSIBLE_DRIFT_SECS`] is **rejected**, so a
    /// source cannot walk the clock away in in-window steps, and a single
    /// misconfigured or hostile (but key-holding) server cannot yank the
    /// process-wide clock — and thus the replay window of
    /// *other* transports sharing this provider — to an arbitrary value. An
    /// in-window observation that agrees with the estimate within the source's
    /// uncertainty keeps the anchor; one outside it re-anchors (possibly
    /// backwards, see the crate docs). If the estimate itself is wrong, rejected
    /// observations from [`RECALIBRATION_SOURCES`] distinct sources that agree
    /// with the first of them within [`RECALIBRATION_WINDOW_SECS`] re-anchor it.
    pub fn calibrate(&self, observed_network_unix: i64, source: TimeSource) -> bool {
        if observed_network_unix < MIN_PLAUSIBLE_UNIX_SECS {
            return false;
        }
        let mono = self.clock.monotonic_nanos();
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
                if !guard.vote(offset, mono, source.id) {
                    return false;
                }
                guard.reference = Some(observation);
            } else if offset.unsigned_abs() <= source.uncertainty_secs {
                guard.votes = None;
                return true;
            }
        } else {
            guard.reference = Some(observation);
        }
        guard.anchor = Some(observation);
        guard.votes = None;
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

    /// An exact source (second-precise stamps) named `name`.
    fn source(name: &str) -> TimeSource {
        TimeSource::new("test", name, 1, 0)
    }

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
        // Server says it is T0 right now.
        provider.calibrate(T0, source("a"));
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
        assert!(provider.calibrate(T0 + 1_000_000_000, source("a")));
        assert_eq!(provider.now_unix(), T0 + 1_000_000_000);
        // A wild jump from a rogue/misconfigured server (> MAX_PLAUSIBLE_DRIFT) is
        // rejected and must not move the shared clock.
        assert!(!provider.calibrate(T0 + 1_000_000_000 + 3_600, source("a")));
        assert_eq!(provider.now_unix(), T0 + 1_000_000_000, "implausible calibration must not move the clock");
        // A small in-window correction re-anchors.
        assert!(provider.calibrate(T0 + 1_000_000_010, source("a")));
        assert_eq!(provider.now_unix(), T0 + 1_000_000_010);
    }

    #[test]
    fn recalibration_reanchors_to_latest_observation() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        provider.calibrate(T0 + 1_000, source("a"));
        clock.advance_secs(10);
        assert_eq!(provider.now_unix(), T0 + 1_010);
        // A fresh, in-window observation re-anchors from the new monotonic point.
        provider.calibrate(T0 + 1_400, source("a"));
        assert_eq!(provider.now_unix(), T0 + 1_400);
        clock.advance_secs(5);
        assert_eq!(provider.now_unix(), T0 + 1_405);
    }

    #[test]
    fn wall_clock_step_after_calibration_does_not_move_network_time() {
        // The device clock is an hour slow; the server corrects it.
        let clock = std::sync::Arc::new(TestClock::new(T0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(T0 + 3_600, source("a")));
        clock.advance_secs(10);
        // Automatic time then steps the device clock forward to the right value.
        clock.set_wall(T0 + 3_610);
        assert_eq!(provider.now_unix(), T0 + 3_610, "a wall-clock step must not move network time");
        assert!(provider.calibrate(T0 + 3_611, source("a")), "honest recalibration stays inside the drift window");
        assert_eq!(provider.now_unix(), T0 + 3_611);
    }

    #[test]
    fn one_source_repeating_a_wrong_time_never_moves_the_clock() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(T0 + 1_000_000, source("a")));
        for _ in 0..10 {
            clock.advance_secs(10);
            assert!(!provider.calibrate(provider.now_unix() + 7_200, source("rogue")));
        }
        assert_eq!(provider.now_unix(), T0 + 1_000_100);
    }

    #[test]
    fn two_sources_agreeing_reanchor_a_wrong_estimate() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(T0 + 1_000_000, source("a")));
        // The estimate is now two hours behind the servers.
        assert!(!provider.calibrate(T0 + 1_007_200, source("b")));
        assert!(!provider.calibrate(T0 + 1_007_200, source("b")), "a repeat from the same source is not a new vote");
        clock.advance_secs(30);
        assert!(provider.calibrate(T0 + 1_007_290, source("c")), "a second source agreeing re-anchors");
        assert_eq!(provider.now_unix(), T0 + 1_007_290);
    }

    #[test]
    fn votes_must_agree_with_the_first_vote_and_expire() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(T0 + 1_000_000, source("a")));
        // The third vote is within the agreement of the second but not of the first.
        assert!(!provider.calibrate(T0 + 1_007_200, source("b")));
        assert!(!provider.calibrate(T0 + 1_007_300, source("b")));
        assert!(!provider.calibrate(T0 + 1_007_400, source("c")));
        assert_eq!(provider.now_unix(), T0 + 1_000_000, "drifting votes must not re-anchor");

        // A vote older than the window no longer counts.
        assert!(!provider.calibrate(T0 + 1_007_200, source("d")));
        clock.advance_secs(601);
        assert!(!provider.calibrate(T0 + 1_007_801, source("e")));
        assert_eq!(provider.now_unix(), T0 + 1_000_601);
    }

    #[test]
    fn coarse_source_within_its_uncertainty_does_not_move_the_clock() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(T0 + 1_000_000, source("sip022")));
        let mieru = TimeSource::new("mieru", "a", 1, 30);
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
        assert!(!provider.calibrate(0, source("rogue")), "a zero timestamp must not become the first anchor");
        assert!(!provider.calibrate(MIN_PLAUSIBLE_UNIX_SECS - 1, source("rogue")));
        assert!(!provider.is_calibrated());
        assert!(provider.calibrate(MIN_PLAUSIBLE_UNIX_SECS, source("a")));
    }

    #[test]
    fn one_source_cannot_walk_the_clock_in_steps() {
        let clock = std::sync::Arc::new(TestClock::new(0));
        let provider = NetworkTimeProvider::with_clock(Box::new(clock.clone()));
        assert!(provider.calibrate(T0, source("a")));
        // Each step is inside the drift window of the previous anchor...
        assert!(provider.calibrate(T0 + 500, source("rogue")));
        // ...but the total distance from the trusted anchor is bounded.
        assert!(!provider.calibrate(T0 + 1_000, source("rogue")));
        assert!(!provider.calibrate(T0 + 1_500, source("rogue")));
        assert_eq!(provider.now_unix(), T0 + 500);
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
