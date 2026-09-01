// SPDX-License-Identifier: BSD-3-Clause AND MIT
//
// AmneziaWG handshake-obfuscation layer for `ripdpi-warp-core`.
//
// This module is the handshake-obfuscation layer described in the epic
// "AmneziaWG outbound support" (task
// `fork-boringtun-and-add-amneziawg-handshake-obfuscation`).
//
// # Crate-vs-module resolution
//
// The task spec sketches an internal `ripdpi-amneziawg-core` crate forked
// from `boringtun`. The task's own `Scope` contract, however, restricts
// edits to `ripdpi-warp-core/**` and `ripdpi-warp-android/**` -- it does
// not permit registering a brand-new workspace member. `ripdpi-warp-core`
// *already* depends on `boringtun` and *already* owns a WireGuard tunnel
// (`wireguard::WireGuardTunnel`) plus an embryonic Amnezia wire codec
// (`amnezia::AmneziaCodec`). The Noise primitives therefore do not need to
// be forked at all: `boringtun` is consumed as-is and AmneziaWG is purely
// an *additive* obfuscation layer wrapped around it. Implementing that
// layer as a first-class module here keeps the change inside the declared
// scope, avoids vendoring a second copy of the Noise handshake, and matches
// the existing crate layout (`amnezia.rs`, `wireguard/`, `virtual_iface/`).
//
// # License attribution
//
// `boringtun` is BSD-3-Clause; the AmneziaWG protocol delta ported from
// `amnezia-vpn/amneziawg-go` (pin `v0.2.18`) is MIT. This file contains
// only the AWG obfuscation delta (junk-packet sequencing, H1-H4 magic
// header substitution, S1-S4 size padding, AWG 2.0 I1-I5 special junk
// intervals). It does not vendor any Noise primitive, so no BSD-3 source
// is copied here; the dual SPDX header above records both licenses for the
// crate as a whole, since the crate links `boringtun` (BSD-3) and ports
// `amneziawg-go` semantics (MIT).
//
// # Reference
//
// Semantics mirror `amnezia-vpn/amneziawg-go` v0.2.18:
//   * `device/peer.go`, `device/send.go` -- `junkPacketCount` (Jc) junk
//     packets, each sized uniformly in `[Jmin, Jmax]`, sent before the
//     real initiation.
//   * `device/noise-protocol.go` -- `InitiationPacketMagicHeader` (H1),
//     `ResponsePacketMagicHeader` (H2), `UnderloadPacketMagicHeader` (H3),
//     `TransportPacketMagicHeader` (H4) replace the WireGuard type bytes
//     `0x01..0x04`; `S1..S4` random prefixes wrap the complete packet.
//   * `device/device.go` -- AWG 2.0 `I1..I5` "special junk" intervals:
//     fixed hex-encoded junk frames injected at the start of the flow.
//
// Note: v0.2.18 fixed the pre-v0.2.18 bug where keepalive packets bypassed
// `S4` padding. RIPDPI was never affected -- its uniform-codec design pads
// *every* `WriteToNetwork` frame (keepalives included), so the unpadded
// keepalive could not occur here regardless of the upstream pin.

use crate::config::WarpAmneziaConfig;
use blake2::digest::consts::U16;
use blake2::digest::{Digest, KeyInit as Blake2KeyInit, Mac};
use blake2::{Blake2s256, Blake2sMac};
use chacha20poly1305::aead::{Aead, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};
use subtle::ConstantTimeEq;

/// Upstream semantics revision used by RIPDPI's independent Rust codec.
pub(crate) const AMNEZIAWG_UPSTREAM_SEMANTICS_VERSION: &str = "v0.2.18";

/// Remains true until the cross-repo arm64 S3/S4 policy records a physically
/// revalidated safe floor. A release-note claim alone must never flip this.
const ARM64_S34_GUARD_REQUIRED: bool = true;

/// Total number of WireGuard message types AmneziaWG obfuscates: initiation
/// (`0x01`), response (`0x02`), cookie-reply / underload (`0x03`), and
/// transport (`0x04`).
pub(crate) const WG_MESSAGE_TYPE_COUNT: usize = 4;

/// Number of AWG 2.0 special-junk slots (`I1..I5`).
pub(crate) const SPECIAL_JUNK_SLOTS: usize = 5;

/// Read random bytes from the OS CSPRNG into `buf`.
pub(crate) fn fill_random(buf: &mut [u8]) {
    getrandom::fill(buf).expect("getrandom failed");
}

/// Read a random `u32` from the OS CSPRNG.
pub(crate) fn rand_u32() -> u32 {
    let mut buf = [0u8; 4];
    fill_random(&mut buf);
    u32::from_le_bytes(buf)
}

/// Errors produced while validating a [`WarpAmneziaConfig`] into [`AwgParams`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum AwgParamsError {
    /// `jmin` exceeds `jmax`; the junk-packet size range would be empty.
    JunkRangeInverted { jmin: u32, jmax: u32 },
    /// A junk packet of the requested size would not fit inside a UDP
    /// datagram alongside the rest of the WireGuard flow.
    JunkSizeTooLarge { jmax: u32, limit: u32 },
    /// A magic header value does not fit in the 4-byte WireGuard type field.
    HeaderOutOfRange { index: usize, value: i64 },
    /// Two message types were assigned the same magic header, which would
    /// make the receive-side classifier ambiguous.
    HeaderCollision { a: usize, b: usize, value: u32 },
    /// A size-padding value is larger than a single UDP datagram can carry.
    PaddingTooLarge { index: usize, value: i64, limit: u32 },
    /// Non-zero S3/S4 are blocked on Android arm64 until a safe upstream floor
    /// is physically revalidated and coordinated with the deploy-side guard.
    Arm64S34VersionFloor { s3: i32, s4: i32 },
    /// A special-junk (`I1..I5`) hex string is malformed.
    SpecialJunkNotHex { index: usize },
}

impl std::fmt::Display for AwgParamsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::JunkRangeInverted { jmin, jmax } => {
                write!(f, "AmneziaWG jmin ({jmin}) is greater than jmax ({jmax})")
            }
            Self::JunkSizeTooLarge { jmax, limit } => {
                write!(f, "AmneziaWG jmax ({jmax}) exceeds the junk-packet size limit ({limit})")
            }
            Self::HeaderOutOfRange { index, value } => {
                write!(f, "AmneziaWG h{} value ({value}) does not fit in a u32 header", index + 1)
            }
            Self::HeaderCollision { a, b, value } => {
                write!(f, "AmneziaWG h{} and h{} share the same magic header ({value})", a + 1, b + 1)
            }
            Self::PaddingTooLarge { index, value, limit } => {
                write!(f, "AmneziaWG s{} padding ({value}) exceeds the per-packet limit ({limit})", index + 1)
            }
            Self::Arm64S34VersionFloor { s3, s4 } => {
                write!(
                    f,
                    "AmneziaWG S3/S4 must remain zero on Android arm64 for reference semantics {AMNEZIAWG_UPSTREAM_SEMANTICS_VERSION} (got S3={s3}, S4={s4})"
                )
            }
            Self::SpecialJunkNotHex { index } => {
                write!(f, "AmneziaWG i{} is not valid hex", index + 1)
            }
        }
    }
}

impl std::error::Error for AwgParamsError {}

/// Upper bound on a single junk packet so it always fits one UDP datagram
/// without IP fragmentation surprises. Matches the conservative ceiling
/// `amneziawg-go` enforces in practice for `Jmax`.
pub(crate) const JUNK_PACKET_SIZE_LIMIT: u32 = 1280;

/// Upper bound on a single `S1..S4` padding run. Padding prefixes the complete
/// WireGuard packet inside one datagram, so it shares the junk-packet ceiling.
pub(crate) const PADDING_SIZE_LIMIT: u32 = 1280;

/// Validated AmneziaWG obfuscation parameters.
///
/// Built from the on-the-wire [`WarpAmneziaConfig`] (which uses signed
/// integers because it is shared verbatim with the Kotlin config model)
/// via [`AwgParams::from_config_for_platform`]. Once constructed, every field is in a
/// range the obfuscation paths can rely on without re-checking.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct AwgParams {
    /// `Jc` -- number of junk packets to emit before the real initiation.
    junk_packet_count: u32,
    /// `Jmin` -- inclusive lower bound on a junk packet's size in bytes.
    junk_packet_min_size: u32,
    /// `Jmax` -- inclusive upper bound on a junk packet's size in bytes.
    junk_packet_max_size: u32,
    /// `H1..H4` -- magic headers that replace WireGuard type bytes `0x01..0x04`.
    magic_headers: [u32; WG_MESSAGE_TYPE_COUNT],
    /// `S1..S4` -- bytes of random padding prefixed to each message type.
    size_padding: [u32; WG_MESSAGE_TYPE_COUNT],
    /// `I1..I5` -- AWG 2.0 special-junk frames, already hex-decoded. An empty
    /// `Vec` means that slot is unset.
    special_junk: [Vec<u8>; SPECIAL_JUNK_SLOTS],
}

impl AwgParams {
    /// Validate a [`WarpAmneziaConfig`] into [`AwgParams`].
    ///
    /// The `i1..i5` hex strings are optional and default to empty (`&[]`);
    /// the native WARP runtime config does not carry them, so callers pass
    /// them explicitly. Pass `&["", "", "", "", ""]` for "no special junk".
    #[cfg(test)]
    pub(crate) fn from_config(cfg: &WarpAmneziaConfig, special_junk_hex: &[&str]) -> Result<Self, AwgParamsError> {
        Self::from_config_for_platform(cfg, special_junk_hex, cfg!(all(target_os = "android", target_arch = "aarch64")))
    }

    /// Platform-explicit validator used by the shared codec. Production passes
    /// the compile target; tests inject both sides of the policy gate.
    pub(crate) fn from_config_for_platform(
        cfg: &WarpAmneziaConfig,
        special_junk_hex: &[&str],
        is_android_arm64: bool,
    ) -> Result<Self, AwgParamsError> {
        if ARM64_S34_GUARD_REQUIRED && is_android_arm64 && (cfg.s3 != 0 || cfg.s4 != 0) {
            return Err(AwgParamsError::Arm64S34VersionFloor { s3: cfg.s3, s4: cfg.s4 });
        }
        let junk_packet_count = cfg.jc.max(0) as u32;
        let junk_packet_min_size = cfg.jmin.max(0) as u32;
        let junk_packet_max_size = cfg.jmax.max(0) as u32;

        if junk_packet_count > 0 {
            if junk_packet_min_size > junk_packet_max_size {
                return Err(AwgParamsError::JunkRangeInverted {
                    jmin: junk_packet_min_size,
                    jmax: junk_packet_max_size,
                });
            }
            if junk_packet_max_size > JUNK_PACKET_SIZE_LIMIT {
                return Err(AwgParamsError::JunkSizeTooLarge {
                    jmax: junk_packet_max_size,
                    limit: JUNK_PACKET_SIZE_LIMIT,
                });
            }
        }

        let raw_headers = [cfg.h1, cfg.h2, cfg.h3, cfg.h4];
        let mut magic_headers = [0u32; WG_MESSAGE_TYPE_COUNT];
        for (index, raw) in raw_headers.iter().copied().enumerate() {
            let value = u32::try_from(raw).map_err(|_| AwgParamsError::HeaderOutOfRange { index, value: raw })?;
            magic_headers[index] = value;
        }
        // A zero means "use the standard WireGuard type". Reject collisions
        // across those effective headers too: e.g. H1=2 collides with an
        // unset H2 and makes receive-side classification ambiguous.
        for a in 0..WG_MESSAGE_TYPE_COUNT {
            for b in (a + 1)..WG_MESSAGE_TYPE_COUNT {
                let effective_a = if magic_headers[a] == 0 { (a + 1) as u32 } else { magic_headers[a] };
                let effective_b = if magic_headers[b] == 0 { (b + 1) as u32 } else { magic_headers[b] };
                if effective_a == effective_b {
                    return Err(AwgParamsError::HeaderCollision { a, b, value: effective_a });
                }
            }
        }

        let raw_padding = [cfg.s1, cfg.s2, cfg.s3, cfg.s4];
        let mut size_padding = [0u32; WG_MESSAGE_TYPE_COUNT];
        for (index, raw) in raw_padding.iter().copied().enumerate() {
            let value = raw.max(0) as u32;
            if value > PADDING_SIZE_LIMIT {
                return Err(AwgParamsError::PaddingTooLarge { index, value: raw as i64, limit: PADDING_SIZE_LIMIT });
            }
            size_padding[index] = value;
        }

        let mut special_junk: [Vec<u8>; SPECIAL_JUNK_SLOTS] = Default::default();
        for (index, hex) in special_junk_hex.iter().take(SPECIAL_JUNK_SLOTS).enumerate() {
            let trimmed = hex.trim();
            if trimmed.is_empty() {
                continue;
            }
            special_junk[index] = decode_hex(trimmed).ok_or(AwgParamsError::SpecialJunkNotHex { index })?;
        }

        Ok(Self {
            junk_packet_count,
            junk_packet_min_size,
            junk_packet_max_size,
            magic_headers,
            size_padding,
            special_junk,
        })
    }

    /// `true` when no obfuscation is configured at all: no junk packets, no
    /// padding, no header substitution, no special junk. In this state the
    /// wire output is byte-identical to upstream WireGuard.
    #[cfg(test)]
    pub(crate) fn is_passthrough(&self) -> bool {
        self.junk_packet_count == 0
            && self.size_padding.iter().all(|&s| s == 0)
            && !self.headers_active()
            && self.special_junk.iter().all(Vec::is_empty)
    }

    /// `true` when at least one magic header is set. WireGuard type bytes are
    /// only rewritten when headers are active; otherwise the codec leaves the
    /// `0x01..0x04` bytes untouched (preserving the byte-identity invariant
    /// for the headers-unset case).
    pub(crate) fn headers_active(&self) -> bool {
        self.magic_headers.iter().any(|&h| h != 0)
    }

    fn wire_header(&self, index: usize) -> u32 {
        match self.magic_headers[index] {
            0 => (index + 1) as u32,
            header => header,
        }
    }

    /// Draw a junk-packet size uniformly from `[Jmin, Jmax]` using `rng`.
    /// `rng` returns the next random `u32`; production passes [`rand_u32`].
    fn junk_packet_size(&self, rng: &mut impl FnMut() -> u32) -> usize {
        let span = self.junk_packet_max_size - self.junk_packet_min_size + 1;
        (self.junk_packet_min_size + rng() % span) as usize
    }

    /// Build the `Jc` junk packets that precede the real handshake
    /// initiation. Each packet is random bytes sized uniformly in
    /// `[Jmin, Jmax]`. `rng` supplies entropy; production passes
    /// [`rand_u32`]. Returns an empty `Vec` when `Jc == 0`.
    pub(crate) fn build_junk_packets(&self, rng: &mut impl FnMut() -> u32) -> Vec<Vec<u8>> {
        let mut packets = Vec::with_capacity(self.junk_packet_count as usize);
        for _ in 0..self.junk_packet_count {
            let size = self.junk_packet_size(rng);
            let mut packet = vec![0u8; size];
            fill_random(&mut packet);
            packets.push(packet);
        }
        packets
    }

    /// Build the AWG 2.0 special-junk (`I1..I5`) frames in slot order.
    /// Unset slots are skipped. These are fixed, hex-decoded byte strings --
    /// they are *not* randomized -- so the obfuscator emits them verbatim at
    /// the start of the flow, matching `amneziawg-go` v0.2.18.
    pub(crate) fn special_junk_packets(&self) -> Vec<Vec<u8>> {
        self.special_junk.iter().filter(|frame| !frame.is_empty()).cloned().collect()
    }

    /// The full pre-initiation prelude: special-junk frames (`I1..I5`)
    /// followed by `Jc` random junk packets, in the order `amneziawg-go`
    /// emits them on the wire. `rng` supplies entropy for the random junk.
    pub(crate) fn handshake_prelude(&self, rng: &mut impl FnMut() -> u32) -> Vec<Vec<u8>> {
        let mut prelude = self.special_junk_packets();
        prelude.extend(self.build_junk_packets(rng));
        prelude
    }
}

/// AmneziaWG packet wire codec: applies H1-H4 magic-header substitution and
/// S1-S4 size padding on send, and reverses both on receive.
///
/// # Wire layout
///
/// ```text
/// send([WG type (4 bytes LE) | body])
///   -> [ S{type} random prefix | H{type} (4 bytes LE) | body ]
/// ```
///
/// This mirrors `amneziawg-go` v0.2.18 `device/send.go`: H1/H2 are present
/// before `CookieGenerator.AddMacs`, then S1/S2 are prefixed after MACs are
/// calculated. H3/H4 use the same header and prefix positions.
///
/// boringtun builds a vanilla packet first, so an authenticated codec also
/// recalculates handshake MAC1/MAC2 over the AWG header. On receive it verifies
/// the AWG MAC before reconstructing the vanilla header and MAC expected by
/// boringtun. Cookie replies are translated between the two MAC1 associated
/// data values so the upstream under-load challenge remains functional.
///
/// # Byte-identity invariant
///
/// With `Jc=0`, `S1..S4=0`, and `H1..H4` unset, encode and decode return the
/// complete packet unchanged, byte-for-byte with upstream WireGuard.
#[derive(Debug, Clone, Copy)]
pub(crate) struct AwgMacKeys {
    local_public_key: [u8; 32],
    peer_public_key: [u8; 32],
}

impl AwgMacKeys {
    pub(crate) fn new(local_public_key: [u8; 32], peer_public_key: [u8; 32]) -> Self {
        Self { local_public_key, peer_public_key }
    }
}

#[derive(Debug, Clone, Copy)]
struct HandshakeMacState {
    sender_index: u32,
    vanilla_mac1: [u8; 16],
    wire_mac1: [u8; 16],
}

#[derive(Debug, Clone, Copy)]
struct TimedCookie {
    value: [u8; 16],
    received_at: Instant,
}

impl TimedCookie {
    fn is_fresh(self) -> bool {
        self.received_at.elapsed() < COOKIE_EXPIRATION
    }
}

#[derive(Debug, Default)]
struct AwgMacState {
    last_outbound: Option<HandshakeMacState>,
    last_inbound: Option<HandshakeMacState>,
    received_cookie: Option<TimedCookie>,
    issued_cookie: Option<TimedCookie>,
}

#[derive(Debug)]
pub(crate) struct AwgWireCodec {
    params: AwgParams,
    mac_keys: Option<AwgMacKeys>,
    mac_state: Mutex<AwgMacState>,
}

impl AwgWireCodec {
    pub(crate) fn new(params: AwgParams) -> Self {
        Self { params, mac_keys: None, mac_state: Mutex::new(AwgMacState::default()) }
    }

    pub(crate) fn new_authenticated(params: AwgParams, mac_keys: AwgMacKeys) -> Self {
        Self { params, mac_keys: Some(mac_keys), mac_state: Mutex::new(AwgMacState::default()) }
    }

    pub(crate) fn params(&self) -> &AwgParams {
        &self.params
    }

    /// Map a WireGuard type byte (`0x01..=0x04`) to its `0..=3` index.
    fn type_index(wg_type: u8) -> Option<usize> {
        match wg_type {
            1..=4 => Some((wg_type - 1) as usize),
            _ => None,
        }
    }

    fn mac_state(&self) -> MutexGuard<'_, AwgMacState> {
        self.mac_state.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Obfuscate a fully built WireGuard packet for sending.
    ///
    /// The complete four-byte type field is replaced, preserving the upstream
    /// packet length before prefix padding. Cookie replies without matching
    /// handshake state are rejected (`None`) rather than emitted unauthenticated.
    #[cfg(test)]
    pub(crate) fn encode(&self, packet: &[u8]) -> Option<Vec<u8>> {
        self.encode_with_reserved(packet, [0; 3])
    }

    /// Like [`AwgWireCodec::encode`], but overlays the 3 WireGuard reserved
    /// bytes (`packet[1..4]`) with `reserved` during the single output copy,
    /// avoiding a redundant per-packet `to_vec` at the call site.
    ///
    pub(crate) fn encode_with_reserved(&self, packet: &[u8], reserved: [u8; 3]) -> Option<Vec<u8>> {
        if packet.len() < 4 {
            return Some(packet.to_vec());
        }
        let wg_type = packet[0];
        let Some(index) = Self::type_index(wg_type) else {
            return Some(packet.to_vec());
        };

        if index == 2 && self.mac_keys.is_some() {
            return self.encode_cookie_reply(packet, index);
        }

        let mut wire_packet = packet.to_vec();
        if self.params.headers_active() {
            wire_packet[..4].copy_from_slice(&self.params.wire_header(index).to_le_bytes());
        } else {
            wire_packet[1..4].copy_from_slice(&reserved);
        }

        if index <= 1 {
            self.rewrite_outbound_handshake_macs(&mut wire_packet);
        }
        Some(self.prefix_padding(wire_packet, index))
    }

    /// Reverse [`AwgWireCodec::encode`] for a packet received from the peer.
    ///
    /// Returns a complete vanilla WireGuard packet or `None` when the packet
    /// has an unknown layout or fails AWG MAC/cookie authentication.
    pub(crate) fn decode(&self, packet: &[u8]) -> Option<Vec<u8>> {
        let index = self.classify_wire_packet(packet)?;
        let pad_len = self.params.size_padding[index] as usize;
        let mut vanilla_packet = packet.get(pad_len..)?.to_vec();

        if self.params.headers_active() {
            vanilla_packet[..4].copy_from_slice(&((index + 1) as u32).to_le_bytes());
        }
        if index == 2 && self.mac_keys.is_some() {
            return self.decode_cookie_reply(packet.get(pad_len..)?, vanilla_packet);
        }
        if index <= 1 && !self.rewrite_inbound_handshake_macs(packet.get(pad_len..)?, &mut vanilla_packet) {
            return None;
        }
        Some(vanilla_packet)
    }

    fn classify_wire_packet(&self, packet: &[u8]) -> Option<usize> {
        (0..WG_MESSAGE_TYPE_COUNT).find(|&index| {
            let pad_len = self.params.size_padding[index] as usize;
            let Some(body) = packet.get(pad_len..) else {
                return false;
            };
            if !packet_size_matches(index, body.len()) || body.len() < 4 {
                return false;
            }
            u32::from_le_bytes(body[..4].try_into().expect("four-byte header checked"))
                == self.params.wire_header(index)
        })
    }

    fn prefix_padding(&self, packet: Vec<u8>, index: usize) -> Vec<u8> {
        let pad_len = self.params.size_padding[index] as usize;
        if pad_len == 0 {
            return packet;
        }
        let mut out = vec![0u8; pad_len];
        fill_random(&mut out);
        out.extend_from_slice(&packet);
        out
    }

    fn rewrite_outbound_handshake_macs(&self, packet: &mut [u8]) {
        let Some(keys) = self.mac_keys else {
            return;
        };
        let Some((mac1_offset, mac2_offset)) = handshake_mac_offsets(packet.len()) else {
            return;
        };
        let vanilla_mac1 = packet[mac1_offset..mac2_offset].try_into().expect("16-byte MAC1 range");
        let wire_mac1 = keyed_blake2s_16(&mac1_key(keys.peer_public_key), &packet[..mac1_offset]);
        packet[mac1_offset..mac2_offset].copy_from_slice(&wire_mac1);

        let mut state = self.mac_state();
        state.received_cookie = state.received_cookie.filter(|cookie| cookie.is_fresh());
        let cookie = state.received_cookie.map(|cookie| cookie.value);
        let wire_mac2 = cookie.map_or([0; 16], |cookie| keyed_blake2s_16(&cookie, &packet[..mac2_offset]));
        packet[mac2_offset..].copy_from_slice(&wire_mac2);
        state.last_outbound = Some(HandshakeMacState {
            sender_index: u32::from_le_bytes(packet[4..8].try_into().expect("handshake sender index")),
            vanilla_mac1,
            wire_mac1,
        });
    }

    fn rewrite_inbound_handshake_macs(&self, wire_packet: &[u8], vanilla_packet: &mut [u8]) -> bool {
        let Some(keys) = self.mac_keys else {
            return true;
        };
        let Some((mac1_offset, mac2_offset)) = handshake_mac_offsets(wire_packet.len()) else {
            return false;
        };
        let wire_mac1: [u8; 16] = wire_packet[mac1_offset..mac2_offset].try_into().expect("16-byte MAC1 range");
        let expected_wire_mac1 = keyed_blake2s_16(&mac1_key(keys.local_public_key), &wire_packet[..mac1_offset]);
        if !bool::from(wire_mac1.ct_eq(&expected_wire_mac1)) {
            return false;
        }

        let vanilla_mac1 = keyed_blake2s_16(&mac1_key(keys.local_public_key), &vanilla_packet[..mac1_offset]);
        vanilla_packet[mac1_offset..mac2_offset].copy_from_slice(&vanilla_mac1);

        let wire_mac2: [u8; 16] = wire_packet[mac2_offset..].try_into().expect("16-byte MAC2 range");
        let mut state = self.mac_state();
        state.issued_cookie = state.issued_cookie.filter(|cookie| cookie.is_fresh());
        let issued_cookie = state.issued_cookie.map(|cookie| cookie.value);
        if bool::from(wire_mac2.ct_eq(&[0; 16])) {
            vanilla_packet[mac2_offset..].fill(0);
        } else {
            let Some(cookie) = issued_cookie else {
                return false;
            };
            let expected_wire_mac2 = keyed_blake2s_16(&cookie, &wire_packet[..mac2_offset]);
            if !bool::from(wire_mac2.ct_eq(&expected_wire_mac2)) {
                return false;
            }
            let vanilla_mac2 = keyed_blake2s_16(&cookie, &vanilla_packet[..mac2_offset]);
            vanilla_packet[mac2_offset..].copy_from_slice(&vanilla_mac2);
        }
        state.last_inbound = Some(HandshakeMacState {
            sender_index: u32::from_le_bytes(wire_packet[4..8].try_into().expect("handshake sender index")),
            vanilla_mac1,
            wire_mac1,
        });
        true
    }

    fn encode_cookie_reply(&self, packet: &[u8], index: usize) -> Option<Vec<u8>> {
        if packet.len() != COOKIE_REPLY_SIZE {
            return None;
        }
        let keys = self.mac_keys?;
        let receiver = u32::from_le_bytes(packet[4..8].try_into().ok()?);
        let mut state = self.mac_state();
        let handshake = state.last_inbound.filter(|handshake| handshake.sender_index == receiver)?;
        let cookie = decrypt_cookie(packet, cookie_key(keys.local_public_key), handshake.vanilla_mac1)?;
        state.issued_cookie = Some(TimedCookie { value: cookie, received_at: Instant::now() });
        drop(state);

        let wire_packet = build_cookie_reply(
            self.params.wire_header(index),
            receiver,
            cookie,
            keys.local_public_key,
            handshake.wire_mac1,
        )?;
        Some(self.prefix_padding(wire_packet, index))
    }

    fn decode_cookie_reply(&self, wire_packet: &[u8], _vanilla_packet: Vec<u8>) -> Option<Vec<u8>> {
        if wire_packet.len() != COOKIE_REPLY_SIZE {
            return None;
        }
        let keys = self.mac_keys?;
        let receiver = u32::from_le_bytes(wire_packet[4..8].try_into().ok()?);
        let mut state = self.mac_state();
        let handshake = state.last_outbound.filter(|handshake| handshake.sender_index == receiver)?;
        let cookie = decrypt_cookie(wire_packet, cookie_key(keys.peer_public_key), handshake.wire_mac1)?;
        state.received_cookie = Some(TimedCookie { value: cookie, received_at: Instant::now() });
        drop(state);

        build_cookie_reply(3, receiver, cookie, keys.peer_public_key, handshake.vanilla_mac1)
    }
}

const HANDSHAKE_INIT_SIZE: usize = 148;
const HANDSHAKE_RESPONSE_SIZE: usize = 92;
const COOKIE_REPLY_SIZE: usize = 64;
const TRANSPORT_MIN_SIZE: usize = 32;
const COOKIE_EXPIRATION: Duration = Duration::from_secs(120);

fn packet_size_matches(index: usize, size: usize) -> bool {
    match index {
        0 => size == HANDSHAKE_INIT_SIZE,
        1 => size == HANDSHAKE_RESPONSE_SIZE,
        2 => size == COOKIE_REPLY_SIZE,
        3 => size >= TRANSPORT_MIN_SIZE,
        _ => false,
    }
}

fn handshake_mac_offsets(size: usize) -> Option<(usize, usize)> {
    matches!(size, HANDSHAKE_INIT_SIZE | HANDSHAKE_RESPONSE_SIZE).then_some((size - 32, size - 16))
}

fn mac1_key(public_key: [u8; 32]) -> [u8; 32] {
    blake2s_hash(b"mac1----", public_key)
}

fn cookie_key(public_key: [u8; 32]) -> [u8; 32] {
    blake2s_hash(b"cookie--", public_key)
}

fn blake2s_hash(label: &[u8], public_key: [u8; 32]) -> [u8; 32] {
    let mut hasher = Blake2s256::new();
    Digest::update(&mut hasher, label);
    Digest::update(&mut hasher, public_key);
    hasher.finalize().into()
}

fn keyed_blake2s_16(key: &[u8], message: &[u8]) -> [u8; 16] {
    type Blake2sMac128 = Blake2sMac<U16>;
    let mut mac = <Blake2sMac128 as Blake2KeyInit>::new_from_slice(key).expect("WireGuard BLAKE2s key length is valid");
    Mac::update(&mut mac, message);
    mac.finalize().into_bytes().into()
}

fn decrypt_cookie(packet: &[u8], key: [u8; 32], aad: [u8; 16]) -> Option<[u8; 16]> {
    let nonce = XNonce::try_from(packet.get(8..32)?).ok()?;
    let cipher = XChaCha20Poly1305::new_from_slice(&key).ok()?;
    let plaintext = cipher.decrypt(&nonce, Payload { msg: packet.get(32..64)?, aad: &aad }).ok()?;
    plaintext.try_into().ok()
}

fn build_cookie_reply(
    header: u32,
    receiver: u32,
    cookie: [u8; 16],
    public_key: [u8; 32],
    aad: [u8; 16],
) -> Option<Vec<u8>> {
    let mut nonce_bytes = [0u8; 24];
    fill_random(&mut nonce_bytes);
    let nonce = XNonce::from(nonce_bytes);
    let cipher = XChaCha20Poly1305::new_from_slice(&cookie_key(public_key)).ok()?;
    let encrypted = cipher.encrypt(&nonce, Payload { msg: &cookie, aad: &aad }).ok()?;
    if encrypted.len() != 32 {
        return None;
    }
    let mut packet = vec![0u8; COOKIE_REPLY_SIZE];
    packet[..4].copy_from_slice(&header.to_le_bytes());
    packet[4..8].copy_from_slice(&receiver.to_le_bytes());
    packet[8..32].copy_from_slice(&nonce_bytes);
    packet[32..].copy_from_slice(&encrypted);
    Some(packet)
}

/// Decode a lowercase/uppercase hex string into bytes. Returns `None` on any
/// non-hex character or an odd length. Used for AWG 2.0 `I1..I5` frames.
fn decode_hex(input: &str) -> Option<Vec<u8>> {
    if !input.len().is_multiple_of(2) {
        return None;
    }
    let mut out = Vec::with_capacity(input.len() / 2);
    let bytes = input.as_bytes();
    for pair in bytes.chunks_exact(2) {
        let hi = (pair[0] as char).to_digit(16)?;
        let lo = (pair[1] as char).to_digit(16)?;
        out.push(((hi << 4) | lo) as u8);
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deterministic RNG: yields the supplied values in order, then repeats
    /// the last one. Lets junk-size draws be asserted exactly.
    fn seq_rng(values: Vec<u32>) -> impl FnMut() -> u32 {
        let mut idx = 0usize;
        move || {
            let value = values.get(idx).copied().unwrap_or_else(|| *values.last().unwrap());
            idx += 1;
            value
        }
    }

    fn cfg(jc: i32, jmin: i32, jmax: i32, h: [i64; 4], s: [i32; 4]) -> WarpAmneziaConfig {
        WarpAmneziaConfig {
            enabled: true,
            jc,
            jmin,
            jmax,
            h1: h[0],
            h2: h[1],
            h3: h[2],
            h4: h[3],
            s1: s[0],
            s2: s[1],
            s3: s[2],
            s4: s[3],
            ..Default::default()
        }
    }

    fn no_special() -> [&'static str; 5] {
        ["", "", "", "", ""]
    }

    // --- decode_hex ---------------------------------------------------------

    #[test]
    fn decode_hex_round_trips_lower_and_upper() {
        assert_eq!(decode_hex("00ff10AB"), Some(vec![0x00, 0xFF, 0x10, 0xAB]));
        assert_eq!(decode_hex(""), Some(vec![]));
    }

    #[test]
    fn decode_hex_rejects_odd_length_and_non_hex() {
        assert_eq!(decode_hex("abc"), None);
        assert_eq!(decode_hex("zz"), None);
    }

    // --- AwgParams validation ----------------------------------------------

    #[test]
    fn from_config_rejects_inverted_junk_range() {
        let c = cfg(2, 100, 40, [0; 4], [0; 4]);
        assert_eq!(
            AwgParams::from_config(&c, &no_special()),
            Err(AwgParamsError::JunkRangeInverted { jmin: 100, jmax: 40 }),
        );
    }

    #[test]
    fn from_config_allows_inverted_junk_range_when_count_is_zero() {
        // No junk packets are emitted, so the (unused) range is not validated.
        let c = cfg(0, 100, 40, [0; 4], [0; 4]);
        assert!(AwgParams::from_config(&c, &no_special()).is_ok());
    }

    #[test]
    fn from_config_rejects_oversized_jmax() {
        let c = cfg(1, 1, (JUNK_PACKET_SIZE_LIMIT + 1) as i32, [0; 4], [0; 4]);
        assert_eq!(
            AwgParams::from_config(&c, &no_special()),
            Err(AwgParamsError::JunkSizeTooLarge { jmax: JUNK_PACKET_SIZE_LIMIT + 1, limit: JUNK_PACKET_SIZE_LIMIT }),
        );
    }

    #[test]
    fn from_config_rejects_header_out_of_u32_range() {
        let c = cfg(0, 0, 0, [i64::from(u32::MAX) + 1, 0, 0, 0], [0; 4]);
        assert_eq!(
            AwgParams::from_config(&c, &no_special()),
            Err(AwgParamsError::HeaderOutOfRange { index: 0, value: i64::from(u32::MAX) + 1 }),
        );
    }

    #[test]
    fn from_config_rejects_colliding_headers() {
        let c = cfg(0, 0, 0, [0x11_22_33_44, 0x11_22_33_44, 0, 0], [0; 4]);
        assert_eq!(
            AwgParams::from_config(&c, &no_special()),
            Err(AwgParamsError::HeaderCollision { a: 0, b: 1, value: 0x11_22_33_44 }),
        );
    }

    #[test]
    fn from_config_rejects_custom_header_colliding_with_unset_standard_type() {
        let c = cfg(0, 0, 0, [2, 0, 0, 0], [0; 4]);
        assert_eq!(
            AwgParams::from_config(&c, &no_special()),
            Err(AwgParamsError::HeaderCollision { a: 0, b: 1, value: 2 }),
        );
    }

    #[test]
    fn from_config_rejects_oversized_padding() {
        let c = cfg(0, 0, 0, [0; 4], [(PADDING_SIZE_LIMIT + 1) as i32, 0, 0, 0]);
        assert_eq!(
            AwgParams::from_config(&c, &no_special()),
            Err(AwgParamsError::PaddingTooLarge {
                index: 0,
                value: i64::from(PADDING_SIZE_LIMIT + 1),
                limit: PADDING_SIZE_LIMIT,
            }),
        );
    }

    #[test]
    fn android_arm64_guard_rejects_non_zero_s3_or_s4() {
        for padding in [[0, 0, 1, 0], [0, 0, 0, 1], [0, 0, 1, 1]] {
            let config = cfg(0, 0, 0, [0; 4], padding);
            let error = AwgParams::from_config_for_platform(&config, &[""; 5], true).unwrap_err();
            assert!(matches!(error, AwgParamsError::Arm64S34VersionFloor { .. }));
        }
    }

    #[test]
    fn android_arm64_guard_accepts_zero_s3_and_s4() {
        let config = cfg(0, 0, 0, [0; 4], [8, 12, 0, 0]);
        assert!(AwgParams::from_config_for_platform(&config, &[""; 5], true).is_ok());
    }

    #[test]
    fn other_platforms_keep_non_zero_s3_and_s4_support() {
        let config = cfg(0, 0, 0, [0; 4], [8, 12, 16, 20]);
        assert!(AwgParams::from_config_for_platform(&config, &[""; 5], false).is_ok());
    }

    #[test]
    fn compiled_guard_matches_the_vendored_cross_repo_policy() {
        let repo_root = std::env::var_os("RIPDPI_REPO_ROOT").map_or_else(
            || std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../.."),
            std::path::PathBuf::from,
        );
        let policy_path = repo_root.join("core/data/src/test/resources/contract/amneziawg-arm64-version-floor.json");
        let policy: serde_json::Value = serde_json::from_str(
            &std::fs::read_to_string(&policy_path).expect("vendored AmneziaWG floor policy must be readable"),
        )
        .expect("vendored AmneziaWG floor policy must be valid JSON");

        assert_eq!(policy["guard_required"].as_bool(), Some(ARM64_S34_GUARD_REQUIRED));
        assert!(policy["verified_safe_floor"].is_null());
        assert_eq!(
            policy["ripdpi_reference"]["upstream_semantics_version"].as_str(),
            Some(AMNEZIAWG_UPSTREAM_SEMANTICS_VERSION),
        );
    }

    #[test]
    fn from_config_rejects_malformed_special_junk() {
        let c = cfg(0, 0, 0, [0; 4], [0; 4]);
        let err = AwgParams::from_config(&c, &["", "zz", "", "", ""]).unwrap_err();
        assert_eq!(err, AwgParamsError::SpecialJunkNotHex { index: 1 });
    }

    // --- passthrough / byte-identity ---------------------------------------

    #[test]
    fn passthrough_when_everything_disabled() {
        let c = cfg(0, 0, 0, [0; 4], [0; 4]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        assert!(params.is_passthrough());
        assert!(!params.headers_active());
    }

    #[test]
    fn not_passthrough_when_any_knob_set() {
        let with_jc = AwgParams::from_config(&cfg(1, 1, 1, [0; 4], [0; 4]), &no_special()).unwrap();
        assert!(!with_jc.is_passthrough());
        let with_h = AwgParams::from_config(&cfg(0, 0, 0, [0xABCD, 0, 0, 0], [0; 4]), &no_special()).unwrap();
        assert!(!with_h.is_passthrough());
        let with_s = AwgParams::from_config(&cfg(0, 0, 0, [0; 4], [4, 0, 0, 0]), &no_special()).unwrap();
        assert!(!with_s.is_passthrough());
        let with_i = AwgParams::from_config(&cfg(0, 0, 0, [0; 4], [0; 4]), &["dead", "", "", "", ""]).unwrap();
        assert!(!with_i.is_passthrough());
    }

    /// The headline acceptance criterion: with `Jc=0`, `S1..S4=0`, and
    /// `H1..H4` unset, the codec output is byte-identical to the WireGuard
    /// input for every message type. Exercised against a WireGuard-shaped
    /// initiation test vector (type byte + 147-byte body = 148 bytes, the
    /// real WireGuard handshake-initiation length).
    #[test]
    fn passthrough_codec_is_byte_identical_to_wireguard() {
        let c = cfg(0, 0, 0, [0; 4], [0; 4]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let codec = AwgWireCodec::new(params);

        // WireGuard handshake-initiation test vector: 148 bytes total.
        let mut initiation = vec![0u8; 148];
        initiation[0] = 0x01;
        for (i, b) in initiation[4..].iter_mut().enumerate() {
            *b = (i % 251) as u8;
        }
        assert_eq!(codec.encode(&initiation).unwrap(), initiation, "encode must be a no-op in passthrough");
        assert_eq!(codec.decode(&initiation).expect("passthrough decode"), initiation);

        // And for every other WireGuard message type.
        for (wg_type, len) in [(0x02u8, 92usize), (0x03, 64), (0x04, 128)] {
            let mut packet = vec![0xA5u8; len];
            packet[..4].copy_from_slice(&u32::from(wg_type).to_le_bytes());
            assert_eq!(codec.encode(&packet).unwrap(), packet);
            assert_eq!(codec.decode(&packet).unwrap(), packet);
        }
    }

    // --- header substitution + padding round trips ------------------------

    #[test]
    fn encode_substitutes_header_for_all_four_message_types() {
        let headers = [0x10_00_00_01, 0x10_00_00_02, 0x10_00_00_03, 0x10_00_00_04];
        let c = cfg(0, 0, 0, headers, [0; 4]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let codec = AwgWireCodec::new(params);

        for ((wg_type, header), len) in (1u8..=4).zip(headers).zip([148, 92, 64, 80]) {
            let mut packet = vec![0x77u8; len];
            packet[..4].copy_from_slice(&u32::from(wg_type).to_le_bytes());
            let encoded = codec.encode(&packet).unwrap();
            assert_eq!(u32::from_le_bytes(encoded[..4].try_into().unwrap()), header as u32);
            assert_eq!(&encoded[4..], &packet[4..], "body preserved for type {wg_type}");
            assert_eq!(codec.decode(&encoded).expect("decode"), packet);
        }
    }

    #[test]
    fn encode_prefixes_size_padding_per_type() {
        let headers = [0xAA_00_00_01, 0xAA_00_00_02, 0xAA_00_00_03, 0xAA_00_00_04];
        let padding = [8, 4, 0, 16];
        let c = cfg(0, 0, 0, headers, padding);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let codec = AwgWireCodec::new(params);

        for ((i, wg_type), len) in (1u8..=4).enumerate().zip([148, 92, 64, 80]) {
            let mut packet = vec![0x33u8; len];
            packet[..4].copy_from_slice(&u32::from(wg_type).to_le_bytes());
            let encoded = codec.encode(&packet).unwrap();
            let pad_len = padding[i] as usize;
            assert_eq!(encoded.len(), packet.len() + pad_len, "len for type {wg_type}");
            assert_eq!(u32::from_le_bytes(encoded[pad_len..pad_len + 4].try_into().unwrap()), headers[i] as u32);
            assert_eq!(codec.decode(&encoded).expect("decode"), packet);
        }
    }

    #[test]
    fn padding_without_headers_keeps_type_byte_and_strips_on_decode() {
        // Headers unset but S-padding active: the type byte stays intact.
        let c = cfg(0, 0, 0, [0; 4], [6, 0, 0, 0]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let codec = AwgWireCodec::new(params);

        let mut packet = vec![0x5Cu8; 40];
        packet.resize(HANDSHAKE_INIT_SIZE, 0x5C);
        packet[..4].copy_from_slice(&1u32.to_le_bytes());
        let encoded = codec.encode(&packet).unwrap();
        assert_eq!(&encoded[6..10], &1u32.to_le_bytes(), "type field preserved after prefix padding");
        assert_eq!(encoded.len(), HANDSHAKE_INIT_SIZE + 6);
        assert_eq!(codec.decode(&encoded).expect("decode"), packet);
    }

    #[test]
    fn decode_rejects_unknown_magic_header() {
        let headers = [0x12_34_56_78, 0x12_34_56_79, 0x12_34_56_7A, 0x12_34_56_7B];
        let c = cfg(0, 0, 0, headers, [0; 4]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let codec = AwgWireCodec::new(params);
        // A datagram whose first 4 bytes match no configured header is junk.
        let junk = vec![0xFFu8, 0xFF, 0xFF, 0xFF, 0x01, 0x02];
        assert_eq!(codec.decode(&junk), None);
    }

    #[test]
    fn decode_rejects_truncated_packet() {
        let headers = [0xDE_AD_00_01, 0, 0, 0];
        let c = cfg(0, 0, 0, headers, [0; 4]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let codec = AwgWireCodec::new(params);
        assert_eq!(codec.decode(&[0xDE, 0xAD]), None);
    }

    #[test]
    fn encode_passes_through_non_wireguard_type_byte() {
        let headers = [0x99_00_00_01, 0, 0, 0];
        let c = cfg(0, 0, 0, headers, [0; 4]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let codec = AwgWireCodec::new(params);
        // Type byte 0x05 is not a WireGuard message type.
        let packet = vec![0x05u8, 1, 2, 3];
        assert_eq!(codec.encode(&packet).unwrap(), packet);
    }

    // --- junk packets ------------------------------------------------------

    #[test]
    fn build_junk_packets_emits_jc_packets_sized_in_range() {
        let c = cfg(3, 10, 20, [0; 4], [0; 4]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        // span = 20-10+1 = 11; sizes: 10+(0%11)=10, 10+(5%11)=15, 10+(10%11)=20
        let mut rng = seq_rng(vec![0, 5, 10]);
        let packets = params.build_junk_packets(&mut rng);
        assert_eq!(packets.len(), 3);
        assert_eq!(packets[0].len(), 10);
        assert_eq!(packets[1].len(), 15);
        assert_eq!(packets[2].len(), 20);
        for p in &packets {
            assert!(p.len() >= 10 && p.len() <= 20);
        }
    }

    #[test]
    fn build_junk_packets_is_empty_when_count_is_zero() {
        let c = cfg(0, 10, 20, [0; 4], [0; 4]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let mut rng = seq_rng(vec![0]);
        assert!(params.build_junk_packets(&mut rng).is_empty());
    }

    #[test]
    fn build_junk_packets_handles_single_size_range() {
        // Jmin == Jmax: span is 1, every packet is exactly that size.
        let c = cfg(2, 64, 64, [0; 4], [0; 4]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let mut rng = seq_rng(vec![123_456, 999]);
        let packets = params.build_junk_packets(&mut rng);
        assert_eq!(packets.len(), 2);
        assert!(packets.iter().all(|p| p.len() == 64));
    }

    // --- AWG 2.0 special junk (I1..I5) -------------------------------------

    #[test]
    fn special_junk_packets_decodes_set_slots_in_order() {
        let c = cfg(0, 0, 0, [0; 4], [0; 4]);
        let params = AwgParams::from_config(&c, &["dead", "", "beefcafe", "", "01"]).unwrap();
        let frames = params.special_junk_packets();
        assert_eq!(frames.len(), 3);
        assert_eq!(frames[0], vec![0xDE, 0xAD]);
        assert_eq!(frames[1], vec![0xBE, 0xEF, 0xCA, 0xFE]);
        assert_eq!(frames[2], vec![0x01]);
    }

    #[test]
    fn special_junk_frames_are_fixed_not_randomized() {
        // The same hex always decodes to the same bytes -- I1..I5 are verbatim.
        let c = cfg(0, 0, 0, [0; 4], [0; 4]);
        let p1 = AwgParams::from_config(&c, &["a1b2c3", "", "", "", ""]).unwrap();
        let p2 = AwgParams::from_config(&c, &["a1b2c3", "", "", "", ""]).unwrap();
        assert_eq!(p1.special_junk_packets(), p2.special_junk_packets());
        assert_eq!(p1.special_junk_packets()[0], vec![0xA1, 0xB2, 0xC3]);
    }

    // --- handshake prelude -------------------------------------------------

    #[test]
    fn handshake_prelude_emits_special_junk_then_random_junk() {
        let c = cfg(2, 8, 8, [0; 4], [0; 4]);
        let params = AwgParams::from_config(&c, &["aaaa", "", "bb", "", ""]).unwrap();
        let mut rng = seq_rng(vec![0, 0]);
        let prelude = params.handshake_prelude(&mut rng);
        // 2 special-junk frames + 2 random junk packets.
        assert_eq!(prelude.len(), 4);
        assert_eq!(prelude[0], vec![0xAA, 0xAA]);
        assert_eq!(prelude[1], vec![0xBB]);
        assert_eq!(prelude[2].len(), 8);
        assert_eq!(prelude[3].len(), 8);
    }

    #[test]
    fn handshake_prelude_is_empty_for_passthrough() {
        let c = cfg(0, 0, 0, [0; 4], [0; 4]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let mut rng = seq_rng(vec![0]);
        assert!(params.handshake_prelude(&mut rng).is_empty());
    }

    // --- full obfuscation combination --------------------------------------

    // --- end-to-end Noise handshake through the obfuscation codec ----------

    /// Drive a real boringtun `Noise_IKpsk2` handshake between two peers with
    /// EVERY on-wire packet passed through the AmneziaWG codec (magic headers
    /// `H1..H4` + `S1..S4` padding active). If boringtun reaches the
    /// transport-data state and an inner IP packet survives the round trip,
    /// the obfuscation layer is proven transport-correct against the actual
    /// Noise state machine -- not merely byte-symmetric in isolation.
    ///
    /// This is the load-bearing "AmneziaWG actually establishes a tunnel"
    /// proof at the data-plane level (no sockets, deterministic, in-process).
    #[test]
    fn obfuscated_handshake_completes_and_transports_a_packet() {
        use boringtun::noise::{Tunn, TunnResult};
        use boringtun::x25519::{PublicKey, StaticSecret};

        // Deterministic-but-distinct keypairs (test-only; not secrets).
        let client_secret = StaticSecret::from([7u8; 32]);
        let server_secret = StaticSecret::from([9u8; 32]);
        let client_public = PublicKey::from(&client_secret);
        let server_public = PublicKey::from(&server_secret);

        let mut client = Tunn::new(client_secret, server_public, None, None, 0, None);
        let mut server = Tunn::new(server_secret, client_public, None, None, 1, None);

        // Both peers share the same AmneziaWG obfuscation parameters (headers
        // + padding active, so the codec is NOT in passthrough).
        let headers = [0x10_00_00_01, 0x10_00_00_02, 0x10_00_00_03, 0x10_00_00_04];
        let params = AwgParams::from_config(&cfg(0, 0, 0, headers, [8, 4, 6, 2]), &no_special()).unwrap();
        assert!(!params.is_passthrough(), "codec must be active for a meaningful test");
        let client_codec = AwgWireCodec::new_authenticated(
            params.clone(),
            AwgMacKeys::new(client_public.to_bytes(), server_public.to_bytes()),
        );
        let server_codec = AwgWireCodec::new_authenticated(
            params,
            AwgMacKeys::new(server_public.to_bytes(), client_public.to_bytes()),
        );

        // 1) Client initiates. `encapsulate(&[])` with no established session
        // yields the handshake initiation as WriteToNetwork.
        let mut buf = [0u8; 2048];
        let init = match client.encapsulate(&[], &mut buf) {
            TunnResult::WriteToNetwork(packet) => packet.to_vec(),
            other => panic!("expected handshake initiation, got {other:?}"),
        };
        assert_eq!(init[0], 1, "WireGuard handshake initiation is message type 1");

        // 2) Obfuscate, hand to the server, deobfuscate, decapsulate -> the
        // server produces the handshake response.
        let obf_init = client_codec.encode(&init).expect("client encodes initiation");
        assert_ne!(obf_init[..4], [1, 0, 0, 0], "type byte must be header-substituted on the wire");
        assert_eq!(obf_init.len(), HANDSHAKE_INIT_SIZE + 8, "S1 is a prefix without changing WG body size");
        assert_eq!(u32::from_le_bytes(obf_init[8..12].try_into().unwrap()), headers[0] as u32);
        let mut corrupted_init = obf_init.clone();
        corrupted_init[20] ^= 1;
        assert!(server_codec.decode(&corrupted_init).is_none(), "AWG MAC1 must authenticate the wire header and body");
        let dec_init = server_codec.decode(&obf_init).expect("server decodes init");

        let mut buf2 = [0u8; 2048];
        let response = match server.decapsulate(None, &dec_init, &mut buf2) {
            TunnResult::WriteToNetwork(packet) => packet.to_vec(),
            other => panic!("expected handshake response, got {other:?}"),
        };
        assert_eq!(response[0], 2, "WireGuard handshake response is message type 2");

        // 3) Client consumes the (obfuscated) response, completing the handshake.
        let obf_resp = server_codec.encode(&response).expect("server encodes response");
        let dec_resp = client_codec.decode(&obf_resp).expect("client decodes response");
        let mut buf3 = [0u8; 2048];
        // Completing the handshake typically yields an empty keepalive
        // (WriteToNetwork) or Done; either means the session is established.
        let post = client.decapsulate(None, &dec_resp, &mut buf3);
        match post {
            TunnResult::WriteToNetwork(_) | TunnResult::Done => {}
            other => panic!("handshake did not complete: {other:?}"),
        }

        // 4) Now transport a real inner IPv4 packet client -> server. Build a
        // minimal well-formed IPv4 header (20 bytes) so boringtun routes it as
        // WriteToTunnelV4 on the receiving side.
        let mut ip_packet = vec![0u8; 20];
        ip_packet[0] = 0x45; // IPv4, IHL=5
        let total_len = (ip_packet.len() as u16).to_be_bytes();
        ip_packet[2] = total_len[0];
        ip_packet[3] = total_len[1];
        ip_packet[9] = 17; // UDP
        ip_packet[12..16].copy_from_slice(&[10, 8, 0, 2]); // src
        ip_packet[16..20].copy_from_slice(&[10, 8, 0, 1]); // dst

        let mut buf4 = [0u8; 2048];
        let data_on_wire = match client.encapsulate(&ip_packet, &mut buf4) {
            TunnResult::WriteToNetwork(packet) => packet.to_vec(),
            other => panic!("expected encrypted transport packet, got {other:?}"),
        };
        assert_eq!(data_on_wire[0], 4, "WireGuard transport-data is message type 4");

        let obf_data = client_codec.encode(&data_on_wire).expect("client encodes transport data");
        let dec_data = server_codec.decode(&obf_data).expect("server decodes transport data");
        let mut buf5 = [0u8; 2048];
        match server.decapsulate(None, &dec_data, &mut buf5) {
            TunnResult::WriteToTunnelV4(plaintext, _) => {
                assert_eq!(plaintext, &ip_packet[..], "inner IP packet survived the obfuscated tunnel");
            }
            other => panic!("server failed to recover the inner packet: {other:?}"),
        }
    }

    /// A WireGuard preshared key (PSK) mixes into the Noise key schedule. This
    /// drives a full handshake with a matching PSK on both peers, through the
    /// active AmneziaWG codec, and asserts an inner packet round-trips -- proving
    /// the generic-profile PSK plumbing (`WireGuardTunnelParams::preshared_key`)
    /// is wired into `Tunn` correctly, not silently dropped.
    #[test]
    fn preshared_key_handshake_completes_through_codec() {
        use boringtun::noise::{Tunn, TunnResult};
        use boringtun::x25519::{PublicKey, StaticSecret};

        let client_secret = StaticSecret::from([3u8; 32]);
        let server_secret = StaticSecret::from([5u8; 32]);
        let client_public = PublicKey::from(&client_secret);
        let server_public = PublicKey::from(&server_secret);
        // Matching 32-byte PSK on both peers (arg 3 of Tunn::new).
        let psk = [0x5Au8; 32];

        let mut client = Tunn::new(client_secret, server_public, Some(psk), None, 0, None);
        let mut server = Tunn::new(server_secret, client_public, Some(psk), None, 1, None);

        let params = AwgParams::from_config(
            &cfg(0, 0, 0, [0x20_00_00_01, 0x20_00_00_02, 0x20_00_00_03, 0x20_00_00_04], [0; 4]),
            &no_special(),
        )
        .unwrap();
        let client_codec = AwgWireCodec::new_authenticated(
            params.clone(),
            AwgMacKeys::new(client_public.to_bytes(), server_public.to_bytes()),
        );
        let server_codec = AwgWireCodec::new_authenticated(
            params,
            AwgMacKeys::new(server_public.to_bytes(), client_public.to_bytes()),
        );
        let through = |sender: &AwgWireCodec, receiver: &AwgWireCodec, packet: &[u8]| -> Vec<u8> {
            let encoded = sender.encode(packet).expect("codec encodes packet");
            receiver.decode(&encoded).expect("codec decodes packet")
        };

        let mut buf = [0u8; 2048];
        let init = match client.encapsulate(&[], &mut buf) {
            TunnResult::WriteToNetwork(p) => p.to_vec(),
            other => panic!("expected init, got {other:?}"),
        };
        let dec_init = through(&client_codec, &server_codec, &init);
        let mut buf2 = [0u8; 2048];
        let response = match server.decapsulate(None, &dec_init, &mut buf2) {
            TunnResult::WriteToNetwork(p) => p.to_vec(),
            other => panic!("expected response (PSK accepted), got {other:?}"),
        };
        let dec_resp = through(&server_codec, &client_codec, &response);
        let mut buf3 = [0u8; 2048];
        match client.decapsulate(None, &dec_resp, &mut buf3) {
            TunnResult::WriteToNetwork(_) | TunnResult::Done => {}
            other => panic!("PSK handshake did not complete: {other:?}"),
        }

        let mut ip_packet = vec![0u8; 20];
        ip_packet[0] = 0x45;
        let total_len = (ip_packet.len() as u16).to_be_bytes();
        ip_packet[2] = total_len[0];
        ip_packet[3] = total_len[1];
        ip_packet[9] = 17;
        let mut buf4 = [0u8; 2048];
        let data = match client.encapsulate(&ip_packet, &mut buf4) {
            TunnResult::WriteToNetwork(p) => p.to_vec(),
            other => panic!("expected transport data, got {other:?}"),
        };
        let dec_data = through(&client_codec, &server_codec, &data);
        let mut buf5 = [0u8; 2048];
        match server.decapsulate(None, &dec_data, &mut buf5) {
            TunnResult::WriteToTunnelV4(plaintext, _) => {
                assert_eq!(plaintext, &ip_packet[..], "PSK-derived transport keys agree and the packet round-trips");
            }
            other => panic!("server could not recover the inner packet under PSK: {other:?}"),
        }
    }

    #[test]
    fn cookie_challenge_round_trips_between_awg_and_boringtun_mac_domains() {
        use boringtun::noise::rate_limiter::RateLimiter;
        use boringtun::noise::{Tunn, TunnResult};
        use boringtun::x25519::{PublicKey, StaticSecret};
        use std::net::{IpAddr, Ipv4Addr};
        use std::sync::Arc;

        let client_secret = StaticSecret::from([0x31; 32]);
        let server_secret = StaticSecret::from([0x47; 32]);
        let client_public = PublicKey::from(&client_secret);
        let server_public = PublicKey::from(&server_secret);
        let client_public_bytes = client_public.to_bytes();
        let server_public_bytes = server_public.to_bytes();

        let mut client = Tunn::new(client_secret, server_public, None, None, 0, None);
        let limiter = Arc::new(RateLimiter::new(&PublicKey::from(server_public_bytes), 0));
        let mut server = Tunn::new(server_secret, client_public, None, None, 1, Some(limiter));

        let headers = [0x41_00_00_01, 0x41_00_00_02, 0x41_00_00_03, 0x41_00_00_04];
        let params = AwgParams::from_config(&cfg(0, 0, 0, headers, [5, 7, 3, 0]), &no_special()).unwrap();
        let client_codec =
            AwgWireCodec::new_authenticated(params.clone(), AwgMacKeys::new(client_public_bytes, server_public_bytes));
        let server_codec =
            AwgWireCodec::new_authenticated(params, AwgMacKeys::new(server_public_bytes, client_public_bytes));
        let source_ip = Some(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 9)));

        let mut init_buf = [0u8; 2048];
        let init = match client.format_handshake_initiation(&mut init_buf, true) {
            TunnResult::WriteToNetwork(packet) => packet.to_vec(),
            other => panic!("expected initial handshake, got {other:?}"),
        };
        let wire_init = client_codec.encode(&init).expect("encode initial handshake");
        let vanilla_init = server_codec.decode(&wire_init).expect("decode initial handshake");

        let mut cookie_buf = [0u8; 2048];
        let vanilla_cookie = match server.decapsulate(source_ip, &vanilla_init, &mut cookie_buf) {
            TunnResult::WriteToNetwork(packet) => packet.to_vec(),
            other => panic!("under-load peer must issue a cookie, got {other:?}"),
        };
        assert_eq!(u32::from_le_bytes(vanilla_cookie[..4].try_into().unwrap()), 3);
        let wire_cookie = server_codec.encode(&vanilla_cookie).expect("translate cookie to AWG MAC1 domain");
        assert_eq!(u32::from_le_bytes(wire_cookie[3..7].try_into().unwrap()), headers[2] as u32);
        let client_cookie = client_codec.decode(&wire_cookie).expect("translate cookie to boringtun MAC1 domain");

        let mut consume_cookie_buf = [0u8; 2048];
        assert!(matches!(client.decapsulate(source_ip, &client_cookie, &mut consume_cookie_buf), TunnResult::Done));

        let mut retry_buf = [0u8; 2048];
        let retry = match client.format_handshake_initiation(&mut retry_buf, true) {
            TunnResult::WriteToNetwork(packet) => packet.to_vec(),
            other => panic!("expected cookie-authenticated retry, got {other:?}"),
        };
        let wire_retry = client_codec.encode(&retry).expect("encode cookie-authenticated retry");
        assert!(!bool::from(wire_retry[wire_retry.len() - 16..].ct_eq(&[0; 16])));
        let vanilla_retry = server_codec.decode(&wire_retry).expect("validate AWG MAC2 and translate retry");

        let mut response_buf = [0u8; 2048];
        let response = match server.decapsulate(source_ip, &vanilla_retry, &mut response_buf) {
            TunnResult::WriteToNetwork(packet) => packet.to_vec(),
            other => panic!("cookie-authenticated handshake must reach response, got {other:?}"),
        };
        assert_eq!(u32::from_le_bytes(response[..4].try_into().unwrap()), 2);
        let wire_response = server_codec.encode(&response).expect("encode response");
        let client_response = client_codec.decode(&wire_response).expect("decode response");
        let mut finish_buf = [0u8; 2048];
        assert!(matches!(
            client.decapsulate(source_ip, &client_response, &mut finish_buf),
            TunnResult::WriteToNetwork(_) | TunnResult::Done
        ));
    }

    #[test]
    fn headers_and_padding_combine_round_trip_for_all_types() {
        let headers = [0xC0_FF_EE_01, 0xC0_FF_EE_02, 0xC0_FF_EE_03, 0xC0_FF_EE_04];
        let padding = [12, 0, 7, 3];
        let c = cfg(4, 32, 96, headers, padding);
        let params = AwgParams::from_config(&c, &["deadbeef", "", "", "", "cafe"]).unwrap();
        assert!(!params.is_passthrough());
        let codec = AwgWireCodec::new(params);

        for ((i, wg_type), len) in (1u8..=4).enumerate().zip([148, 92, 64, 100]) {
            let mut packet = vec![0u8; len];
            packet[..4].copy_from_slice(&u32::from(wg_type).to_le_bytes());
            for (j, b) in packet[4..].iter_mut().enumerate() {
                *b = (j % 97) as u8;
            }
            let encoded = codec.encode(&packet).expect("encode");
            assert_eq!(encoded.len(), packet.len() + padding[i] as usize);
            assert_eq!(codec.decode(&encoded).expect("round trip decode"), packet);
        }
    }
}
