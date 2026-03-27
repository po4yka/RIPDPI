use std::net::{IpAddr, Ipv4Addr};

use aes::Aes256;
use cipher::{KeyIvInit, StreamCipher};

type Aes256Ctr = ctr::Ctr128BE<Aes256>;

/// Extract the Telegram DC number from an obfuscated2 init packet.
///
/// The first 64 bytes of an MTProto connection use AES-256-CTR obfuscation.
/// Bytes 60..64 of the decrypted init contain the DC ID as a little-endian i32.
pub fn extract_dc_from_init(init: &[u8; 64]) -> Option<u8> {
    let key = &init[8..40];
    let iv = &init[40..56];

    let mut dec = [0u8; 64];
    dec.copy_from_slice(init);

    let mut stream_cipher = Aes256Ctr::new_from_slices(key, iv).expect("AES-256-CTR key/iv length mismatch");
    stream_cipher.apply_keystream(&mut dec);

    let dc_id = i32::from_le_bytes([dec[60], dec[61], dec[62], dec[63]]);
    let dc = dc_id.unsigned_abs() as u8;
    if (1..=5).contains(&dc) {
        Some(dc)
    } else {
        None
    }
}

/// Map a known Telegram IPv4 address to its DC number.
pub fn dc_from_ip(ip: Ipv4Addr) -> Option<u8> {
    let o = ip.octets();
    match (o[0], o[1]) {
        (149, 154) => Some(match o[2] {
            160..=163 => 1,
            164..=167 => 2,
            168..=171 => 3,
            172..=175 => 1,
            _ => 2,
        }),
        (91, 108) => Some(match o[2] {
            56..=59 => 5,
            8..=11 => 3,
            12..=15 => 4,
            _ => 2,
        }),
        (91, 105) | (185, 76) => Some(2),
        _ => None,
    }
}

/// Check whether an IP address belongs to a known Telegram DC range.
pub fn is_telegram_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => dc_from_ip(v4).is_some(),
        IpAddr::V6(_) => false,
    }
}

/// Build the WebSocket tunnel URL for the given DC number.
pub fn ws_url(dc: u8) -> String {
    format!("wss://kws{dc}.web.telegram.org/apiws")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dc_from_known_ips() {
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 160, 1)), Some(1));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 165, 10)), Some(2));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 170, 5)), Some(3));
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 108, 56, 100)), Some(5));
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 108, 9, 1)), Some(3));
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 108, 13, 1)), Some(4));
        assert_eq!(dc_from_ip(Ipv4Addr::new(185, 76, 151, 1)), Some(2));
    }

    #[test]
    fn non_telegram_ips_return_none() {
        assert_eq!(dc_from_ip(Ipv4Addr::new(8, 8, 8, 8)), None);
        assert_eq!(dc_from_ip(Ipv4Addr::new(1, 1, 1, 1)), None);
        assert_eq!(dc_from_ip(Ipv4Addr::new(192, 168, 0, 1)), None);
    }

    #[test]
    fn is_telegram_ip_v6_returns_false() {
        let v6: IpAddr = "::1".parse().unwrap();
        assert!(!is_telegram_ip(v6));
    }

    #[test]
    fn ws_url_format() {
        assert_eq!(ws_url(1), "wss://kws1.web.telegram.org/apiws");
        assert_eq!(ws_url(5), "wss://kws5.web.telegram.org/apiws");
    }

    /// Craft a synthetic obfuscated2 init packet with a known DC ID and verify
    /// that `extract_dc_from_init` correctly decrypts and extracts it.
    #[test]
    fn extract_dc_from_synthetic_init_packet() {
        for expected_dc in 1u8..=5 {
            let init = build_test_init_packet(expected_dc as i32);
            assert_eq!(extract_dc_from_init(&init), Some(expected_dc), "failed for DC{expected_dc}");
        }
    }

    #[test]
    fn extract_dc_from_init_returns_none_for_invalid_dc() {
        // DC 0 is invalid
        let init = build_test_init_packet(0);
        assert_eq!(extract_dc_from_init(&init), None);

        // DC 6 is out of range
        let init = build_test_init_packet(6);
        assert_eq!(extract_dc_from_init(&init), None);
    }

    #[test]
    fn extract_dc_from_init_handles_negative_dc() {
        // Telegram uses negative DC IDs for test/media DCs; unsigned_abs maps them
        let init = build_test_init_packet(-3);
        assert_eq!(extract_dc_from_init(&init), Some(3));
    }

    /// Build a 64-byte init packet where `extract_dc_from_init` will return
    /// the given DC ID.
    ///
    /// The obfuscated2 format: key = init[8..40], iv = init[40..56], then
    /// AES-256-CTR(key, iv) decrypts the entire packet. DC is at decrypted[60..64].
    fn build_test_init_packet(dc_id: i32) -> [u8; 64] {
        // 1. Start with an all-zero plaintext and set dc_id at bytes 60..64
        let mut plaintext = [0u8; 64];
        plaintext[60..64].copy_from_slice(&dc_id.to_le_bytes());

        // 2. Choose a deterministic key and IV
        let key: [u8; 32] = [
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12,
            0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
        ];
        let iv: [u8; 16] =
            [0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae, 0xaf, 0xb0];

        // 3. Encrypt the plaintext
        let mut ciphertext = plaintext;
        let mut cipher = Aes256Ctr::new((&key).into(), (&iv).into());
        cipher.apply_keystream(&mut ciphertext);

        // 4. Embed key and IV into the packet at the positions where
        //    extract_dc_from_init reads them
        ciphertext[8..40].copy_from_slice(&key);
        ciphertext[40..56].copy_from_slice(&iv);

        ciphertext
    }

    #[test]
    fn classify_target_tunnels_known_telegram_ips() {
        use crate::{classify_target, WsTunnelDecision};

        let cases = [
            ("149.154.160.1", 1),
            ("149.154.165.10", 2),
            ("149.154.170.5", 3),
            ("91.108.56.100", 5),
            ("91.108.13.1", 4),
        ];
        for (ip_str, expected_dc) in cases {
            let ip: IpAddr = ip_str.parse().unwrap();
            match classify_target(ip) {
                WsTunnelDecision::Tunnel(dc) => {
                    assert_eq!(dc, expected_dc, "wrong DC for {ip_str}");
                }
                WsTunnelDecision::Passthrough => panic!("expected Tunnel for {ip_str}"),
            }
        }
    }

    #[test]
    fn classify_target_passes_through_non_telegram_ips() {
        use crate::{classify_target, WsTunnelDecision};
        let ip: IpAddr = "8.8.8.8".parse().unwrap();
        assert!(matches!(classify_target(ip), WsTunnelDecision::Passthrough));
    }

    #[test]
    fn classify_target_passes_through_ipv6() {
        use crate::{classify_target, WsTunnelDecision};
        let ip: IpAddr = "2001:db8::1".parse().unwrap();
        assert!(matches!(classify_target(ip), WsTunnelDecision::Passthrough));
    }

    #[test]
    fn dc_from_ip_boundary_first_and_last_in_range() {
        // DC1: 149.154.160.0 - 149.154.163.255
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 160, 0)), Some(1));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 163, 255)), Some(1));
        // DC2: 149.154.164.0 - 149.154.167.255
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 164, 0)), Some(2));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 167, 255)), Some(2));
        // DC3: 149.154.168.0 - 149.154.171.255
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 168, 0)), Some(3));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 171, 255)), Some(3));
        // DC5: 91.108.56.0 - 91.108.59.255
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 108, 56, 0)), Some(5));
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 108, 59, 255)), Some(5));
    }

    #[test]
    fn dc_from_ip_falls_back_to_dc2_for_unmatched_telegram_ranges() {
        // 149.154.x.x where x is not in any specific DC range -> DC 2
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 200, 1)), Some(2));
        // 91.108.x.x where x is not in any specific DC range -> DC 2
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 108, 100, 1)), Some(2));
        // 91.105.x.x -> DC 2
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 105, 1, 1)), Some(2));
    }

    #[test]
    fn dc_from_ip_maps_alternate_dc1_range() {
        // 149.154.172-175 also maps to DC 1
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 172, 0)), Some(1));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 175, 255)), Some(1));
    }

    #[test]
    fn extract_dc_from_init_handles_large_negative_dc() {
        // DC -100 -> unsigned_abs = 100, not in 1..=5 -> None
        let init = build_test_init_packet(-100);
        assert_eq!(extract_dc_from_init(&init), None);
    }
}
