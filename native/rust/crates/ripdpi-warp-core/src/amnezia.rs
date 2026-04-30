use crate::config::WarpAmneziaConfig;

pub(crate) fn fill_random(buf: &mut [u8]) {
    getrandom::fill(buf).expect("getrandom failed");
}

/// Read a random u32 from the OS CSPRNG.
pub(crate) fn rand_u32() -> u32 {
    let mut buf = [0u8; 4];
    fill_random(&mut buf);
    u32::from_le_bytes(buf)
}

/// AmneziaWG packet obfuscation codec.
///
/// On send: replaces the WireGuard type byte with the configured header value
/// (hN) and prepends sN random padding bytes.
/// On receive: reads the u32 after the configured padding, matches against
/// h1-h4 to identify the WireGuard type and strips the Amnezia header.
pub(crate) struct AmneziaCodec {
    /// Fixed replacement header values for WG types 1-4.
    h: [u32; 4],
    /// Padding lengths for WG types 1-4.
    s: [usize; 4],
}

impl AmneziaCodec {
    pub(crate) fn new(cfg: &WarpAmneziaConfig) -> Self {
        Self {
            h: [cfg.h1 as u32, cfg.h2 as u32, cfg.h3 as u32, cfg.h4 as u32],
            s: [cfg.s1 as usize, cfg.s2 as usize, cfg.s3 as usize, cfg.s4 as usize],
        }
    }

    /// Obfuscate a WireGuard packet for sending.
    /// Returns a new `Vec<u8>` with padding prepended and the WG type byte
    /// replaced by the configured Amnezia header.
    pub(crate) fn encode(&self, packet: &[u8]) -> Vec<u8> {
        if packet.len() < 4 {
            return packet.to_vec();
        }
        let wg_type = packet[0];
        let idx = match wg_type {
            1 => 0,
            2 => 1,
            3 => 2,
            4 => 3,
            _ => return packet.to_vec(),
        };
        let header = self.h[idx];
        let pad_len = self.s[idx];
        // Layout: [pad_len random bytes] [4-byte header LE] [packet[1..]]
        let mut out = Vec::with_capacity(pad_len + 4 + packet.len() - 1);
        let pad_start = out.len();
        out.resize(pad_start + pad_len, 0u8);
        fill_random(&mut out[pad_start..pad_start + pad_len]);
        out.extend_from_slice(&header.to_le_bytes());
        out.extend_from_slice(&packet[1..]);
        out
    }

    /// Decode an AmneziaWG packet received from the peer.
    /// Returns `(wg_type, payload_after_header)` or `None` if unrecognized.
    pub(crate) fn decode<'a>(&self, packet: &'a [u8]) -> Option<(u8, &'a [u8])> {
        if packet.len() < 4 {
            return None;
        }
        for (idx, &h_val) in self.h.iter().enumerate() {
            let pad_len = self.s[idx];
            let header_start = pad_len;
            let payload_start = header_start + 4;
            if payload_start > packet.len() {
                continue;
            }
            let header = u32::from_le_bytes(packet[header_start..payload_start].try_into().ok()?);
            if header == h_val {
                let wg_type = (idx + 1) as u8;
                return Some((wg_type, &packet[payload_start..]));
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_amnezia_codec() -> AmneziaCodec {
        AmneziaCodec { h: [100_000, 1_000_000, 10_000_000, 100_000_000], s: [8, 4, 0, 16] }
    }

    #[test]
    fn amnezia_codec_encode_decode_roundtrip_init() {
        let codec = test_amnezia_codec();
        let mut original = vec![0u8; 148];
        original[0] = 1;
        for (i, b) in original[1..].iter_mut().enumerate() {
            *b = (i & 0xFF) as u8;
        }
        let encoded = codec.encode(&original);
        assert_eq!(encoded.len(), 8 + 4 + 147);
        let header = u32::from_le_bytes(encoded[8..12].try_into().unwrap());
        assert_eq!(header, 100_000u32);
        let (wg_type, tail) = codec.decode(&encoded).expect("decode failed");
        assert_eq!(wg_type, 1);
        let reconstructed: Vec<u8> = std::iter::once(wg_type).chain(tail.iter().copied()).collect();
        assert_eq!(reconstructed, original);
    }

    #[test]
    fn amnezia_codec_encode_decode_roundtrip_transport() {
        let codec = test_amnezia_codec();
        let mut original = vec![0xABu8; 64];
        original[0] = 4;
        let encoded = codec.encode(&original);
        assert_eq!(encoded.len(), 16 + 4 + 63);
        let (wg_type, tail) = codec.decode(&encoded).expect("decode failed");
        assert_eq!(wg_type, 4);
        let reconstructed: Vec<u8> = std::iter::once(wg_type).chain(tail.iter().copied()).collect();
        assert_eq!(reconstructed, original);
    }

    #[test]
    fn amnezia_codec_decode_unknown_header_returns_none() {
        let codec = test_amnezia_codec();
        let packet = vec![0x01, 0x02, 0x03, 0x04, 0xFF, 0xFF];
        assert!(codec.decode(&packet).is_none());
    }

    #[test]
    fn amnezia_codec_passthrough_non_wg_type() {
        let codec = test_amnezia_codec();
        let packet = vec![0u8, 1, 2, 3];
        assert_eq!(codec.encode(&packet), packet);
    }
}
