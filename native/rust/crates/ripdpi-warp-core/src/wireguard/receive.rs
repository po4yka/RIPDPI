use boringtun::noise::awg_cookies::CookieSource;
use boringtun::noise::errors::WireGuardError;
use boringtun::noise::{ReceiveOutcome, Tunn, TunnResult};

use super::keys::apply_reserved_bytes;
use crate::amneziawg::AwgWireCodec;

/// All backend output slices start at the beginning of the caller's output buffer.
/// Store only their lengths so candidate attempts can reuse that buffer without copies.
pub(crate) enum ReceiveOutput {
    None,
    Network(usize),
    Ip(usize),
    Error(WireGuardError),
}

impl From<TunnResult<'_>> for ReceiveOutput {
    fn from(result: TunnResult<'_>) -> Self {
        match result {
            TunnResult::Done => Self::None,
            TunnResult::WriteToNetwork(packet) => Self::Network(packet.len()),
            TunnResult::WriteToTunnelV4(packet, _) | TunnResult::WriteToTunnelV6(packet, _) => Self::Ip(packet.len()),
            TunnResult::Err(error) => Self::Error(error),
        }
    }
}

/// Authenticate bounded layout candidates and commit only the selected conversion context.
/// A malformed authenticated transport is terminal: its replay counter was consumed.
/// Cookie challenges are provisional until no authenticated candidate succeeds.
pub(crate) fn receive_packet(
    peer: &mut Tunn,
    codec: Option<&AwgWireCodec>,
    source: CookieSource,
    datagram: &mut [u8],
    output: &mut [u8],
) -> (ReceiveOutput, ReceiveOutcome) {
    if datagram.is_empty() {
        return (ReceiveOutput::None, ReceiveOutcome::Rejected);
    }
    let Some(codec) = codec else {
        apply_reserved_bytes(datagram, [0; 3]);
        let (result, outcome) = peer.decapsulate_from(Some(source), datagram, output);
        return (result.into(), outcome);
    };
    let candidates = codec.decode_candidates(datagram);
    let mut challenge = None;
    let mut rejected = ReceiveOutput::None;
    for (index, candidate) in candidates.iter().enumerate() {
        let (result, outcome) = peer.decapsulate_from(Some(source), &candidate.packet, output);
        let result = ReceiveOutput::from(result);
        match outcome {
            ReceiveOutcome::Rejected => rejected = result,
            ReceiveOutcome::CookieChallenge => {
                if challenge.is_none()
                    && let ReceiveOutput::Network(length) = result
                {
                    challenge = Some((index, output[..length].to_vec()));
                }
            }
            _ => {
                codec.commit_candidate(candidate);
                return (result, outcome);
            }
        }
    }
    if let Some((index, packet)) = challenge {
        codec.commit_candidate(&candidates[index]);
        output[..packet.len()].copy_from_slice(&packet);
        return (ReceiveOutput::Network(packet.len()), ReceiveOutcome::CookieChallenge);
    }
    (rejected, ReceiveOutcome::Rejected)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::amneziawg::{AwgMacKeys, AwgParams};
    use crate::config::{AwgV3Config, WarpAmneziaConfig};
    use blake2::digest::{Digest, KeyInit, Mac, consts::U16};
    use blake2::{Blake2s256, Blake2sMac};
    use boringtun::noise::awg::AwgTimerConfig;
    use boringtun::noise::rate_limiter::RateLimiter;
    use boringtun::x25519::{PublicKey, StaticSecret};
    use std::sync::Arc;

    fn source() -> CookieSource {
        CookieSource::Udp(([127, 0, 0, 1], 9000).into())
    }

    fn network(result: TunnResult<'_>) -> Vec<u8> {
        let TunnResult::WriteToNetwork(packet) = result else { panic!("network output: {result:?}") };
        packet.to_vec()
    }

    fn exchange(peer: &mut Tunn, codec: &AwgWireCodec, mut wire: Vec<u8>) -> Option<Vec<u8>> {
        let mut out = [0; 2048];
        match receive_packet(peer, Some(codec), source(), &mut wire, &mut out).0 {
            ReceiveOutput::Network(length) => Some(codec.encode(&out[..length]).unwrap()),
            ReceiveOutput::None => None,
            ReceiveOutput::Error(error) => panic!("exchange: {error:?}"),
            ReceiveOutput::Ip(_) => panic!("unexpected IP"),
        }
    }

    fn pair(s2: i32) -> (Tunn, Tunn, AwgWireCodec, AwgWireCodec) {
        let a_key = StaticSecret::from([7; 32]);
        let b_key = StaticSecret::from([9; 32]);
        let a_public = PublicKey::from(&a_key);
        let b_public = PublicKey::from(&b_key);
        let mut a = Tunn::new(a_key, b_public, None, None, 0, Some(Arc::new(RateLimiter::new(&a_public, 0))));
        let mut b = Tunn::new(b_key, a_public, None, None, 1, None);
        a.set_awg_timer_config(AwgTimerConfig::default()).unwrap();
        let config = WarpAmneziaConfig {
            enabled: true,
            h1: "101".into(),
            h2: "202".into(),
            h3: "303".into(),
            h4: "404".into(),
            s2,
            s4: 200,
            v3: AwgV3Config { random_trailers: true, ..Default::default() },
            ..Default::default()
        };
        let params = AwgParams::from_config(&config, &[""; 5]).unwrap();
        let a_codec =
            AwgWireCodec::new_authenticated(params.clone(), AwgMacKeys::new(a_public.to_bytes(), b_public.to_bytes()));
        let b_codec =
            AwgWireCodec::new_authenticated(params, AwgMacKeys::new(b_public.to_bytes(), a_public.to_bytes()));
        let mut out = [0; 2048];
        let initial = b_codec.encode(&network(b.format_handshake_initiation(&mut out, true))).unwrap();
        let cookie = exchange(&mut a, &a_codec, initial).unwrap();
        assert!(exchange(&mut b, &b_codec, cookie).is_none());
        let retry = b_codec.encode(&network(b.format_handshake_initiation(&mut out, true))).unwrap();
        let response = exchange(&mut a, &a_codec, retry).unwrap();
        let confirm = exchange(&mut b, &b_codec, response).unwrap();
        assert!(exchange(&mut a, &a_codec, confirm).is_none());
        (a, b, a_codec, b_codec)
    }

    // Independently construct a MAC1-valid but Noise-invalid initiation in the random prefix.
    fn false_initiation(wire: &mut [u8]) {
        wire[..148].fill(0);
        wire[..4].copy_from_slice(&101u32.to_le_bytes());
        wire[4..8].copy_from_slice(&777u32.to_le_bytes());
        let public = PublicKey::from(&StaticSecret::from([7; 32]));
        let mut key = Blake2s256::new();
        Digest::update(&mut key, b"mac1----");
        Digest::update(&mut key, public.as_bytes());
        let mut mac = <Blake2sMac<U16> as KeyInit>::new_from_slice(&key.finalize()).unwrap();
        Mac::update(&mut mac, &wire[..116]);
        wire[116..132].copy_from_slice(&mac.finalize().into_bytes());
    }

    #[test]
    fn valid_transport_wins_a_false_handshake_prefix_and_delivers_once() {
        let (mut receiver, mut sender, receiver_codec, sender_codec) = pair(0);
        let ip = [0x45, 0, 0, 20, 0, 0, 0, 0, 64, 17, 0, 0, 1, 1, 1, 1, 10, 0, 0, 1];
        let mut out = [0; 2048];
        let data = network(sender.encapsulate(&ip, &mut out));
        let mut wire = sender_codec.encode(&data).unwrap();
        false_initiation(&mut wire);
        let candidates = receiver_codec.decode_candidates(&wire);
        assert_eq!(candidates.len(), 2, "unequal S permits both H1 and H4 layouts under RT");
        let (ReceiveOutput::Ip(length), ReceiveOutcome::TransportIp) =
            receive_packet(&mut receiver, Some(&receiver_codec), source(), &mut wire, &mut out)
        else {
            panic!("authenticated transport must win")
        };
        assert_eq!(&out[..length], ip);
        let (result, outcome) = receive_packet(&mut receiver, Some(&receiver_codec), source(), &mut wire, &mut out);
        assert!(!matches!(result, ReceiveOutput::Ip(_)), "replay must not deliver twice");
        assert!(!matches!(outcome, ReceiveOutcome::TransportIp | ReceiveOutcome::TransportEmpty));
    }

    #[test]
    fn authenticated_malformed_transport_is_terminal_without_cookie_fallback() {
        let (mut receiver, mut sender, receiver_codec, sender_codec) = pair(0);
        let mut out = [0; 2048];
        let mut wire = sender_codec.encode(&network(sender.encapsulate(&[1; 120], &mut out))).unwrap();
        false_initiation(&mut wire);
        let (result, outcome) = receive_packet(&mut receiver, Some(&receiver_codec), source(), &mut wire, &mut out);
        assert!(matches!(result, ReceiveOutput::Error(WireGuardError::InvalidPacket)));
        assert_eq!(outcome, ReceiveOutcome::TransportMalformed);
    }

    #[test]
    fn provisional_cookie_does_not_hide_an_authenticated_response() {
        let (mut receiver, mut sender, receiver_codec, sender_codec) = pair(200);
        let mut out = [0; 2048];
        let init = receiver_codec.encode(&network(receiver.format_handshake_initiation(&mut out, true))).unwrap();
        let mut response = exchange(&mut sender, &sender_codec, init).unwrap();
        false_initiation(&mut response);
        let candidates = receiver_codec.decode_candidates(&response);
        assert_eq!(candidates.len(), 2);
        let (result, outcome) = receive_packet(&mut receiver, Some(&receiver_codec), source(), &mut response, &mut out);
        assert_eq!(outcome, ReceiveOutcome::HandshakeResponse);
        assert!(matches!(result, ReceiveOutput::Network(_)));
        assert_eq!(&out[..4], &[4, 0, 0, 0], "send confirmation, not the provisional cookie");
    }
}
