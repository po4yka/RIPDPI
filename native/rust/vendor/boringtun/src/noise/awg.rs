// Copyright (c) 2026 RIPDPI contributors.
// SPDX-License-Identifier: BSD-3-Clause

//! AmneziaWG timer policy. The ordinary WireGuard constructor keeps its original timers.

use rand_core::{OsRng, RngCore};
use std::time::Duration;

/// An inclusive, validated range of seconds (or handshake attempts).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TimerRange {
    low: u16,
    high: u16,
}

impl TimerRange {
    pub fn new(low: u16, high: u16) -> Option<Self> {
        (low <= high).then_some(Self { low, high })
    }

    pub const fn fixed(value: u16) -> Self {
        Self {
            low: value,
            high: value,
        }
    }

    fn sample(self) -> u32 {
        let span = u32::from(self.high) - u32::from(self.low) + 1;
        let threshold = span.wrapping_neg() % span;
        loop {
            let value = OsRng.next_u32();
            if value >= threshold {
                return u32::from(self.low) + value % span;
            }
        }
    }

    fn duration(self) -> Duration {
        Duration::from_secs(u64::from(self.sample()))
    }
}

/// Validated policy supplied once, before the first handshake.
/// Unset textual fields are normalized to these defaults by the caller.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AwgTimerConfig {
    pub rekey_after: TimerRange,
    pub rekey_timeout: TimerRange,
    pub reject_after: TimerRange,
    pub keepalive_timeout: TimerRange,
    pub max_handshake_attempts: TimerRange,
    pub persistent_keepalive: Option<TimerRange>,
}

impl Default for AwgTimerConfig {
    fn default() -> Self {
        Self {
            rekey_after: TimerRange::fixed(120),
            rekey_timeout: TimerRange::fixed(5),
            reject_after: TimerRange::fixed(180),
            keepalive_timeout: TimerRange::fixed(10),
            max_handshake_attempts: TimerRange::fixed(18),
            persistent_keepalive: None,
        }
    }
}

impl AwgTimerConfig {
    pub(super) fn expiry(self) -> Duration {
        Duration::from_secs(u64::from(self.reject_after.high))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum Action {
    None,
    Initiate,
    Retry,
    Keepalive,
    StopRetries,
    ClearKeys,
}

pub(super) struct AwgTimers {
    pub(super) config: AwgTimerConfig,
    pub(super) roles: [bool; super::N_SESSIONS],
    pub(super) confirmed: [bool; super::N_SESSIONS],
    retry: Option<Duration>,
    cleanup: Option<Duration>,
    passive_keepalive: Option<Duration>,
    new_handshake: Option<Duration>,
    persistent_keepalive: Option<Duration>,
    last_initiation: Option<Duration>,
    attempts: u32,
    max_attempts: u32,
    another_keepalive: bool,
    refresh: bool,
    received_refresh: bool,
}

impl AwgTimers {
    pub(super) fn new(config: AwgTimerConfig) -> Self {
        Self {
            config,
            roles: [false; super::N_SESSIONS],
            confirmed: [false; super::N_SESSIONS],
            retry: None,
            cleanup: None,
            passive_keepalive: None,
            new_handshake: None,
            persistent_keepalive: None,
            last_initiation: None,
            attempts: 0,
            max_attempts: config.max_handshake_attempts.sample(),
            another_keepalive: false,
            refresh: false,
            received_refresh: false,
        }
    }

    pub(super) fn request_initiation(&mut self, now: Duration, retry: bool) -> bool {
        if !retry {
            self.attempts = 0;
            self.max_attempts = self.config.max_handshake_attempts.sample();
        }
        self.last_initiation.map_or(true, |last| {
            now.saturating_sub(last)
                >= Duration::from_secs(u64::from(self.config.rekey_timeout.low))
        })
    }

    pub(super) fn initiated(&mut self, now: Duration) {
        self.last_initiation = Some(now);
        self.retry = Some(now + self.config.rekey_timeout.duration() + jitter());
        self.sent(now, false);
    }

    pub(super) fn derived(&mut self, now: Duration, index: usize, initiator: bool) {
        self.roles[index % super::N_SESSIONS] = initiator;
        self.confirmed[index % super::N_SESSIONS] = initiator;
        self.cleanup = Some(now + self.config.expiry() * 3);
    }

    pub(super) fn completed(&mut self) {
        self.retry = None;
        self.refresh = false;
        self.attempts = 0;
        self.max_attempts = self.config.max_handshake_attempts.sample();
        self.received_refresh = false;
    }

    fn traversed(&mut self, now: Duration) {
        self.persistent_keepalive = self
            .config
            .persistent_keepalive
            .filter(|range| range.high != 0)
            .map(|range| now + range.duration());
    }

    pub(super) fn sent(&mut self, now: Duration, data: bool) {
        self.passive_keepalive = None;
        self.traversed(now);
        if data && self.new_handshake.is_none() {
            self.new_handshake = Some(
                now + Duration::from_secs(u64::from(self.config.keepalive_timeout.high))
                    + self.config.rekey_timeout.duration()
                    + jitter(),
            );
        }
    }

    pub(super) fn received(&mut self, now: Duration, data: bool) {
        self.new_handshake = None;
        self.traversed(now);
        if data {
            if self.passive_keepalive.is_none() {
                self.passive_keepalive = Some(now + self.config.keepalive_timeout.duration());
            } else {
                self.another_keepalive = true;
            }
        }
    }

    pub(super) fn refresh_sending(&mut self, age: Duration, index: usize) {
        if self.roles[index % super::N_SESSIONS] && age > self.config.rekey_after.duration() {
            self.refresh = true;
        }
    }

    pub(super) fn refresh_receiving(&mut self, age: Duration, index: usize) {
        if !self.roles[index % super::N_SESSIONS] || self.received_refresh {
            return;
        }
        let threshold = self
            .config
            .reject_after
            .duration()
            .saturating_sub(Duration::from_secs(u64::from(
                self.config.keepalive_timeout.low,
            )))
            .saturating_sub(Duration::from_secs(u64::from(
                self.config.rekey_timeout.low,
            )));
        if age > threshold {
            self.received_refresh = true;
            self.refresh = true;
        }
    }

    pub(super) fn poll(&mut self, now: Duration) -> Action {
        if due(self.cleanup, now) {
            return Action::ClearKeys;
        }
        if due(self.retry, now) {
            self.retry = None;
            if self.attempts > self.max_attempts {
                self.passive_keepalive = None;
                self.cleanup.get_or_insert(now + self.config.expiry() * 3);
                return Action::StopRetries;
            }
            self.attempts += 1;
            return Action::Retry;
        }
        if self.refresh || due(self.new_handshake, now) {
            self.refresh = false;
            self.new_handshake = None;
            return Action::Initiate;
        }
        if due(self.passive_keepalive, now) || due(self.persistent_keepalive, now) {
            self.passive_keepalive = None;
            self.persistent_keepalive = None;
            return Action::Keepalive;
        }
        Action::None
    }

    pub(super) fn keepalive_sent(&mut self, now: Duration) {
        if std::mem::replace(&mut self.another_keepalive, false) {
            self.passive_keepalive = Some(now + self.config.keepalive_timeout.duration());
        }
    }
}

fn due(deadline: Option<Duration>, now: Duration) -> bool {
    deadline.is_some_and(|deadline| now >= deadline)
}

fn jitter() -> Duration {
    Duration::from_millis(u64::from(TimerRange { low: 0, high: 332 }.sample()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn non_retry_request_resets_count_even_when_spacing_suppresses_send() {
        let mut timers = AwgTimers::new(AwgTimerConfig::default());
        timers.initiated(Duration::ZERO);
        timers.attempts = 5;
        assert!(!timers.request_initiation(Duration::from_secs(1), false));
        assert_eq!(timers.attempts, 0);
        assert_eq!(timers.max_attempts, 18);
        timers.attempts = 5;
        assert!(!timers.request_initiation(Duration::from_secs(1), true));
        assert_eq!(timers.attempts, 5);
        timers.completed();
        assert_eq!(timers.attempts, 0);
        assert_eq!(timers.retry, None);
    }

    #[test]
    fn persistent_deadline_moves_with_authenticated_traversal() {
        let mut timers = AwgTimers::new(AwgTimerConfig {
            persistent_keepalive: Some(TimerRange::fixed(25)),
            ..Default::default()
        });
        timers.sent(Duration::ZERO, false);
        timers.received(Duration::from_secs(10), false);
        assert_eq!(timers.poll(Duration::from_secs(25)), Action::None);
        assert_eq!(timers.poll(Duration::from_secs(35)), Action::Keepalive);
        assert_eq!(timers.poll(Duration::from_secs(35)), Action::None);
        let mut off = AwgTimers::new(AwgTimerConfig::default());
        off.sent(Duration::ZERO, false);
        assert_eq!(off.poll(Duration::from_secs(60)), Action::None);
    }

    #[test]
    fn zero_based_timer_ranges_are_bounded_and_receive_threshold_saturates() {
        let range = TimerRange::new(0, 1).unwrap();
        for _ in 0..64 {
            assert!(range.sample() <= 1);
        }
        assert!(TimerRange::new(2, 1).is_none());
        let mut timers = AwgTimers::new(AwgTimerConfig {
            reject_after: TimerRange::fixed(5),
            ..Default::default()
        });
        timers.derived(Duration::ZERO, 0, true);
        timers.refresh_receiving(Duration::ZERO, 0);
        assert_eq!(timers.poll(Duration::ZERO), Action::None);
        timers.refresh_receiving(Duration::from_nanos(1), 0);
        assert_eq!(timers.poll(Duration::from_nanos(1)), Action::Initiate);
        timers.refresh_receiving(Duration::from_secs(1), 0);
        assert_eq!(timers.poll(Duration::from_secs(1)), Action::None);
    }

    #[test]
    fn new_handshake_deadline_uses_high_keepalive_and_does_not_slide_on_data_sends() {
        let mut timers = AwgTimers::new(AwgTimerConfig {
            keepalive_timeout: TimerRange::new(4, 7).unwrap(),
            rekey_timeout: TimerRange::fixed(3),
            ..Default::default()
        });
        timers.sent(Duration::ZERO, true);
        timers.sent(Duration::from_secs(9), true);
        assert_eq!(timers.poll(Duration::from_secs(9)), Action::None);
        assert_eq!(timers.poll(Duration::from_secs(11)), Action::Initiate);
        timers.sent(Duration::from_secs(12), true);
        timers.received(Duration::from_secs(13), false);
        assert_eq!(timers.poll(Duration::from_secs(24)), Action::None);
    }
}
