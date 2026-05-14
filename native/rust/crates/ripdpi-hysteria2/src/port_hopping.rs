//! Randomized port-hopping interval window for the Hysteria2 outbound.
//!
//! Hysteria 2.8.0 replaced the single fixed `hopInterval` knob with a
//! `minHopInterval` / `maxHopInterval` window so the per-session port-hopping
//! cadence becomes unpredictable to interval-based DPI traffic classifiers.
//! This module owns the runtime side of that feature:
//!
//! * [`PortHoppingWindow`] resolves the three [`Config`](crate::Config) knobs
//!   (`hop_interval`, `min_hop_interval`, `max_hop_interval`) into a single
//!   hopping policy.
//! * [`PortHoppingWindow::next_interval`] draws the interval a session should
//!   wait before its next port hop -- a uniform sample inside
//!   `[min, max]` for a randomized window, or the fixed cadence otherwise.
//! * [`HopIntervalTelemetry`] accumulates the intervals actually drawn for a
//!   session so the distribution can be surfaced for verification.
//!
//! Backward compatibility: when neither `minHopInterval` nor `maxHopInterval`
//! is configured the window degrades to the previous behavior -- a fixed
//! `hopInterval` cadence, or no hopping at all when that is also unset.

use std::time::Duration;

use rand::RngExt;

/// The resolved port-hopping policy for a Hysteria2 outbound.
///
/// Built from a [`Config`](crate::Config) via
/// [`Config::port_hopping_window`](crate::Config::port_hopping_window). The
/// three variants mirror the three reachable states of the Hysteria 2.8.0
/// config surface.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PortHoppingWindow {
    /// Port hopping is off: no `hopInterval`, `minHopInterval`, or
    /// `maxHopInterval` was configured.
    Disabled,
    /// Legacy fixed cadence: only `hopInterval` was configured, so every hop
    /// waits exactly this long. This is the pre-2.8.0 behavior.
    Fixed(Duration),
    /// Randomized window per Hysteria 2.8.0: each hop waits a uniformly random
    /// duration inside the inclusive `[min, max]` range. `min <= max` always
    /// holds because the constructor swaps reversed bounds.
    Randomized {
        /// Inclusive lower bound of the hop-interval window.
        min: Duration,
        /// Inclusive upper bound of the hop-interval window.
        max: Duration,
    },
}

impl PortHoppingWindow {
    /// Resolve the raw config knobs into a hopping policy.
    ///
    /// Precedence, matching Hysteria 2.8.0:
    /// * if either window bound is set, the result is [`Randomized`] -- a
    ///   missing bound falls back to the other bound, and a missing-both case
    ///   cannot occur here. Reversed bounds (`min > max`) are swapped so the
    ///   window is always well-ordered.
    /// * else if `hop_interval` is set, the result is [`Fixed`].
    /// * else the result is [`Disabled`].
    ///
    /// [`Randomized`]: PortHoppingWindow::Randomized
    /// [`Fixed`]: PortHoppingWindow::Fixed
    /// [`Disabled`]: PortHoppingWindow::Disabled
    #[must_use]
    pub fn resolve(
        hop_interval: Option<Duration>,
        min_hop_interval: Option<Duration>,
        max_hop_interval: Option<Duration>,
    ) -> Self {
        match (min_hop_interval, max_hop_interval) {
            (Some(min), Some(max)) => {
                let (min, max) = if min <= max { (min, max) } else { (max, min) };
                Self::Randomized { min, max }
            }
            (Some(bound), None) | (None, Some(bound)) => Self::Randomized { min: bound, max: bound },
            (None, None) => match hop_interval {
                Some(interval) => Self::Fixed(interval),
                None => Self::Disabled,
            },
        }
    }

    /// Whether this window randomizes the hop interval (Hysteria 2.8.0
    /// behavior) rather than using a fixed cadence or no hopping.
    #[must_use]
    pub fn is_randomized(&self) -> bool {
        matches!(self, Self::Randomized { .. })
    }

    /// Whether port hopping is enabled at all (randomized or fixed).
    #[must_use]
    pub fn is_enabled(&self) -> bool {
        !matches!(self, Self::Disabled)
    }

    /// Draw the interval a session should wait before its next port hop.
    ///
    /// * [`Randomized`](PortHoppingWindow::Randomized): a uniform sample in the
    ///   inclusive `[min, max]` range, drawn fresh from `rng` on every call so
    ///   each hop is independently unpredictable.
    /// * [`Fixed`](PortHoppingWindow::Fixed): the configured cadence.
    /// * [`Disabled`](PortHoppingWindow::Disabled): `None`.
    pub fn next_interval<R: RngExt + ?Sized>(&self, rng: &mut R) -> Option<Duration> {
        match *self {
            Self::Disabled => None,
            Self::Fixed(interval) => Some(interval),
            Self::Randomized { min, max } => {
                if min == max {
                    return Some(min);
                }
                // Sample in nanoseconds so sub-second windows are honored;
                // the inclusive upper bound matches Hysteria's semantics.
                let lo = duration_to_nanos(min);
                let hi = duration_to_nanos(max);
                let nanos = rng.random_range(lo..=hi);
                Some(nanos_to_duration(nanos))
            }
        }
    }
}

/// Saturating `Duration` -> nanoseconds, clamped to `u128`'s range.
fn duration_to_nanos(duration: Duration) -> u128 {
    duration.as_nanos()
}

/// Nanoseconds -> `Duration`, clamping the seconds part so an extreme window
/// bound cannot panic `Duration::new`.
fn nanos_to_duration(nanos: u128) -> Duration {
    const NANOS_PER_SEC: u128 = 1_000_000_000;
    let secs = (nanos / NANOS_PER_SEC).min(u128::from(u64::MAX)) as u64;
    let subsec = (nanos % NANOS_PER_SEC) as u32;
    Duration::new(secs, subsec)
}

/// Per-session accumulator for the hop intervals actually drawn from a
/// [`PortHoppingWindow`].
///
/// The point of randomized port hopping is that the cadence is *observably*
/// unpredictable; this telemetry surfaces the realized distribution so a test
/// (or a diagnostics probe) can verify the spread is non-degenerate and stays
/// inside the configured window. Every interval handed to a session should
/// also be fed here via [`record`](HopIntervalTelemetry::record).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct HopIntervalTelemetry {
    count: u64,
    total: Duration,
    min: Option<Duration>,
    max: Option<Duration>,
}

impl HopIntervalTelemetry {
    /// A fresh telemetry accumulator with no samples.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Record one hop interval that was drawn for the session.
    pub fn record(&mut self, interval: Duration) {
        self.count += 1;
        self.total = self.total.saturating_add(interval);
        self.min = Some(self.min.map_or(interval, |current| current.min(interval)));
        self.max = Some(self.max.map_or(interval, |current| current.max(interval)));
    }

    /// Number of hop intervals recorded so far.
    #[must_use]
    pub fn count(&self) -> u64 {
        self.count
    }

    /// Smallest interval recorded, or `None` if nothing has been recorded.
    #[must_use]
    pub fn min(&self) -> Option<Duration> {
        self.min
    }

    /// Largest interval recorded, or `None` if nothing has been recorded.
    #[must_use]
    pub fn max(&self) -> Option<Duration> {
        self.max
    }

    /// Mean of the recorded intervals, or `None` if nothing has been recorded.
    #[must_use]
    pub fn mean(&self) -> Option<Duration> {
        if self.count == 0 {
            return None;
        }
        Some(self.total / u32::try_from(self.count).unwrap_or(u32::MAX))
    }

    /// Spread between the largest and smallest recorded interval.
    ///
    /// A randomized window with enough samples produces a non-zero spread; a
    /// fixed cadence (or a single sample) produces [`Duration::ZERO`].
    #[must_use]
    pub fn spread(&self) -> Duration {
        match (self.min, self.max) {
            (Some(min), Some(max)) => max.saturating_sub(min),
            _ => Duration::ZERO,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolve_with_no_knobs_is_disabled() {
        assert_eq!(PortHoppingWindow::resolve(None, None, None), PortHoppingWindow::Disabled);
    }

    #[test]
    fn resolve_with_only_hop_interval_is_fixed_for_backward_compat() {
        let window = PortHoppingWindow::resolve(Some(Duration::from_secs(30)), None, None);
        assert_eq!(window, PortHoppingWindow::Fixed(Duration::from_secs(30)));
        assert!(window.is_enabled());
        assert!(!window.is_randomized());
    }

    #[test]
    fn resolve_with_window_is_randomized() {
        let window = PortHoppingWindow::resolve(
            Some(Duration::from_secs(30)),
            Some(Duration::from_secs(10)),
            Some(Duration::from_secs(60)),
        );
        assert_eq!(
            window,
            PortHoppingWindow::Randomized { min: Duration::from_secs(10), max: Duration::from_secs(60) }
        );
        assert!(window.is_randomized());
    }

    #[test]
    fn resolve_swaps_reversed_window_bounds() {
        let window = PortHoppingWindow::resolve(None, Some(Duration::from_secs(60)), Some(Duration::from_secs(10)));
        assert_eq!(
            window,
            PortHoppingWindow::Randomized { min: Duration::from_secs(10), max: Duration::from_secs(60) }
        );
    }

    #[test]
    fn resolve_with_single_window_bound_uses_it_for_both() {
        assert_eq!(
            PortHoppingWindow::resolve(None, Some(Duration::from_secs(15)), None),
            PortHoppingWindow::Randomized { min: Duration::from_secs(15), max: Duration::from_secs(15) }
        );
        assert_eq!(
            PortHoppingWindow::resolve(None, None, Some(Duration::from_secs(25))),
            PortHoppingWindow::Randomized { min: Duration::from_secs(25), max: Duration::from_secs(25) }
        );
    }

    #[test]
    fn disabled_window_yields_no_interval() {
        let mut rng = rand::rng();
        assert_eq!(PortHoppingWindow::Disabled.next_interval(&mut rng), None);
    }

    #[test]
    fn fixed_window_always_yields_the_configured_cadence() {
        let mut rng = rand::rng();
        let window = PortHoppingWindow::Fixed(Duration::from_secs(30));
        for _ in 0..100 {
            assert_eq!(window.next_interval(&mut rng), Some(Duration::from_secs(30)));
        }
    }

    #[test]
    fn randomized_window_stays_inside_the_configured_bounds() {
        let mut rng = rand::rng();
        let min = Duration::from_secs(10);
        let max = Duration::from_secs(60);
        let window = PortHoppingWindow::Randomized { min, max };
        for _ in 0..1_000 {
            let interval = window.next_interval(&mut rng).expect("randomized window yields an interval");
            assert!(interval >= min, "interval {interval:?} below window minimum {min:?}");
            assert!(interval <= max, "interval {interval:?} above window maximum {max:?}");
        }
    }

    #[test]
    fn randomized_window_actually_varies_the_interval() {
        let mut rng = rand::rng();
        let window = PortHoppingWindow::Randomized { min: Duration::from_secs(1), max: Duration::from_secs(120) };
        let first = window.next_interval(&mut rng).expect("interval");
        let varied = (0..1_000).any(|_| window.next_interval(&mut rng).expect("interval") != first);
        assert!(varied, "a wide randomized window must produce more than one distinct interval");
    }

    #[test]
    fn randomized_window_with_equal_bounds_is_effectively_fixed() {
        let mut rng = rand::rng();
        let window = PortHoppingWindow::Randomized { min: Duration::from_secs(30), max: Duration::from_secs(30) };
        for _ in 0..100 {
            assert_eq!(window.next_interval(&mut rng), Some(Duration::from_secs(30)));
        }
    }

    #[test]
    fn randomized_window_honors_sub_second_bounds() {
        let mut rng = rand::rng();
        let min = Duration::from_millis(250);
        let max = Duration::from_millis(750);
        let window = PortHoppingWindow::Randomized { min, max };
        for _ in 0..1_000 {
            let interval = window.next_interval(&mut rng).expect("interval");
            assert!(interval >= min && interval <= max, "sub-second interval {interval:?} escaped its window");
        }
    }

    #[test]
    fn telemetry_starts_empty() {
        let telemetry = HopIntervalTelemetry::new();
        assert_eq!(telemetry.count(), 0);
        assert_eq!(telemetry.min(), None);
        assert_eq!(telemetry.max(), None);
        assert_eq!(telemetry.mean(), None);
        assert_eq!(telemetry.spread(), Duration::ZERO);
    }

    #[test]
    fn telemetry_tracks_count_min_max_and_mean() {
        let mut telemetry = HopIntervalTelemetry::new();
        telemetry.record(Duration::from_secs(10));
        telemetry.record(Duration::from_secs(30));
        telemetry.record(Duration::from_secs(20));
        assert_eq!(telemetry.count(), 3);
        assert_eq!(telemetry.min(), Some(Duration::from_secs(10)));
        assert_eq!(telemetry.max(), Some(Duration::from_secs(30)));
        assert_eq!(telemetry.mean(), Some(Duration::from_secs(20)));
        assert_eq!(telemetry.spread(), Duration::from_secs(20));
    }

    #[test]
    fn telemetry_spread_is_zero_for_a_fixed_cadence() {
        let mut rng = rand::rng();
        let window = PortHoppingWindow::Fixed(Duration::from_secs(30));
        let mut telemetry = HopIntervalTelemetry::new();
        for _ in 0..50 {
            telemetry.record(window.next_interval(&mut rng).expect("interval"));
        }
        assert_eq!(telemetry.spread(), Duration::ZERO);
        assert_eq!(telemetry.count(), 50);
    }

    #[test]
    fn telemetry_surfaces_a_non_degenerate_distribution_for_a_randomized_window() {
        let mut rng = rand::rng();
        let min = Duration::from_secs(10);
        let max = Duration::from_secs(60);
        let window = PortHoppingWindow::Randomized { min, max };
        let mut telemetry = HopIntervalTelemetry::new();
        for _ in 0..2_000 {
            telemetry.record(window.next_interval(&mut rng).expect("interval"));
        }
        assert_eq!(telemetry.count(), 2_000);
        assert!(telemetry.min().expect("min") >= min);
        assert!(telemetry.max().expect("max") <= max);
        assert!(telemetry.spread() > Duration::ZERO, "a randomized window must surface a non-zero spread");
        let mean = telemetry.mean().expect("mean");
        assert!(mean > min && mean < max, "mean {mean:?} should sit inside the window");
    }
}
