// Copyright (c) 2019 Cloudflare, Inc. All rights reserved.
// SPDX-License-Identifier: BSD-3-Clause

use super::errors::WireGuardError;
use crate::noise::{Tunn, TunnResult};
use std::mem;
use std::ops::{Index, IndexMut};

use std::time::Duration;

#[cfg(feature = "mock-instant")]
use mock_instant::Instant;

#[cfg(not(feature = "mock-instant"))]
use crate::sleepyinstant::Instant;

// Some constants, represent time in seconds
// https://www.wireguard.com/papers/wireguard.pdf#page=14
pub(crate) const REKEY_AFTER_TIME: Duration = Duration::from_secs(120);
const REJECT_AFTER_TIME: Duration = Duration::from_secs(180);
const REKEY_ATTEMPT_TIME: Duration = Duration::from_secs(90);
pub(crate) const REKEY_TIMEOUT: Duration = Duration::from_secs(5);
const KEEPALIVE_TIMEOUT: Duration = Duration::from_secs(10);
const COOKIE_EXPIRATION_TIME: Duration = Duration::from_secs(120);

#[derive(Debug)]
pub enum TimerName {
    /// Current time, updated each call to `update_timers`
    TimeCurrent,
    /// Time when last handshake was completed
    TimeSessionEstablished,
    /// Time the last attempt for a new handshake began
    TimeLastHandshakeStarted,
    /// Time we last received and authenticated a packet
    TimeLastPacketReceived,
    /// Time we last send a packet
    TimeLastPacketSent,
    /// Time we last received and authenticated a DATA packet
    TimeLastDataPacketReceived,
    /// Time we last send a DATA packet
    TimeLastDataPacketSent,
    /// Time we last received a cookie
    TimeCookieReceived,
    /// Time we last sent persistent keepalive
    TimePersistentKeepalive,
    Top,
}

use self::TimerName::*;

#[cfg(test)]
mod awg_tests {
    use super::*;
    use crate::noise::awg::{AwgTimerConfig, TimerRange};
    use crate::x25519::{PublicKey, StaticSecret};
    use std::convert::TryInto;

    fn peer() -> Tunn {
        Tunn::new(
            StaticSecret::from([7; 32]),
            PublicKey::from(&StaticSecret::from([9; 32])),
            None,
            None,
            0,
            None,
        )
    }

    #[test]
    fn configured_one_second_retry_is_not_overridden_by_the_five_second_default() {
        let mut tunnel = peer();
        tunnel
            .set_awg_timer_config(AwgTimerConfig {
                rekey_timeout: TimerRange::fixed(1),
                ..Default::default()
            })
            .unwrap();
        let mut packet = [0; 256];
        assert!(matches!(
            tunnel.format_handshake_initiation(&mut packet, false),
            TunnResult::WriteToNetwork(_)
        ));
        // One second plus the maximum 332 ms of jitter has elapsed, without a real sleep.
        assert!(matches!(
            tunnel.update_timers_at(Duration::from_secs(2), &mut packet),
            TunnResult::WriteToNetwork(_)
        ));
    }

    fn established(config: AwgTimerConfig) -> (Tunn, Tunn) {
        let a_key = StaticSecret::from([7; 32]);
        let b_key = StaticSecret::from([9; 32]);
        let mut a = Tunn::new(a_key.clone(), PublicKey::from(&b_key), None, None, 0, None);
        let mut b = Tunn::new(b_key, PublicKey::from(&a_key), None, None, 1, None);
        a.set_awg_timer_config(config).unwrap();
        b.set_awg_timer_config(config).unwrap();
        let mut first = [0; 512];
        let mut second = [0; 512];
        let TunnResult::WriteToNetwork(init) = a.format_handshake_initiation(&mut first, false)
        else {
            panic!("init")
        };
        let TunnResult::WriteToNetwork(response) = b.decapsulate(None, init, &mut second) else {
            panic!("response")
        };
        let TunnResult::WriteToNetwork(confirm) = a.decapsulate(None, response, &mut first) else {
            panic!("confirm")
        };
        assert!(matches!(
            b.decapsulate(None, confirm, &mut second),
            TunnResult::Done
        ));
        (a, b)
    }

    fn ip() -> [u8; 20] {
        let mut ip = [0; 20];
        ip[0] = 0x45;
        ip[3] = 20;
        ip
    }

    #[test]
    fn one_attempt_means_two_retries_then_stops_until_new_send() {
        let mut tunnel = peer();
        tunnel
            .set_awg_timer_config(AwgTimerConfig {
                rekey_timeout: TimerRange::fixed(1),
                max_handshake_attempts: TimerRange::fixed(1),
                ..Default::default()
            })
            .unwrap();
        let mut packet = [0; 256];
        assert!(matches!(
            tunnel.encapsulate(&ip(), &mut packet),
            TunnResult::WriteToNetwork(_)
        ));
        for second in [2, 4] {
            assert!(matches!(
                tunnel.update_timers_at(Duration::from_secs(second), &mut packet),
                TunnResult::WriteToNetwork(_)
            ));
        }
        assert!(matches!(
            tunnel.update_timers_at(Duration::from_secs(6), &mut packet),
            TunnResult::Err(WireGuardError::ConnectionExpired)
        ));
        assert!(tunnel.packet_queue.is_empty());
        assert!(matches!(
            tunnel.update_timers_at(Duration::from_secs(8), &mut packet),
            TunnResult::Done
        ));
        assert!(matches!(
            tunnel.encapsulate(&ip(), &mut packet),
            TunnResult::WriteToNetwork(_)
        ));
        assert!(matches!(
            tunnel.update_timers_at(Duration::from_secs(10), &mut packet),
            TunnResult::WriteToNetwork(_)
        ));
    }

    #[test]
    fn configured_expiry_is_checked_without_a_timer_poll_and_has_reference_boundaries() {
        let config = AwgTimerConfig {
            reject_after: TimerRange::new(20, 30).unwrap(),
            ..Default::default()
        };
        let (mut a, _) = established(config);
        let mut packet = [0; 256];
        a.timers[TimeCurrent] =
            a.timers.session_timers[a.current % super::super::N_SESSIONS] + Duration::from_secs(30);
        let TunnResult::WriteToNetwork(out) = a.encapsulate(&ip(), &mut packet) else {
            panic!("new handshake")
        };
        assert_eq!(
            &out[..4],
            &[1, 0, 0, 0],
            "sending at the high expiry bound must not use the old key"
        );

        for (age, accepted) in [(20, true), (30, true), (31, false)] {
            let (mut a, mut b) = established(config);
            let mut encrypted = [0; 256];
            let TunnResult::WriteToNetwork(data) = a.encapsulate(&ip(), &mut encrypted) else {
                panic!("data")
            };
            let idx = b.current % super::super::N_SESSIONS;
            b.timers[TimeCurrent] = b.timers.session_timers[idx] + Duration::from_secs(age);
            let result = b.decapsulate(None, data, &mut packet);
            assert_eq!(
                matches!(result, TunnResult::WriteToTunnelV4(..)),
                accepted,
                "receive age {age}: {result:?}"
            );
        }
    }

    #[test]
    fn long_rekey_policy_replaces_the_fixed_ceiling_and_respects_role() {
        for (limit, age, initiator, expects_rekey) in [
            (200, 121, true, false),
            (200, 201, true, true),
            (200, 201, false, false),
            (60, 60, true, false),
            (60, 61, true, true),
            (60, 61, false, false),
        ] {
            let (a, b) = established(AwgTimerConfig {
                rekey_after: TimerRange::fixed(limit),
                reject_after: TimerRange::fixed(300),
                ..Default::default()
            });
            let mut tunnel = if initiator { a } else { b };
            let now = tunnel.timers.session_timers[tunnel.current % super::super::N_SESSIONS]
                + Duration::from_secs(age);
            tunnel.timers[TimeCurrent] = now;
            let mut packet = [0; 256];
            assert!(matches!(
                tunnel.encapsulate(&ip(), &mut packet),
                TunnResult::WriteToNetwork(_)
            ));
            let result = tunnel.update_timers_at(now, &mut packet);
            assert_eq!(
                matches!(result, TunnResult::WriteToNetwork(_)),
                expects_rekey,
                "age {age}, initiator {initiator}: {result:?}"
            );
        }
    }

    #[test]
    fn passive_keepalive_uses_configured_timeout_and_auth_send_cancels_it() {
        for cancel in [false, true] {
            let (mut a, mut b) = established(AwgTimerConfig {
                keepalive_timeout: TimerRange::fixed(7),
                ..Default::default()
            });
            let mut encrypted = [0; 256];
            let mut packet = [0; 256];
            let TunnResult::WriteToNetwork(data) = a.encapsulate(&ip(), &mut encrypted) else {
                panic!("data")
            };
            assert!(matches!(
                b.decapsulate(None, data, &mut packet),
                TunnResult::WriteToTunnelV4(..)
            ));
            let received = b.timers[TimeCurrent];
            assert!(matches!(
                b.update_timers_at(received + Duration::from_secs(6), &mut packet),
                TunnResult::Done
            ));
            if cancel {
                assert!(matches!(
                    b.encapsulate(&[], &mut packet),
                    TunnResult::WriteToNetwork(_)
                ));
            }
            let result = b.update_timers_at(received + Duration::from_secs(7), &mut packet);
            assert_eq!(matches!(result, TunnResult::WriteToNetwork(_)), !cancel);
        }
    }

    #[test]
    fn authenticated_padded_empty_resets_idle_and_cancels_no_response_handshake() {
        let (mut a, mut b) = established(AwgTimerConfig {
            persistent_keepalive: Some(TimerRange::fixed(20)),
            ..Default::default()
        });
        let mut encrypted = [0; 512];
        let mut packet = [0; 512];
        assert!(matches!(
            a.encapsulate(&ip(), &mut packet),
            TunnResult::WriteToNetwork(_)
        ));
        let start = a.timers[TimeCurrent];
        a.timers[TimeCurrent] = start + Duration::from_secs(10);
        let TunnResult::WriteToNetwork(empty) = b.encapsulate(&[0; 120], &mut encrypted) else {
            panic!("padded empty")
        };
        assert!(matches!(
            a.decapsulate(None, empty, &mut packet),
            TunnResult::Done
        ));
        assert!(matches!(
            a.update_timers_at(start + Duration::from_secs(21), &mut packet),
            TunnResult::Done
        ));
        let TunnResult::WriteToNetwork(keepalive) =
            a.update_timers_at(start + Duration::from_secs(30), &mut packet)
        else {
            panic!("idle keepalive")
        };
        assert_eq!(&keepalive[..4], &[4, 0, 0, 0]);
    }

    fn responder_rekeys(a: &mut Tunn, b: &mut Tunn) {
        let mut first = [0; 512];
        let mut second = [0; 512];
        let TunnResult::WriteToNetwork(init) = b.format_handshake_initiation(&mut first, true)
        else {
            panic!("init")
        };
        let TunnResult::WriteToNetwork(response) = a.decapsulate(None, init, &mut second) else {
            panic!("response")
        };
        let TunnResult::WriteToNetwork(confirm) = b.decapsulate(None, response, &mut first) else {
            panic!("confirm")
        };
        assert!(matches!(
            a.decapsulate(None, confirm, &mut second),
            TunnResult::Done
        ));
    }

    #[test]
    fn delayed_old_session_packet_does_not_refresh_the_new_current_session() {
        let (mut a, mut b) = established(AwgTimerConfig::default());
        let mut encrypted = [0; 256];
        let mut packet = [0; 256];
        let TunnResult::WriteToNetwork(delayed) = b.encapsulate(&ip(), &mut encrypted) else {
            panic!("old data")
        };
        let delayed = delayed.to_vec();
        let now = a.timers[TimeCurrent] + Duration::from_secs(170);
        a.timers[TimeCurrent] = now;
        b.timers[TimeCurrent] = now;
        responder_rekeys(&mut a, &mut b);
        assert!(matches!(
            a.decapsulate(None, &delayed, &mut packet),
            TunnResult::WriteToTunnelV4(..)
        ));
        assert!(matches!(
            a.update_timers_at(now, &mut packet),
            TunnResult::Done
        ));
    }

    #[test]
    fn completed_new_handshake_cancels_queued_refresh_of_old_keys() {
        let (mut a, mut b) = established(AwgTimerConfig::default());
        let now = a.timers[TimeCurrent] + Duration::from_secs(121);
        a.timers[TimeCurrent] = now;
        b.timers[TimeCurrent] = now;
        let mut packet = [0; 256];
        assert!(matches!(
            a.encapsulate(&ip(), &mut packet),
            TunnResult::WriteToNetwork(_)
        ));
        responder_rekeys(&mut a, &mut b);
        assert!(matches!(
            a.update_timers_at(now, &mut packet),
            TunnResult::Done
        ));
    }

    #[test]
    fn automatic_empty_and_queued_data_use_the_transport_padding_policy() {
        struct Padding;
        impl crate::noise::TransportPadding for Padding {
            fn padded_len(&self, len: usize) -> usize {
                len + 300
            }
        }
        let a_key = StaticSecret::from([7; 32]);
        let b_key = StaticSecret::from([9; 32]);
        let mut a = Tunn::new(a_key.clone(), PublicKey::from(&b_key), None, None, 0, None);
        let mut b = Tunn::new(b_key, PublicKey::from(&a_key), None, None, 1, None);
        let config = AwgTimerConfig {
            persistent_keepalive: Some(TimerRange::fixed(3)),
            ..Default::default()
        };
        a.set_awg_timer_config(config).unwrap();
        b.set_awg_timer_config(config).unwrap();
        a.set_transport_padding(std::sync::Arc::new(Padding))
            .unwrap();
        let mut first = [0; 2048];
        let mut second = [0; 2048];
        let TunnResult::WriteToNetwork(init) = a.encapsulate(&ip(), &mut first) else {
            panic!("init")
        };
        let TunnResult::WriteToNetwork(response) = b.decapsulate(None, init, &mut second) else {
            panic!("response")
        };
        let TunnResult::WriteToNetwork(confirm) = a.decapsulate(None, response, &mut first) else {
            panic!("confirm")
        };
        assert_eq!(
            confirm.len(),
            332,
            "automatic confirmation is padded beyond the former 256-byte timer buffer"
        );
        assert!(matches!(
            b.decapsulate(None, confirm, &mut second),
            TunnResult::Done
        ));
        let TunnResult::WriteToNetwork(queued) = a.decapsulate(None, &[], &mut first) else {
            panic!("queued IP")
        };
        assert_eq!(queued.len(), 352, "20 IP + 300 padding + 32 framing");
        let TunnResult::WriteToTunnelV4(received, _) = b.decapsulate(None, queued, &mut second)
        else {
            panic!("IP")
        };
        assert_eq!(received, ip());
        let now = a.timers[TimeCurrent] + Duration::from_secs(3);
        let TunnResult::WriteToNetwork(idle) = a.update_timers_at(now, &mut first) else {
            panic!("persistent empty")
        };
        assert_eq!(idle.len(), 332);
        assert!(matches!(
            b.decapsulate(None, idle, &mut second),
            TunnResult::Done
        ));
    }

    #[test]
    fn short_empty_output_keeps_handshake_response_and_nonce_reusable() {
        let a_key = StaticSecret::from([7; 32]);
        let b_key = StaticSecret::from([9; 32]);
        let mut a = Tunn::new(a_key.clone(), PublicKey::from(&b_key), None, None, 0, None);
        let mut b = Tunn::new(b_key, PublicKey::from(&a_key), None, None, 1, None);
        a.set_awg_timer_config(AwgTimerConfig::default()).unwrap();
        let mut first = [0; 256];
        let mut second = [0; 256];
        let mut short = [0; 31];
        let TunnResult::WriteToNetwork(init) = a.format_handshake_initiation(&mut first, false)
        else {
            panic!("init")
        };
        let TunnResult::WriteToNetwork(response) = b.decapsulate(None, init, &mut second) else {
            panic!("response")
        };
        assert!(matches!(
            a.decapsulate(None, response, &mut short),
            TunnResult::Err(WireGuardError::DestinationBufferTooSmall)
        ));
        let TunnResult::WriteToNetwork(confirm) = a.decapsulate(None, response, &mut first) else {
            panic!("same response")
        };
        assert_eq!(u64::from_le_bytes(confirm[8..16].try_into().unwrap()), 0);
        assert!(matches!(
            a.encapsulate(&[], &mut short),
            TunnResult::Err(WireGuardError::DestinationBufferTooSmall)
        ));
        let TunnResult::WriteToNetwork(empty) = a.encapsulate(&[], &mut first) else {
            panic!("empty")
        };
        assert_eq!(
            u64::from_le_bytes(empty[8..16].try_into().unwrap()),
            1,
            "buffer failure must not consume nonce"
        );
    }

    #[test]
    fn cleanup_removes_keys_after_three_expiry_intervals() {
        let (mut tunnel, _) = established(AwgTimerConfig {
            reject_after: TimerRange::fixed(30),
            ..Default::default()
        });
        let started = tunnel.timers[TimeSessionEstablished];
        let mut packet = [0; 256];
        assert!(matches!(
            tunnel.update_timers_at(started + Duration::from_secs(90), &mut packet),
            TunnResult::Err(WireGuardError::ConnectionExpired)
        ));
        assert!(tunnel.sessions.iter().all(Option::is_none));
        assert!(tunnel.handshake.is_expired());
    }
}

#[derive(Debug)]
pub struct Timers {
    /// Is the owner of the timer the initiator or the responder for the last handshake?
    is_initiator: bool,
    /// Start time of the tunnel
    time_started: Instant,
    timers: [Duration; TimerName::Top as usize],
    pub(super) session_timers: [Duration; super::N_SESSIONS],
    /// Did we receive data without sending anything back?
    want_keepalive: bool,
    /// Did we send data without hearing back?
    want_handshake: bool,
    persistent_keepalive: usize,
    /// Should this timer call reset rr function (if not a shared rr instance)
    pub(super) should_reset_rr: bool,
}

impl Timers {
    pub(super) fn new(persistent_keepalive: Option<u16>, reset_rr: bool) -> Timers {
        Timers {
            is_initiator: false,
            time_started: Instant::now(),
            timers: Default::default(),
            session_timers: Default::default(),
            want_keepalive: Default::default(),
            want_handshake: Default::default(),
            persistent_keepalive: usize::from(persistent_keepalive.unwrap_or(0)),
            should_reset_rr: reset_rr,
        }
    }

    fn is_initiator(&self) -> bool {
        self.is_initiator
    }

    // We don't really clear the timers, but we set them to the current time to
    // so the reference time frame is the same
    pub(super) fn clear(&mut self) {
        let now = Instant::now().duration_since(self.time_started);
        for t in &mut self.timers[..] {
            *t = now;
        }
        self.want_handshake = false;
        self.want_keepalive = false;
    }
}

impl Index<TimerName> for Timers {
    type Output = Duration;
    fn index(&self, index: TimerName) -> &Duration {
        &self.timers[index as usize]
    }
}

impl IndexMut<TimerName> for Timers {
    fn index_mut(&mut self, index: TimerName) -> &mut Duration {
        &mut self.timers[index as usize]
    }
}

impl Tunn {
    pub(super) fn refresh_awg_time(&mut self) {
        if let Some(awg) = &self.awg_timers {
            let now = Instant::now()
                .duration_since(self.timers.time_started)
                .max(self.timers[TimeCurrent]);
            self.timers[TimeCurrent] = now;
            for (slot, started) in self.sessions.iter_mut().zip(self.timers.session_timers) {
                if now.saturating_sub(started) > awg.config.expiry() {
                    *slot = None;
                }
            }
        }
    }

    pub(super) fn timer_tick(&mut self, timer_name: TimerName) {
        match timer_name {
            TimeLastPacketReceived => {
                self.timers.want_keepalive = true;
                self.timers.want_handshake = false;
            }
            TimeLastPacketSent => {
                self.timers.want_handshake = true;
                self.timers.want_keepalive = false;
            }
            _ => {}
        }

        let time = self.timers[TimeCurrent];
        self.timers[timer_name] = time;
    }

    pub(super) fn timer_tick_session_established(
        &mut self,
        is_initiator: bool,
        session_idx: usize,
    ) {
        self.timer_tick(TimeSessionEstablished);
        self.timers.session_timers[session_idx % crate::noise::N_SESSIONS] =
            self.timers[TimeCurrent];
        self.timers.is_initiator = is_initiator;
    }

    // We don't really clear the timers, but we set them to the current time to
    // so the reference time frame is the same
    fn clear_all(&mut self) {
        for session in &mut self.sessions {
            *session = None;
        }

        self.packet_queue.clear();

        self.timers.clear();
    }

    fn update_session_timers(&mut self, time_now: Duration) {
        let timers = &mut self.timers;

        for (i, t) in timers.session_timers.iter_mut().enumerate() {
            if time_now - *t > REJECT_AFTER_TIME {
                if let Some(session) = self.sessions[i].take() {
                    tracing::debug!(
                        message = "SESSION_EXPIRED(REJECT_AFTER_TIME)",
                        session = session.receiving_index
                    );
                }
                *t = time_now;
            }
        }
    }

    pub fn update_timers<'a>(&mut self, dst: &'a mut [u8]) -> TunnResult<'a> {
        let now = Instant::now().duration_since(self.timers.time_started);
        self.update_timers_at(now, dst)
    }

    fn update_timers_at<'a>(&mut self, now: Duration, dst: &'a mut [u8]) -> TunnResult<'a> {
        if self.awg_timers.is_some() {
            return self.update_awg_timers(now, dst);
        }
        let mut handshake_initiation_required = false;
        let mut keepalive_required = false;

        if self.timers.should_reset_rr {
            self.rate_limiter.reset_count();
        }

        // All the times are counted from tunnel initiation, for efficiency our timers are rounded
        // to a second, as there is no real benefit to having highly accurate timers.
        self.timers[TimeCurrent] = now;

        self.update_session_timers(now);

        // Load timers only once:
        let session_established = self.timers[TimeSessionEstablished];
        let handshake_started = self.timers[TimeLastHandshakeStarted];
        let aut_packet_received = self.timers[TimeLastPacketReceived];
        let aut_packet_sent = self.timers[TimeLastPacketSent];
        let data_packet_received = self.timers[TimeLastDataPacketReceived];
        let data_packet_sent = self.timers[TimeLastDataPacketSent];
        let persistent_keepalive = self.timers.persistent_keepalive;

        {
            if self.handshake.is_expired() {
                return TunnResult::Err(WireGuardError::ConnectionExpired);
            }

            // Clear cookie after COOKIE_EXPIRATION_TIME
            if self.handshake.has_cookie()
                && now - self.timers[TimeCookieReceived] >= COOKIE_EXPIRATION_TIME
            {
                self.handshake.clear_cookie();
            }

            // All ephemeral private keys and symmetric session keys are zeroed out after
            // (REJECT_AFTER_TIME * 3) ms if no new keys have been exchanged.
            if now - session_established >= REJECT_AFTER_TIME * 3 {
                tracing::error!("CONNECTION_EXPIRED(REJECT_AFTER_TIME * 3)");
                self.handshake.set_expired();
                self.clear_all();
                return TunnResult::Err(WireGuardError::ConnectionExpired);
            }

            if let Some(time_init_sent) = self.handshake.timer() {
                // Handshake Initiation Retransmission
                if now - handshake_started >= REKEY_ATTEMPT_TIME {
                    // After REKEY_ATTEMPT_TIME ms of trying to initiate a new handshake,
                    // the retries give up and cease, and clear all existing packets queued
                    // up to be sent. If a packet is explicitly queued up to be sent, then
                    // this timer is reset.
                    tracing::error!("CONNECTION_EXPIRED(REKEY_ATTEMPT_TIME)");
                    self.handshake.set_expired();
                    self.clear_all();
                    return TunnResult::Err(WireGuardError::ConnectionExpired);
                }

                if time_init_sent.elapsed() >= REKEY_TIMEOUT {
                    // We avoid using `time` here, because it can be earlier than `time_init_sent`.
                    // Once `checked_duration_since` is stable we can use that.
                    // A handshake initiation is retried after REKEY_TIMEOUT + jitter ms,
                    // if a response has not been received, where jitter is some random
                    // value between 0 and 333 ms.
                    tracing::warn!("HANDSHAKE(REKEY_TIMEOUT)");
                    handshake_initiation_required = true;
                }
            } else {
                if self.timers.is_initiator() {
                    // After sending a packet, if the sender was the original initiator
                    // of the handshake and if the current session key is REKEY_AFTER_TIME
                    // ms old, we initiate a new handshake. If the sender was the original
                    // responder of the handshake, it does not re-initiate a new handshake
                    // after REKEY_AFTER_TIME ms like the original initiator does.
                    if session_established < data_packet_sent
                        && now - session_established >= REKEY_AFTER_TIME
                    {
                        tracing::debug!("HANDSHAKE(REKEY_AFTER_TIME (on send))");
                        handshake_initiation_required = true;
                    }

                    // After receiving a packet, if the receiver was the original initiator
                    // of the handshake and if the current session key is REJECT_AFTER_TIME
                    // - KEEPALIVE_TIMEOUT - REKEY_TIMEOUT ms old, we initiate a new
                    // handshake.
                    if session_established < data_packet_received
                        && now - session_established
                            >= REJECT_AFTER_TIME - KEEPALIVE_TIMEOUT - REKEY_TIMEOUT
                    {
                        tracing::warn!(
                            "HANDSHAKE(REJECT_AFTER_TIME - KEEPALIVE_TIMEOUT - \
                        REKEY_TIMEOUT \
                        (on receive))"
                        );
                        handshake_initiation_required = true;
                    }
                }

                // If we have sent a packet to a given peer but have not received a
                // packet after from that peer for (KEEPALIVE + REKEY_TIMEOUT) ms,
                // we initiate a new handshake.
                if data_packet_sent > aut_packet_received
                    && now - aut_packet_received >= KEEPALIVE_TIMEOUT + REKEY_TIMEOUT
                    && mem::replace(&mut self.timers.want_handshake, false)
                {
                    tracing::warn!("HANDSHAKE(KEEPALIVE + REKEY_TIMEOUT)");
                    handshake_initiation_required = true;
                }

                if !handshake_initiation_required {
                    // If a packet has been received from a given peer, but we have not sent one back
                    // to the given peer in KEEPALIVE ms, we send an empty packet.
                    if data_packet_received > aut_packet_sent
                        && now - aut_packet_sent >= KEEPALIVE_TIMEOUT
                        && mem::replace(&mut self.timers.want_keepalive, false)
                    {
                        tracing::debug!("KEEPALIVE(KEEPALIVE_TIMEOUT)");
                        keepalive_required = true;
                    }

                    // Persistent KEEPALIVE
                    if persistent_keepalive > 0
                        && (now - self.timers[TimePersistentKeepalive]
                            >= Duration::from_secs(persistent_keepalive as _))
                    {
                        tracing::debug!("KEEPALIVE(PERSISTENT_KEEPALIVE)");
                        self.timer_tick(TimePersistentKeepalive);
                        keepalive_required = true;
                    }
                }
            }
        }

        if handshake_initiation_required {
            return self.format_handshake_initiation(dst, true);
        }

        if keepalive_required {
            return self.encapsulate(&[], dst);
        }

        TunnResult::Done
    }

    fn update_awg_timers<'a>(&mut self, now: Duration, dst: &'a mut [u8]) -> TunnResult<'a> {
        use super::awg::{Action, AwgTimers};
        self.timers[TimeCurrent] = now.max(self.timers[TimeCurrent]);
        self.refresh_awg_time();
        let now = self.timers[TimeCurrent];
        if self.timers.should_reset_rr {
            self.rate_limiter.reset_count();
        }
        if self.handshake.has_cookie()
            && now.saturating_sub(self.timers[TimeCookieReceived]) >= COOKIE_EXPIRATION_TIME
        {
            self.handshake.clear_cookie();
        }
        let action = match self.awg_timers.as_mut() {
            Some(awg) => awg.poll(now),
            None => return TunnResult::Done,
        };
        match action {
            Action::None => TunnResult::Done,
            Action::Initiate => self.format_handshake_initiation_inner(dst, true, false),
            Action::Retry => self.format_handshake_initiation_inner(dst, true, true),
            Action::StopRetries => {
                self.packet_queue.clear();
                TunnResult::Err(WireGuardError::ConnectionExpired)
            }
            Action::ClearKeys => {
                self.handshake.set_expired();
                self.clear_all();
                if let Some(awg) = &mut self.awg_timers {
                    *awg = AwgTimers::new(awg.config);
                }
                TunnResult::Err(WireGuardError::ConnectionExpired)
            }
            Action::Keepalive => {
                let result = self.encapsulate(&[], dst);
                if let Some(awg) = &mut self.awg_timers {
                    awg.keepalive_sent(now);
                }
                result
            }
        }
    }

    pub fn time_since_last_handshake(&self) -> Option<Duration> {
        let current_session = self.current;
        if self.sessions[current_session % super::N_SESSIONS].is_some() {
            let duration_since_tun_start = Instant::now().duration_since(self.timers.time_started);
            let duration_since_session_established = self.timers[TimeSessionEstablished];

            Some(duration_since_tun_start - duration_since_session_established)
        } else {
            None
        }
    }

    pub fn persistent_keepalive(&self) -> Option<u16> {
        let keepalive = self.timers.persistent_keepalive;

        if keepalive > 0 {
            Some(keepalive as u16)
        } else {
            None
        }
    }
}
