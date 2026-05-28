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
// `amnezia-vpn/amneziawg-go` (pin `v0.2.16`) is MIT. This file contains
// only the AWG obfuscation delta (junk-packet sequencing, H1-H4 magic
// header substitution, S1-S4 size padding, AWG 2.0 I1-I5 special junk
// intervals). It does not vendor any Noise primitive, so no BSD-3 source
// is copied here; the dual SPDX header above records both licenses for the
// crate as a whole, since the crate links `boringtun` (BSD-3) and ports
// `amneziawg-go` semantics (MIT).
//
// # Reference
//
// Semantics mirror `amnezia-vpn/amneziawg-go` v0.2.16:
//   * `device/peer.go`, `device/send.go` -- `junkPacketCount` (Jc) junk
//     packets, each sized uniformly in `[Jmin, Jmax]`, sent before the
//     real initiation.
//   * `device/noise-protocol.go` -- `InitiationPacketMagicHeader` (H1),
//     `ResponsePacketMagicHeader` (H2), `UnderloadPacketMagicHeader` (H3),
//     `TransportPacketMagicHeader` (H4) replace the WireGuard type bytes
//     `0x01..0x04`; `S1..S4` size padding inserted between the protocol
//     payload and the MAC.
//   * `device/device.go` -- AWG 2.0 `I1..I5` "special junk" intervals:
//     fixed hex-encoded junk frames injected at the start of the flow.

use crate::config::WarpAmneziaConfig;

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

/// Upper bound on a single `S1..S4` padding run. Padding is inserted between
/// the protocol payload and the MAC inside one datagram, so it shares the
/// same ceiling as junk packets.
pub(crate) const PADDING_SIZE_LIMIT: u32 = 1280;

/// Validated AmneziaWG obfuscation parameters.
///
/// Built from the on-the-wire [`WarpAmneziaConfig`] (which uses signed
/// integers because it is shared verbatim with the Kotlin config model)
/// via [`AwgParams::from_config`]. Once constructed, every field is in a
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
    /// `S1..S4` -- bytes of random padding inserted before the MAC for each
    /// message type.
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
    pub(crate) fn from_config(cfg: &WarpAmneziaConfig, special_junk_hex: &[&str]) -> Result<Self, AwgParamsError> {
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
        // Reject collisions between *set* headers. A header value of 0 means
        // "unset" (see `headers_active`): unset headers never collide.
        for a in 0..WG_MESSAGE_TYPE_COUNT {
            for b in (a + 1)..WG_MESSAGE_TYPE_COUNT {
                if magic_headers[a] != 0 && magic_headers[a] == magic_headers[b] {
                    return Err(AwgParamsError::HeaderCollision { a, b, value: magic_headers[a] });
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
    /// the start of the flow, matching `amneziawg-go` v0.2.16.
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
/// # Wire layout (when headers are active)
///
/// ```text
/// send([type_byte | body])
///   -> [ H{type} (4 bytes LE) | body | S{type} random padding bytes ]
/// ```
///
/// Padding is appended *after* the body, mirroring `amneziawg-go`'s
/// `device/noise-protocol.go`, which inserts `S1..S4` junk between the
/// protocol payload and the trailing MAC. boringtun hands the codec a fully
/// built WireGuard message (MAC included), so for RIPDPI's purposes the
/// padding sits after the whole message; the receiver strips it by length.
///
/// # Byte-identity invariant
///
/// When [`AwgParams::is_passthrough`] is true the codec is a no-op: `encode`
/// returns the input unchanged and `decode` returns `(type_byte, body)`
/// as-is. This is what makes the `Jc=0`, `S1..S4=0`, `H1..H4` unset case
/// byte-identical to upstream WireGuard.
#[derive(Debug, Clone)]
pub(crate) struct AwgWireCodec {
    params: AwgParams,
}

impl AwgWireCodec {
    pub(crate) fn new(params: AwgParams) -> Self {
        Self { params }
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

    /// Obfuscate a fully built WireGuard packet for sending.
    ///
    /// `packet[0]` must be a WireGuard type byte. The returned `Vec` has the
    /// type byte replaced by the configured 4-byte magic header (little
    /// endian) when headers are active, and `S{type}` random padding bytes
    /// appended. Packets shorter than one byte, or whose type byte is not
    /// `0x01..=0x04`, are returned unchanged.
    pub(crate) fn encode(&self, packet: &[u8]) -> Vec<u8> {
        if self.params.is_passthrough() {
            return packet.to_vec();
        }
        let Some(&wg_type) = packet.first() else {
            return packet.to_vec();
        };
        let Some(index) = Self::type_index(wg_type) else {
            return packet.to_vec();
        };
        let body = &packet[1..];
        let pad_len = self.params.size_padding[index] as usize;
        let headers_active = self.params.headers_active();
        let header_len = if headers_active { 4 } else { 1 };
        let mut out = Vec::with_capacity(header_len + body.len() + pad_len);
        if headers_active {
            out.extend_from_slice(&self.params.magic_headers[index].to_le_bytes());
        } else {
            out.push(wg_type);
        }
        out.extend_from_slice(body);
        if pad_len > 0 {
            let pad_start = out.len();
            out.resize(pad_start + pad_len, 0u8);
            fill_random(&mut out[pad_start..]);
        }
        out
    }

    /// Reverse [`AwgWireCodec::encode`] for a packet received from the peer.
    ///
    /// Returns `(wg_type, body)` -- the reconstructed WireGuard type byte and
    /// the protocol body with magic header and trailing padding stripped --
    /// or `None` when the packet matches no known message type. A `None`
    /// result is the signal to drop the datagram (e.g. junk emitted by the
    /// remote peer during its own handshake setup).
    pub(crate) fn decode<'a>(&self, packet: &'a [u8]) -> Option<(u8, &'a [u8])> {
        if self.params.is_passthrough() {
            let (&wg_type, body) = packet.split_first()?;
            return Some((wg_type, body));
        }

        if self.params.headers_active() {
            if packet.len() < 4 {
                return None;
            }
            let header = u32::from_le_bytes(packet[..4].try_into().ok()?);
            let index = self.params.magic_headers.iter().position(|&h| h != 0 && h == header)?;
            let pad_len = self.params.size_padding[index] as usize;
            let body_end = packet.len().checked_sub(pad_len)?;
            if body_end < 4 {
                return None;
            }
            let wg_type = (index + 1) as u8;
            Some((wg_type, &packet[4..body_end]))
        } else {
            // Headers unset but padding active: the type byte is intact.
            let (&wg_type, rest) = packet.split_first()?;
            let index = Self::type_index(wg_type)?;
            let pad_len = self.params.size_padding[index] as usize;
            let body_end = rest.len().checked_sub(pad_len)?;
            Some((wg_type, &rest[..body_end]))
        }
    }
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
        for (i, b) in initiation[1..].iter_mut().enumerate() {
            *b = (i % 251) as u8;
        }
        assert_eq!(codec.encode(&initiation), initiation, "encode must be a no-op in passthrough");
        let (wg_type, body) = codec.decode(&initiation).expect("passthrough decode");
        assert_eq!(wg_type, 0x01);
        assert_eq!(body, &initiation[1..]);

        // And for every other WireGuard message type.
        for (wg_type, len) in [(0x02u8, 92usize), (0x03, 64), (0x04, 128)] {
            let mut packet = vec![0xA5u8; len];
            packet[0] = wg_type;
            assert_eq!(codec.encode(&packet), packet);
            let (decoded_type, body) = codec.decode(&packet).unwrap();
            assert_eq!(decoded_type, wg_type);
            assert_eq!(body, &packet[1..]);
        }
    }

    // --- header substitution + padding round trips ------------------------

    #[test]
    fn encode_substitutes_header_for_all_four_message_types() {
        let headers = [0x10_00_00_01, 0x10_00_00_02, 0x10_00_00_03, 0x10_00_00_04];
        let c = cfg(0, 0, 0, headers, [0; 4]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let codec = AwgWireCodec::new(params);

        for (wg_type, header) in (1u8..=4).zip(headers) {
            let mut packet = vec![0x77u8; 80];
            packet[0] = wg_type;
            let encoded = codec.encode(&packet);
            assert_eq!(u32::from_le_bytes(encoded[..4].try_into().unwrap()), header as u32);
            assert_eq!(&encoded[4..], &packet[1..], "body preserved for type {wg_type}");
            let (decoded_type, body) = codec.decode(&encoded).expect("decode");
            assert_eq!(decoded_type, wg_type);
            assert_eq!(body, &packet[1..]);
        }
    }

    #[test]
    fn encode_appends_size_padding_per_type() {
        let headers = [0xAA_00_00_01, 0xAA_00_00_02, 0xAA_00_00_03, 0xAA_00_00_04];
        let padding = [8, 4, 0, 16];
        let c = cfg(0, 0, 0, headers, padding);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let codec = AwgWireCodec::new(params);

        for (i, wg_type) in (1u8..=4).enumerate() {
            let mut packet = vec![0x33u8; 60];
            packet[0] = wg_type;
            let encoded = codec.encode(&packet);
            // header (4) + body (59) + padding
            assert_eq!(encoded.len(), 4 + 59 + padding[i] as usize, "len for type {wg_type}");
            let (decoded_type, body) = codec.decode(&encoded).expect("decode");
            assert_eq!(decoded_type, wg_type);
            assert_eq!(body, &packet[1..], "padding stripped on decode for type {wg_type}");
        }
    }

    #[test]
    fn padding_without_headers_keeps_type_byte_and_strips_on_decode() {
        // Headers unset but S-padding active: the type byte stays intact.
        let c = cfg(0, 0, 0, [0; 4], [6, 0, 0, 0]);
        let params = AwgParams::from_config(&c, &no_special()).unwrap();
        let codec = AwgWireCodec::new(params);

        let mut packet = vec![0x5Cu8; 40];
        packet[0] = 0x01;
        let encoded = codec.encode(&packet);
        assert_eq!(encoded[0], 0x01, "type byte preserved when headers unset");
        assert_eq!(encoded.len(), 40 + 6);
        let (wg_type, body) = codec.decode(&encoded).expect("decode");
        assert_eq!(wg_type, 0x01);
        assert_eq!(body, &packet[1..]);
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
        assert_eq!(codec.encode(&packet), packet);
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

    #[test]
    fn headers_and_padding_combine_round_trip_for_all_types() {
        let headers = [0xC0_FF_EE_01, 0xC0_FF_EE_02, 0xC0_FF_EE_03, 0xC0_FF_EE_04];
        let padding = [12, 0, 7, 3];
        let c = cfg(4, 32, 96, headers, padding);
        let params = AwgParams::from_config(&c, &["deadbeef", "", "", "", "cafe"]).unwrap();
        assert!(!params.is_passthrough());
        let codec = AwgWireCodec::new(params);

        for (i, wg_type) in (1u8..=4).enumerate() {
            let mut packet = vec![0u8; 100 + i];
            packet[0] = wg_type;
            for (j, b) in packet[1..].iter_mut().enumerate() {
                *b = (j % 97) as u8;
            }
            let encoded = codec.encode(&packet);
            assert_eq!(encoded.len(), 4 + (packet.len() - 1) + padding[i] as usize);
            let (decoded_type, body) = codec.decode(&encoded).expect("round trip decode");
            assert_eq!(decoded_type, wg_type);
            assert_eq!(body, &packet[1..]);
        }
    }
}
