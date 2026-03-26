//! Entropy analysis and bypass padding for DPI evasion.
//!
//! Two detection models are supported:
//!
//! **Popcount (GFW):** The GFW detects fully encrypted protocols by
//! computing the average popcount (1-bits per byte) of the first data
//! packet. Connections with average popcount between 3.4 and 4.6 are
//! blocked. Reference: "How the Great Firewall of China Detects and Blocks
//! Fully Encrypted Traffic" (USENIX Security 2023).
//!
//! **Shannon entropy (middlebox):** DPI systems flag traffic with suspiciously
//! high Shannon entropy (~8.0 bits/byte). Legitimate TLS has entropy
//! ~7.92-7.96 due to structured record headers, while naive obfuscation
//! produces ~7.99-8.0.

/// GFW exemption threshold: average popcount per byte <= this value is exempt.
pub const POPCOUNT_EXEMPT_LOW: f32 = 3.4;

/// GFW exemption threshold: average popcount per byte >= this value is exempt.
pub const POPCOUNT_EXEMPT_HIGH: f32 = 4.6;

/// Average popcount of the padding byte ('A' = 0x41, popcount = 2).
const PAD_BYTE_POPCOUNT: f32 = 2.0;

/// The byte used for padding. 'A' (0x41) has popcount 2, well below 3.4.
const PAD_BYTE: u8 = b'A';

/// Shannon entropy in bits per byte (0.0-8.0).
///
/// Computes `-sum(p * log2(p))` over the byte frequency distribution.
/// Returns 0.0 for empty or uniform data, 8.0 for perfectly uniform
/// distribution across all 256 byte values.
pub fn shannon_entropy(data: &[u8]) -> f32 {
    if data.is_empty() {
        return 0.0;
    }
    let mut freq = [0u32; 256];
    for &b in data {
        freq[b as usize] += 1;
    }
    let len = data.len() as f32;
    let mut entropy: f32 = 0.0;
    for &count in &freq {
        if count > 0 {
            let p = count as f32 / len;
            entropy -= p * p.log2();
        }
    }
    entropy
}

/// Returns true if the payload's Shannon entropy exceeds `threshold`,
/// indicating suspiciously high randomness (e.g. naive obfuscation).
///
/// Legitimate TLS typically has entropy ~7.92-7.96 due to structured
/// record headers. Pure random/obfuscated traffic sits at ~7.99-8.0.
pub fn is_shannon_suspicious(data: &[u8], threshold: f32) -> bool {
    !data.is_empty() && shannon_entropy(data) > threshold
}

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

/// TLS record header pattern used as structured padding.
/// Content type 0x17 (application data), TLS 1.2 version (0x03, 0x03),
/// followed by two length bytes -- a pattern that appears frequently in
/// legitimate TLS traffic and has Shannon entropy ~3.5-4.0.
const TLS_HEADER_PATTERN: [u8; 5] = [0x17, 0x03, 0x03, 0x00, 0x20];

/// Generate padding that, when prepended to `payload`, brings the combined
/// Shannon entropy at or below `target`.
///
/// The padding uses repeating TLS record header patterns mixed with
/// low-entropy filler to produce a realistic byte distribution. Returns
/// an empty vec if the payload already meets the target or is empty.
pub fn generate_shannon_padding(payload: &[u8], target: f32, max_pad: usize) -> Vec<u8> {
    if payload.is_empty() || max_pad == 0 {
        return Vec::new();
    }
    let current = shannon_entropy(payload);
    if current <= target {
        return Vec::new();
    }

    // Binary search for the minimum padding length that achieves the target.
    // The padding pattern has low entropy (~3.5), so adding it reduces the
    // aggregate. We search up to max_pad.
    let mut lo: usize = 1;
    let mut hi: usize = max_pad;
    let mut best: Option<usize> = None;

    while lo <= hi {
        let mid = lo + (hi - lo) / 2;
        let pad = build_structured_padding(mid);
        let combined = shannon_entropy_combined(payload, &pad);
        if combined <= target {
            best = Some(mid);
            hi = mid - 1;
        } else {
            lo = mid + 1;
        }
    }

    match best {
        Some(len) => build_structured_padding(len),
        None => Vec::new(),
    }
}

/// Shannon entropy of two slices treated as one contiguous buffer,
/// without allocating a combined buffer.
fn shannon_entropy_combined(a: &[u8], b: &[u8]) -> f32 {
    let total = a.len() + b.len();
    if total == 0 {
        return 0.0;
    }
    let mut freq = [0u32; 256];
    for &byte in a {
        freq[byte as usize] += 1;
    }
    for &byte in b {
        freq[byte as usize] += 1;
    }
    let len = total as f32;
    let mut entropy: f32 = 0.0;
    for &count in &freq {
        if count > 0 {
            let p = count as f32 / len;
            entropy -= p * p.log2();
        }
    }
    entropy
}

/// Build structured padding of `len` bytes from repeating TLS header
/// patterns. This produces low-entropy, realistic-looking padding.
fn build_structured_padding(len: usize) -> Vec<u8> {
    let mut buf = Vec::with_capacity(len);
    while buf.len() < len {
        let remaining = len - buf.len();
        if remaining >= TLS_HEADER_PATTERN.len() {
            buf.extend_from_slice(&TLS_HEADER_PATTERN);
        } else {
            buf.extend_from_slice(&TLS_HEADER_PATTERN[..remaining]);
        }
    }
    buf
}

/// Generate padding that satisfies both popcount and Shannon entropy targets.
///
/// Returns the larger of the two padding buffers needed, or empty if the
/// payload already passes both checks.
pub fn generate_combined_padding(
    payload: &[u8],
    popcount_target: f32,
    shannon_target: f32,
    max_pad: usize,
) -> Vec<u8> {
    let popcount_pad = generate_entropy_padding(payload, popcount_target, max_pad);
    let shannon_pad = generate_shannon_padding(payload, shannon_target, max_pad);
    if shannon_pad.len() >= popcount_pad.len() {
        shannon_pad
    } else {
        popcount_pad
    }
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

    #[test]
    fn shannon_padding_reduces_entropy() {
        // High-entropy payload simulating encrypted traffic
        let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
        let original = shannon_entropy(&payload);
        assert!(original > 7.9, "precondition: high entropy, got {original}");

        let padding = generate_shannon_padding(&payload, 7.92, 512);
        assert!(!padding.is_empty(), "padding should be generated");

        let combined = shannon_entropy_combined(&payload, &padding);
        assert!(combined <= 7.92, "expected <= 7.92, got {combined}");
    }

    #[test]
    fn shannon_padding_noop_for_low_entropy() {
        // Low entropy payload doesn't need padding
        let payload = b"Hello world, this is low entropy text!";
        assert!(generate_shannon_padding(payload, 7.92, 512).is_empty());
        // Empty payload
        assert!(generate_shannon_padding(&[], 7.92, 512).is_empty());
    }

    #[test]
    fn shannon_padding_respects_max() {
        let payload: Vec<u8> = (0..4096).map(|i| (i % 256) as u8).collect();
        let padding = generate_shannon_padding(&payload, 7.0, 50);
        assert!(padding.len() <= 50);
    }

    #[test]
    fn combined_padding_satisfies_both() {
        // Payload with popcount ~4.0 (in GFW detection window) and high Shannon entropy
        let payload = vec![0xAA; 200]; // popcount=4.0, entropy=0.0 (uniform)
        // For this uniform payload, Shannon is 0.0 (already low), but popcount is 4.0
        let padding = generate_combined_padding(&payload, POPCOUNT_EXEMPT_LOW, 7.92, 512);
        if !padding.is_empty() {
            let mut combined = padding.clone();
            combined.extend_from_slice(&payload);
            let pc = popcount_per_byte(&combined);
            assert!(pc <= POPCOUNT_EXEMPT_LOW, "popcount {pc} > {POPCOUNT_EXEMPT_LOW}");
        }
    }

    #[test]
    fn combined_padding_handles_high_entropy() {
        // High entropy payload that needs Shannon padding
        let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
        let padding = generate_combined_padding(&payload, POPCOUNT_EXEMPT_LOW, 7.92, 512);
        assert!(!padding.is_empty());
        let combined = shannon_entropy_combined(&payload, &padding);
        assert!(combined <= 7.92, "expected <= 7.92, got {combined}");
    }

    #[test]
    fn shannon_entropy_empty() {
        assert_eq!(shannon_entropy(&[]), 0.0);
    }

    #[test]
    fn shannon_entropy_uniform_bytes() {
        // All same byte: only one symbol, entropy = 0
        assert_eq!(shannon_entropy(&[0x42; 100]), 0.0);
    }

    #[test]
    fn shannon_entropy_all_256_values() {
        // Perfect uniform distribution across all 256 values: entropy = 8.0
        let data: Vec<u8> = (0..=255u8).collect();
        let e = shannon_entropy(&data);
        assert!((e - 8.0).abs() < 0.001, "expected ~8.0, got {e}");
    }

    #[test]
    fn shannon_entropy_printable_ascii() {
        // Printable ASCII text should be in the 4-6 range
        let data = b"Hello, world! This is a test of Shannon entropy on printable ASCII text.";
        let e = shannon_entropy(data);
        assert!(e > 3.5 && e < 6.5, "expected 3.5-6.5, got {e}");
    }

    #[test]
    fn shannon_entropy_encrypted_like() {
        // Simulated high-entropy data: cycle through all 256 bytes many times
        let data: Vec<u8> = (0..4096).map(|i| (i % 256) as u8).collect();
        let e = shannon_entropy(&data);
        assert!(e > 7.9, "expected >7.9, got {e}");
    }

    #[test]
    fn is_shannon_suspicious_detects_high_entropy() {
        let random_like: Vec<u8> = (0..4096).map(|i| (i % 256) as u8).collect();
        assert!(is_shannon_suspicious(&random_like, 7.96));
        // Low entropy text is not suspicious
        assert!(!is_shannon_suspicious(b"AAAAAAAAAA", 7.96));
        // Empty is not suspicious
        assert!(!is_shannon_suspicious(&[], 7.96));
    }
}
