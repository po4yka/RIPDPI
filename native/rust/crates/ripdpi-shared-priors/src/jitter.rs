//! Jitter scheduler for shared-priors uploads.
//!
//! Uploads are delayed by a random duration in the window [60 s, 3600 s] so
//! that the upload timestamp cannot be correlated with user activity.
//! The window bounds are deliberately coarse — the goal is temporal
//! unlinkability, not precise scheduling.

use std::time::Duration;

/// Lower bound of the jitter window (60 seconds).
pub const JITTER_MIN_SECS: u64 = 60;

/// Upper bound of the jitter window (3600 seconds = 1 hour).
pub const JITTER_MAX_SECS: u64 = 3600;

/// Schedules upload delays with uniform jitter in [`JITTER_MIN_SECS`, `JITTER_MAX_SECS`].
pub struct JitterScheduler;

impl JitterScheduler {
    /// Return the next upload delay as a [`Duration`].
    ///
    /// The delay is drawn uniformly from [`JITTER_MIN_SECS`, `JITTER_MAX_SECS`]
    /// (inclusive on both ends) using the caller-supplied RNG so the scheduler
    /// is deterministic in tests. The `now` parameter is accepted for API
    /// symmetry with future implementations that may base the window on a
    /// wall-clock deadline; it is unused in the current uniform-sampling
    /// strategy.
    pub fn next_delay<R: rand::RngExt + ?Sized>(_now: std::time::Instant, rng: &mut R) -> Duration {
        let secs = rng.random_range(JITTER_MIN_SECS..=JITTER_MAX_SECS);
        Duration::from_secs(secs)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rand::rngs::SmallRng;
    use rand::SeedableRng;

    fn make_rng(seed: u64) -> SmallRng {
        SmallRng::seed_from_u64(seed)
    }

    /// Delays produced by JitterScheduler must fall in [60, 3600] seconds.
    #[test]
    fn jitter_delays_fall_in_window() {
        let mut rng = make_rng(42);
        let now = std::time::Instant::now();
        for _ in 0..200 {
            let delay = JitterScheduler::next_delay(now, &mut rng);
            assert!(
                delay.as_secs() >= JITTER_MIN_SECS,
                "delay {} s is below minimum {} s",
                delay.as_secs(),
                JITTER_MIN_SECS
            );
            assert!(
                delay.as_secs() <= JITTER_MAX_SECS,
                "delay {} s is above maximum {} s",
                delay.as_secs(),
                JITTER_MAX_SECS
            );
        }
    }

    /// Same seed must produce the same sequence of delays (determinism).
    #[test]
    fn jitter_with_same_seed_is_deterministic() {
        let now = std::time::Instant::now();
        let mut rng_a = make_rng(0xdead_beef);
        let mut rng_b = make_rng(0xdead_beef);
        for _ in 0..20 {
            let a = JitterScheduler::next_delay(now, &mut rng_a);
            let b = JitterScheduler::next_delay(now, &mut rng_b);
            assert_eq!(a, b, "same seed must yield identical delays");
        }
    }
}
