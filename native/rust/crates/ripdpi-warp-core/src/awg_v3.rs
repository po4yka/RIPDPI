//! AmneziaWG 3.x envelope (amneziawg-go v3.1.20260828): header protection,
//! random trailers, transport content padding, and timer ranges.
//!
//! The byte-level rules and their upstream sources are in
//! `docs/native/amneziawg-3x-wire-semantics.md`. Noise, MACs, and cookies are
//! unchanged; this module only wraps and unwraps a complete AWG message.

use std::sync::atomic::{AtomicU32, Ordering};

use boringtun::noise::awg::{AwgTimerConfig, TimerRange};

use chacha20::ChaCha20;
use chacha20::cipher::{KeyIvInit, StreamCipher};

use crate::amneziawg::{AwgParamsError, WG_MESSAGE_TYPE_COUNT, fill_random, rand_u32};
use crate::awg_profile::HeaderRange;
use crate::config::WarpAmneziaConfig;

/// `HeaderCipherNonceSize`: the first 12 bytes of every datagram are the nonce.
pub(crate) const HEADER_NONCE_SIZE: usize = 12;
/// `DefaultUdpWindow`: the initial and per-endpoint-reset UDP window.
const DEFAULT_UDP_WINDOW: u32 = 500;
/// WireGuard message sizes for init, response, cookie, and an empty transport.
const MESSAGE_SIZES: [usize; WG_MESSAGE_TYPE_COUNT] = [148, 92, 64, 32];
const TRANSPORT_INDEX: usize = 3;
const COOKIE_INDEX: usize = 2;
const TRANSPORT_HEADER_SIZE: usize = 16;
const AEAD_TAG_SIZE: usize = 16;
/// Upper bound of every 3.x range key (amneziawg-tools parses them as u16 ranges).
const RANGE_LIMIT: u32 = u16::MAX as u32;

/// Validated 3.x knobs. Every field is off by default, which keeps the AWG 1.x/2.0 wire output unchanged.
#[derive(Clone, Default, PartialEq, Eq)]
pub(crate) struct AwgV3Params {
    header_key: Option<[u8; 32]>,
    content_padding: Option<HeaderRange>,
    random_trailers: bool,
    timers: AwgTimerConfig,
}

impl std::fmt::Debug for AwgV3Params {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AwgV3Params")
            .field("header_protection", &self.header_key.is_some())
            .field("content_padding", &self.content_padding)
            .field("random_trailers", &self.random_trailers)
            .field("timers", &self.timers)
            .finish()
    }
}

impl AwgV3Params {
    /// Validate the 3.x keys of `cfg`. `size_padding` is the validated `S1..S4`.
    pub(crate) fn from_config(
        cfg: &WarpAmneziaConfig,
        size_padding: &[u32; WG_MESSAGE_TYPE_COUNT],
    ) -> Result<Self, AwgParamsError> {
        let header_key = match cfg.v3.header_protection_key.0.as_str() {
            "" => None,
            text => {
                let key = crate::wireguard::decode_key(text).map_err(|_| AwgParamsError::InvalidHeaderProtectionKey)?;
                // amneziawg-go treats an all-zero key as "header protection off".
                (key != [0; 32]).then_some(key)
            }
        };
        if header_key.is_some()
            && let Some(index) = size_padding.iter().position(|&size| (size as usize) < HEADER_NONCE_SIZE)
        {
            return Err(AwgParamsError::HeaderProtectionPaddingTooSmall { index, value: size_padding[index] });
        }
        let content_padding = parse_range("ContentPaddingAddition", &cfg.v3.content_padding_addition)?;
        let defaults = AwgTimerConfig::default();
        let timers = AwgTimerConfig {
            rekey_after: timer_range("RekeyAfterTime", &cfg.v3.rekey_after_time, defaults.rekey_after)?,
            rekey_timeout: timer_range("RekeyTimeout", &cfg.v3.rekey_timeout, defaults.rekey_timeout)?,
            reject_after: timer_range("RejectAfterTime", &cfg.v3.reject_after_time, defaults.reject_after)?,
            keepalive_timeout: timer_range("KeepaliveTimeout", &cfg.v3.keepalive_timeout, defaults.keepalive_timeout)?,
            max_handshake_attempts: timer_range(
                "MaxHandshakeAttempts",
                &cfg.v3.max_handshake_attempts,
                defaults.max_handshake_attempts,
            )?,
            persistent_keepalive: None,
        };
        Ok(Self { header_key, content_padding, random_trailers: cfg.v3.random_trailers, timers })
    }

    pub(crate) fn timer_config(&self) -> AwgTimerConfig {
        self.timers
    }

    /// Wrap a final AWG message: `S` random prefix, header protection, then a
    /// random trailer for handshake and cookie messages.
    /// `None` only when header protection lacks a 12-byte prefix, which validation rejects.
    pub(crate) fn seal(
        &self,
        message: Vec<u8>,
        index: usize,
        pad_len: usize,
        udp_window: &UdpWindow,
    ) -> Option<Vec<u8>> {
        if pad_len == 0 && self.header_key.is_none() && !self.random_trailers {
            return Some(message);
        }
        let trailer_len = if index == TRANSPORT_INDEX || !self.random_trailers {
            0
        } else {
            // A responder cookie uses the device default window; peers use the learned one.
            let window = if index == COOKIE_INDEX { DEFAULT_UDP_WINDOW } else { udp_window.get() };
            random_below((window as usize).saturating_sub(pad_len + message.len()))
        };
        let mut out = vec![0u8; pad_len + message.len() + trailer_len];
        fill_random(&mut out[..pad_len]);
        let body_end = pad_len + message.len();
        out[pad_len..body_end].copy_from_slice(&message);
        fill_random(&mut out[body_end..]);
        if let Some(key) = &self.header_key {
            let (prefix, body) = out.split_at_mut(pad_len);
            let masked = masked_len(index, message.len());
            apply_keystream(key, prefix.first_chunk()?, &mut body[..masked]);
        }
        Some(out)
    }

    /// Return every bounded layout candidate. Header/length matches are not authentication.
    pub(crate) fn open_candidates(
        &self,
        packet: &[u8],
        size_padding: &[u32; WG_MESSAGE_TYPE_COUNT],
        headers: &[HeaderRange; WG_MESSAGE_TYPE_COUNT],
    ) -> Vec<(usize, Vec<u8>)> {
        let type_mask = match &self.header_key {
            Some(key) => {
                let Some(nonce) = packet.first_chunk() else { return Vec::new() };
                let mut mask = [0u8; 4];
                apply_keystream(key, nonce, &mut mask);
                u32::from_le_bytes(mask)
            }
            None => 0,
        };
        let mut candidates = Vec::new();
        // Try transport first. A publicly forgeable cookie must not hide valid encrypted data.
        for index in [TRANSPORT_INDEX, 0, 1, COOKIE_INDEX] {
            let pad = size_padding[index] as usize;
            let expected = pad + MESSAGE_SIZES[index];
            let size_matches = if index == TRANSPORT_INDEX {
                packet.len() >= expected
            } else {
                packet.len() == expected || (self.random_trailers && packet.len() > expected)
            };
            if !size_matches
                || !packet
                    .get(pad..pad + 4)
                    .and_then(|bytes| bytes.try_into().ok())
                    .is_some_and(|bytes| headers[index].contains(u32::from_le_bytes(bytes) ^ type_mask))
            {
                continue;
            }
            let end = if index == TRANSPORT_INDEX { packet.len() } else { expected };
            let mut message = packet[pad..end].to_vec();
            if let Some(key) = &self.header_key {
                let masked = masked_len(index, message.len());
                // The nonce was checked above before a candidate could be produced.
                if let Some(nonce) = packet.first_chunk() {
                    apply_keystream(key, nonce, &mut message[..masked]);
                }
            }
            candidates.push((index, message));
        }
        candidates
    }

    #[cfg(test)]
    pub(crate) fn open(
        &self,
        packet: &[u8],
        size_padding: &[u32; WG_MESSAGE_TYPE_COUNT],
        headers: &[HeaderRange; WG_MESSAGE_TYPE_COUNT],
    ) -> Option<(usize, Vec<u8>)> {
        self.open_candidates(packet, size_padding, headers).into_iter().next()
    }

    /// Zero bytes to append to a transport plaintext before encryption
    /// (`randomPaddingAddition`, else `randomTrailer`, else 16-byte alignment).
    /// Alignment stops at the configured inner MTU, as in Go calculatePaddingSize.
    pub(crate) fn transport_padding(&self, plaintext_len: usize, s4: usize, udp_window: &UdpWindow) -> usize {
        let packet_size = plaintext_len + MESSAGE_SIZES[TRANSPORT_INDEX] + s4;
        udp_window.raise(packet_size);
        if self.content_padding.is_none() && !self.random_trailers {
            let mtu = udp_window.mtu;
            let last = if plaintext_len > mtu { plaintext_len % mtu } else { plaintext_len };
            return last.next_multiple_of(16).min(mtu) - last;
        }
        let Some(space) = (udp_window.get() as usize).checked_sub(packet_size) else {
            return 0;
        };
        match self.content_padding {
            Some(range) => (range.sample() as usize).min(space),
            None => random_below(space),
        }
    }
}

/// The per-peer `udpWindow`: the largest authenticated datagram size seen, used to bound padding.
#[derive(Debug)]
pub(crate) struct UdpWindow {
    size: AtomicU32,
    limit: u32,
    mtu: usize,
}

/// Largest IP packet a tunnel carries (Ethernet MTU); the window never grows past it plus framing.
const MAX_TUNNEL_IP_PACKET: u32 = 1500;

impl Default for UdpWindow {
    fn default() -> Self {
        Self::for_transport_prefix(0)
    }
}

impl UdpWindow {
    /// A window that starts at 500 and is capped at `S4 + 32 + 1500`.
    pub(crate) fn for_transport_prefix(s4: u32) -> Self {
        Self::for_transport_mtu(s4, MAX_TUNNEL_IP_PACKET as usize)
    }

    pub(crate) fn for_transport_mtu(s4: u32, mtu: usize) -> Self {
        let mtu = mtu.clamp(1280, MAX_TUNNEL_IP_PACKET as usize);
        let limit = mtu as u32 + MESSAGE_SIZES[TRANSPORT_INDEX] as u32 + s4;
        Self { size: AtomicU32::new(DEFAULT_UDP_WINDOW), limit, mtu }
    }

    // Relaxed: the window is a size heuristic; no other memory is published through it.
    fn get(&self) -> u32 {
        self.size.load(Ordering::Relaxed)
    }

    fn raise(&self, size: usize) {
        let size = u32::try_from(size).unwrap_or(u32::MAX).min(self.limit);
        self.size.fetch_max(size, Ordering::Relaxed);
    }

    /// Learn from a received transport datagram (`S4 + header + plaintext`).
    /// Call only after boringtun authenticated it, like `receive.go:518-521`.
    pub(crate) fn observe_inbound_transport(&self, datagram_len: usize) {
        self.raise(datagram_len.saturating_sub(AEAD_TAG_SIZE));
    }
}

/// Bytes covered by header protection: the 16-byte transport header, or the whole handshake message.
fn masked_len(index: usize, message_len: usize) -> usize {
    if index == TRANSPORT_INDEX { TRANSPORT_HEADER_SIZE.min(message_len) } else { message_len }
}

/// XOR `data` with the IETF ChaCha20 keystream (block counter 0) of `key` and the 12-byte `nonce`.
fn apply_keystream(key: &[u8; 32], nonce: &[u8; HEADER_NONCE_SIZE], data: &mut [u8]) {
    ChaCha20::new(&(*key).into(), &(*nonce).into()).apply_keystream(data);
}

/// `fastrandn(n)`: uniform-enough value in `[0, n)`, or `0` when `n == 0`.
fn random_below(n: usize) -> usize {
    if n == 0 { 0 } else { rand_u32() as usize % n }
}

/// Parse an optional `lo` or `lo-hi` range. Empty and `0` mean "not set" (`UintRange.IsZero`).
fn timer_range(name: &'static str, text: &str, default: TimerRange) -> Result<TimerRange, AwgParamsError> {
    match parse_range(name, text)? {
        None => Ok(default),
        Some(range) => {
            TimerRange::new(range.low() as u16, range.high() as u16).ok_or(AwgParamsError::InvalidRange { name })
        }
    }
}

fn parse_range(name: &'static str, text: &str) -> Result<Option<HeaderRange>, AwgParamsError> {
    if text.is_empty() {
        return Ok(None);
    }
    let range = HeaderRange::parse(text)
        .filter(|range| range.high() <= RANGE_LIMIT)
        .ok_or(AwgParamsError::InvalidRange { name })?;
    Ok((!range.is_singleton(0)).then_some(range))
}

#[cfg(test)]
#[path = "awg_v3_tests.rs"]
mod tests;
