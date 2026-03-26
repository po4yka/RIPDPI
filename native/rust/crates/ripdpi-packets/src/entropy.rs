//! GFW popcount-based entropy detection and bypass padding.
//!
//! The GFW detects fully encrypted protocols by computing the average
//! popcount (1-bits per byte) of the first data packet. Connections with
//! average popcount between 3.4 and 4.6 are blocked. This module provides
//! utilities to detect this condition and generate printable-ASCII padding
//! that lowers the combined popcount below the exemption threshold.
//!
//! Reference: "How the Great Firewall of China Detects and Blocks Fully
//! Encrypted Traffic" (USENIX Security 2023).

/// GFW exemption threshold: average popcount per byte <= this value is exempt.
pub const POPCOUNT_EXEMPT_LOW: f32 = 3.4;

/// GFW exemption threshold: average popcount per byte >= this value is exempt.
pub const POPCOUNT_EXEMPT_HIGH: f32 = 4.6;

/// Average popcount of the padding byte ('A' = 0x41, popcount = 2).
const PAD_BYTE_POPCOUNT: f32 = 2.0;

/// The byte used for padding. 'A' (0x41) has popcount 2, well below 3.4.
const PAD_BYTE: u8 = b'A';

/// Average popcount (number of 1-bits) per byte.
pub fn popcount_per_byte(data: &[u8]) -> f32 {
    if data.is_empty() {
        return 0.0;
    }
    data.iter().map(|b| b.count_ones() as f32).sum::<f32>() / data.len() as f32
}

/// Returns true if the payload would be exempt from GFW popcount blocking.
///
/// Exempt when average popcount is outside the 3.4-4.6 detection window.
pub fn is_popcount_exempt(data: &[u8]) -> bool {
    if data.is_empty() {
        return true;
    }
    let avg = popcount_per_byte(data);
    avg <= POPCOUNT_EXEMPT_LOW || avg >= POPCOUNT_EXEMPT_HIGH
}

/// Fraction of bytes in the printable ASCII range (0x20..=0x7E).
pub fn printable_ascii_fraction(data: &[u8]) -> f32 {
    if data.is_empty() {
        return 0.0;
    }
    let count = data.iter().filter(|&&b| (0x20..=0x7E).contains(&b)).count();
    count as f32 / data.len() as f32
}

/// Generate a padding prefix that, when prepended to `payload`, brings the
/// combined average popcount at or below `target`.
///
/// Returns an empty vec if the payload is already exempt or the target is
/// unreachable (at or below the padding byte's own popcount).
///
/// `max_pad` caps the padding length to avoid excessive bloat.
pub fn generate_entropy_padding(payload: &[u8], target: f32, max_pad: usize) -> Vec<u8> {
    if payload.is_empty() || is_popcount_exempt(payload) {
        return Vec::new();
    }
    let current = popcount_per_byte(payload);
    if current <= target {
        return Vec::new();
    }
    if target <= PAD_BYTE_POPCOUNT {
        // Cannot reach a target at or below the padding byte's own popcount.
        return Vec::new();
    }
    // Solve: (pad_len * PAD + payload_len * current) / (pad_len + payload_len) <= target
    // => pad_len >= payload_len * (current - target) / (target - PAD)
    let needed = ((payload.len() as f32 * (current - target)) / (target - PAD_BYTE_POPCOUNT)).ceil() as usize;
    let pad_len = needed.min(max_pad).max(1);
    vec![PAD_BYTE; pad_len]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn popcount_per_byte_known_values() {
        assert_eq!(popcount_per_byte(&[0x00]), 0.0);
        assert_eq!(popcount_per_byte(&[0xFF]), 8.0);
        assert_eq!(popcount_per_byte(&[0xAA]), 4.0); // 10101010
        assert_eq!(popcount_per_byte(&[0x41]), 2.0); // 'A' = 01000001
        assert_eq!(popcount_per_byte(&[]), 0.0);
    }

    #[test]
    fn popcount_per_byte_mixed() {
        // 0x00 (0) + 0xFF (8) = avg 4.0
        assert!((popcount_per_byte(&[0x00, 0xFF]) - 4.0).abs() < f32::EPSILON);
    }

    #[test]
    fn is_popcount_exempt_detects_thresholds() {
        // All zeros: popcount 0.0, exempt (below 3.4)
        assert!(is_popcount_exempt(&[0x00; 100]));
        // All 0xFF: popcount 8.0, exempt (above 4.6)
        assert!(is_popcount_exempt(&[0xFF; 100]));
        // All 0xAA: popcount 4.0, NOT exempt (between 3.4 and 4.6)
        assert!(!is_popcount_exempt(&[0xAA; 100]));
        // Empty: exempt
        assert!(is_popcount_exempt(&[]));
    }

    #[test]
    fn printable_ascii_fraction_correct() {
        assert!((printable_ascii_fraction(b"Hello") - 1.0).abs() < f32::EPSILON);
        assert!((printable_ascii_fraction(&[0x00, 0x41, 0x42]) - 2.0 / 3.0).abs() < 0.01);
        assert_eq!(printable_ascii_fraction(&[]), 0.0);
    }

    #[test]
    fn generate_entropy_padding_lowers_popcount() {
        // Simulated encrypted payload: all 0xAA (popcount = 4.0 per byte)
        let encrypted = vec![0xAA; 100];
        assert!(!is_popcount_exempt(&encrypted));

        let padding = generate_entropy_padding(&encrypted, POPCOUNT_EXEMPT_LOW, 512);
        assert!(!padding.is_empty());

        // Combined should be exempt
        let mut combined = padding.clone();
        combined.extend_from_slice(&encrypted);
        let combined_popcount = popcount_per_byte(&combined);
        assert!(
            combined_popcount <= POPCOUNT_EXEMPT_LOW,
            "expected <= {POPCOUNT_EXEMPT_LOW}, got {combined_popcount}"
        );
    }

    #[test]
    fn generate_entropy_padding_noop_for_exempt() {
        // Already exempt (all zeros)
        assert!(generate_entropy_padding(&[0x00; 100], POPCOUNT_EXEMPT_LOW, 512).is_empty());
        // Already exempt (high popcount)
        assert!(generate_entropy_padding(&[0xFF; 100], POPCOUNT_EXEMPT_LOW, 512).is_empty());
        // Empty payload
        assert!(generate_entropy_padding(&[], POPCOUNT_EXEMPT_LOW, 512).is_empty());
    }

    #[test]
    fn generate_entropy_padding_respects_max() {
        let encrypted = vec![0xAA; 1000];
        let padding = generate_entropy_padding(&encrypted, POPCOUNT_EXEMPT_LOW, 50);
        assert!(padding.len() <= 50);
    }

    #[test]
    fn generate_entropy_padding_unreachable_target() {
        // Target below padding byte popcount (2.0) — impossible
        assert!(generate_entropy_padding(&[0xAA; 10], 1.0, 512).is_empty());
    }
}
