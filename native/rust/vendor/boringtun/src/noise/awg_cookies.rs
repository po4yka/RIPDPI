// Copyright (c) 2026 RIPDPI contributors.
// SPDX-License-Identifier: BSD-3-Clause

use std::net::SocketAddr;
use std::time::Duration;

/// Actual datagram source, or an opaque identifier for an established carrier.
/// Do not substitute a configured remote address for the observed UDP source.
#[derive(Clone, Copy)]
pub enum CookieSource {
    Udp(SocketAddr),
    Connection([u8; 16]),
}

impl CookieSource {
    pub(super) fn bytes(self) -> [u8; 19] {
        let mut bytes = [0; 19];
        match self {
            Self::Udp(SocketAddr::V4(address)) => {
                bytes[0] = 4;
                bytes[1..5].copy_from_slice(&address.ip().octets());
                bytes[17..19].copy_from_slice(&address.port().to_be_bytes());
            }
            Self::Udp(SocketAddr::V6(address)) => {
                bytes[0] = 6;
                bytes[1..17].copy_from_slice(&address.ip().octets());
                bytes[17..19].copy_from_slice(&address.port().to_be_bytes());
            }
            Self::Connection(id) => {
                bytes[0] = 1;
                bytes[1..17].copy_from_slice(&id);
            }
        }
        bytes
    }
}

/// Local per-peer safety limits. Invalid MAC1 does not spend these budgets.
/// This is intentionally independent of the backend's under-load detector.
pub(super) struct Admission {
    pub(super) disabled: bool,
    started: Duration,
    handshakes: u8,
    challenges: u8,
}

impl Admission {
    pub(super) fn new(disabled: bool) -> Self {
        Self {
            disabled,
            started: Duration::ZERO,
            handshakes: 0,
            challenges: 0,
        }
    }

    pub(super) fn advance(&mut self, now: Duration) {
        if now.saturating_sub(self.started) >= Duration::from_secs(1) {
            self.started = now;
            self.handshakes = 0;
            self.challenges = 0;
        }
    }

    pub(super) fn handshake(&mut self) -> bool {
        if self.handshakes >= 10 {
            return false;
        }
        self.handshakes += 1;
        true
    }

    pub(super) fn challenge(&mut self) -> bool {
        if self.challenges >= 10 {
            return false;
        }
        self.challenges += 1;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::noise::awg::AwgTimerConfig;
    use crate::noise::handshake::{b2s_hash, b2s_keyed_mac_16, LABEL_MAC1};
    use crate::noise::rate_limiter::RateLimiter;
    use crate::noise::timers::TimerName::TimeCurrent;
    use crate::noise::{ReceiveOutcome as O, Tunn, TunnResult, WireGuardError};
    use crate::x25519::{PublicKey, StaticSecret};
    use std::sync::Arc;

    fn pair(disabled: bool, limit: u64) -> (Tunn, Tunn) {
        let a_key = StaticSecret::from([7; 32]);
        let b_key = StaticSecret::from([9; 32]);
        let b_public = PublicKey::from(&b_key);
        let mut a = Tunn::new(a_key.clone(), b_public, None, None, 0, None);
        let mut b = Tunn::new(
            b_key,
            PublicKey::from(&a_key),
            None,
            None,
            1,
            Some(Arc::new(RateLimiter::new(&b_public, limit))),
        );
        for peer in [&mut a, &mut b] {
            peer.set_awg_timer_config(AwgTimerConfig::default())
                .unwrap();
            peer.set_awg_disable_cookies(disabled).unwrap();
        }
        (a, b)
    }

    fn init(a: &mut Tunn) -> Vec<u8> {
        let mut output = [0; 512];
        let TunnResult::WriteToNetwork(packet) = a.format_handshake_initiation(&mut output, true)
        else {
            panic!("init")
        };
        packet.to_vec()
    }

    fn source(port: u16) -> CookieSource {
        CookieSource::Udp(([127, 0, 0, 1], port).into())
    }

    #[test]
    fn cookie_flag_preserves_mac1_noise_and_bounded_handshake_work() {
        for disabled in [false, true] {
            for limit in [0, 100] {
                let (mut a, mut b) = pair(disabled, limit);
                let valid = init(&mut a);
                let mut invalid = valid.clone();
                invalid[40] ^= 1;
                let mut out = [0; 512];
                for _ in 0..20 {
                    let (result, outcome) =
                        b.decapsulate_from(Some(source(9000)), &invalid, &mut out);
                    assert!(matches!(
                        result,
                        TunnResult::Err(WireGuardError::InvalidMac)
                    ));
                    assert_eq!(outcome, O::Rejected);
                }
                assert_eq!(b.awg_admission.as_ref().unwrap().handshakes, 0);
                assert_eq!(b.awg_admission.as_ref().unwrap().challenges, 0);
                let (result, outcome) = b.decapsulate_from(Some(source(9000)), &valid, &mut out);
                assert!(matches!(result, TunnResult::WriteToNetwork(_)));
                assert_eq!(
                    outcome,
                    if disabled || limit > 0 {
                        O::HandshakeInitiation
                    } else {
                        O::CookieChallenge
                    }
                );
            }
            let (mut a, mut b) = pair(disabled, 0);
            let mut forged = init(&mut a);
            forged[40] ^= 1;
            let key = b2s_hash(
                LABEL_MAC1,
                PublicKey::from(&StaticSecret::from([9; 32])).as_bytes(),
            );
            let mac = b2s_keyed_mac_16(&key, &forged[..116]);
            forged[116..132].copy_from_slice(&mac);
            let mut out = [0; 512];
            for _ in 0..10 {
                let (result, outcome) = b.decapsulate_from(Some(source(9000)), &forged, &mut out);
                if disabled {
                    assert!(matches!(result, TunnResult::Err(_)));
                    assert_eq!(outcome, O::Rejected, "MAC1 alone cannot authenticate Noise");
                } else {
                    assert!(matches!(result, TunnResult::WriteToNetwork(_)));
                    assert_eq!(outcome, O::CookieChallenge);
                }
            }
            assert!(matches!(
                b.decapsulate_from(Some(source(9000)), &forged, &mut out).0,
                TunnResult::Err(WireGuardError::UnderLoad)
            ));
            b.timers[TimeCurrent] = Duration::from_secs(2);
            let (result, outcome) = b.decapsulate_from(Some(source(9000)), &forged, &mut out);
            if disabled {
                assert!(!matches!(
                    result,
                    TunnResult::Err(WireGuardError::UnderLoad)
                ));
                assert_eq!(outcome, O::Rejected);
            } else {
                assert_eq!(outcome, O::CookieChallenge);
            }
        }
    }

    #[test]
    fn cookie_retry_binds_udp_port_and_connection_identity_and_cookie_is_not_peer_proof() {
        for (original, changed) in [
            (source(9000), source(9001)),
            (
                source(9000),
                CookieSource::Udp(([127, 0, 0, 2], 9000).into()),
            ),
            (
                CookieSource::Udp("[::1]:9000".parse().unwrap()),
                CookieSource::Udp("[::2]:9000".parse().unwrap()),
            ),
            (
                CookieSource::Connection([7; 16]),
                CookieSource::Connection([8; 16]),
            ),
        ] {
            let (mut a, mut b) = pair(false, 0);
            let first = init(&mut a);
            let mut out = [0; 512];
            let (TunnResult::WriteToNetwork(cookie), O::CookieChallenge) =
                b.decapsulate_from(Some(original), &first, &mut out)
            else {
                panic!("challenge")
            };
            let cookie = cookie.to_vec();
            let before = a.timers[TimeCurrent];
            let mut corrupt = cookie.clone();
            *corrupt.last_mut().unwrap() ^= 1;
            assert_eq!(
                a.decapsulate_from(Some(original), &corrupt, &mut out).1,
                O::Rejected
            );
            assert!(!a.handshake.has_cookie());
            assert_eq!(
                a.decapsulate_from(Some(original), &cookie, &mut out).1,
                O::CookieAccepted
            );
            assert!(a.handshake.has_cookie());
            assert!(a.time_since_last_handshake().is_none());
            assert!(a.timers[TimeCurrent] >= before);
            a.timers[TimeCurrent] = Duration::from_secs(6);
            let retry = init(&mut a);
            assert_eq!(
                b.decapsulate_from(Some(changed), &retry, &mut out).1,
                O::CookieChallenge
            );
            b.awg_admission.as_mut().unwrap().handshakes = 10;
            assert!(
                matches!(
                    b.decapsulate_from(Some(original), &retry, &mut out).0,
                    TunnResult::Err(WireGuardError::UnderLoad)
                ),
                "valid MAC2 cannot bypass the expensive-work budget"
            );
            b.timers[TimeCurrent] = Duration::from_secs(2);
            assert_eq!(
                b.decapsulate_from(Some(original), &retry, &mut out).1,
                O::HandshakeInitiation
            );
        }
    }

    #[test]
    fn incoming_cookie_is_consumed_with_either_local_flag_without_peer_readiness() {
        for disabled in [false, true] {
            let (mut a, mut b) = pair(false, 0);
            a.set_awg_disable_cookies(disabled).unwrap();
            let initial = init(&mut a);
            let mut out = [0; 512];
            let (TunnResult::WriteToNetwork(cookie), _) =
                b.decapsulate_from(Some(source(9000)), &initial, &mut out)
            else {
                panic!("cookie")
            };
            let cookie = cookie.to_vec();
            assert_eq!(
                a.decapsulate_from(Some(source(9000)), &cookie, &mut out).1,
                O::CookieAccepted
            );
            assert!(a.time_since_last_handshake().is_none());
        }
    }
    #[test]
    fn response_cookie_policy_and_authenticated_transport_survive_load() {
        for disabled in [false, true] {
            let (mut a, mut b) = pair(disabled, 100);
            let first = init(&mut a);
            let mut out = [0; 512];
            let (TunnResult::WriteToNetwork(response), _) =
                b.decapsulate_from(Some(source(9000)), &first, &mut out)
            else {
                panic!("response")
            };
            let response = response.to_vec();
            let a_public = PublicKey::from(&StaticSecret::from([7; 32]));
            a.rate_limiter = Arc::new(RateLimiter::new(&a_public, 0));
            let mut forged = response.clone();
            forged[20] ^= 1;
            let mac = b2s_keyed_mac_16(&b2s_hash(LABEL_MAC1, a_public.as_bytes()), &forged[..60]);
            forged[60..76].copy_from_slice(&mac);
            let (_, outcome) = a.decapsulate_from(Some(source(9001)), &forged, &mut out);
            assert_eq!(
                outcome,
                if disabled {
                    O::Rejected
                } else {
                    O::CookieChallenge
                }
            );
            assert!(a.time_since_last_handshake().is_none());
            let (result, outcome) = a.decapsulate_from(Some(source(9001)), &response, &mut out);
            if disabled {
                assert_eq!(outcome, O::HandshakeResponse);
                assert!(matches!(result, TunnResult::WriteToNetwork(_)));
            } else {
                assert_eq!(outcome, O::CookieChallenge);
                let TunnResult::WriteToNetwork(cookie) = result else {
                    panic!("response challenge")
                };
                let cookie = cookie.to_vec();
                assert_eq!(
                    b.decapsulate_from(Some(source(9000)), &cookie, &mut out).1,
                    O::CookieAccepted
                );
                a.timers[TimeCurrent] = Duration::from_secs(6);
                let retry = init(&mut a);
                let (TunnResult::WriteToNetwork(response), _) =
                    b.decapsulate_from(Some(source(9000)), &retry, &mut out)
                else {
                    panic!("response retry")
                };
                let response = response.to_vec();
                assert_eq!(
                    a.decapsulate_from(Some(source(9001)), &response, &mut out)
                        .1,
                    O::HandshakeResponse
                );
            }
            // Transport is outside the expensive-handshake budget.
            b.awg_admission.as_mut().unwrap().handshakes = 10;
            let ip = [
                0x45, 0, 0, 20, 0, 0, 0, 0, 64, 17, 0, 0, 1, 1, 1, 1, 10, 0, 0, 1,
            ];
            let TunnResult::WriteToNetwork(data) = a.encapsulate(&ip, &mut out) else {
                panic!("data")
            };
            let data = data.to_vec();
            assert_eq!(
                b.decapsulate_from(Some(source(9000)), &data, &mut out).1,
                O::TransportIp
            );
        }
    }
}
