//! Segment padding shaped as upstream `pkg/protocol/padding.go` does it for
//! the stream carrier (see `PROTOCOL.md` §3).
//!
//! - Data segments: random bytes; each of the prefix and suffix lengths is
//!   upstream `rng.Intn(255)`, which favours short padding.
//! - Session segments (`openSession*`, `closeSession*`): the suffix follows
//!   `buildRecommendedPaddingOpts`. A fixed per-user strategy picks either a
//!   run of printable ASCII (24 to 40 bytes, inside random bytes), or bit-biased
//!   bytes that move the segment's 0/1 bit ratio towards 0.325. Both defeat
//!   fully-encrypted-traffic heuristics (no long printable run, popcount near
//!   4 per byte) on the first flight.
//!
//! The strategy and run length are derived from the username, where upstream
//! also mixes in its app version and the host name.

use ring::digest::{SHA256, digest};
use ring::rand::SecureRandom;

use crate::cipher::TAG_LEN;
use crate::error::{MieruError, Result};
use crate::metadata::METADATA_LEN;

/// Upstream `maxPaddingSize` for the stream transport.
const MAX_PADDING: usize = 255;
/// Upstream `recommendedConsecutiveASCIILen` is `24 + FixedIntVH(17)`.
const MIN_ASCII_RUN: usize = 24;
const ASCII_RUN_SPREAD: u32 = 17;
/// Upstream `recommendedTargetProbability`.
const TARGET_PROBABILITY: f64 = 0.325;
/// Upstream `streamOverhead`: sealed metadata plus the payload tag.
const STREAM_OVERHEAD: usize = METADATA_LEN + TAG_LEN * 2;
const PRINTABLE_FIRST: u8 = 0x20;
const PRINTABLE_LAST: u8 = 0x7E;

/// The per-user session padding strategy.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SessionPadding {
    /// A printable run of this many bytes.
    Ascii(usize),
    /// Bit-biased bytes towards [`TARGET_PROBABILITY`].
    Entropy,
}

impl SessionPadding {
    pub(crate) fn for_user(username: &[u8]) -> Self {
        let hash = digest(&SHA256, username);
        let bytes = hash.as_ref();
        if bytes[3] & 1 == 0 {
            let spread = u32::from_be_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]) % ASCII_RUN_SPREAD;
            // Infallible: `spread < 17` fits in `usize`.
            Self::Ascii(MIN_ASCII_RUN + spread as usize)
        } else {
            Self::Entropy
        }
    }

    /// Suffix padding for a session segment carrying `payload_len` bytes.
    pub(crate) fn suffix(self, rng: &dyn SecureRandom, payload_len: usize) -> Result<Vec<u8>> {
        match self {
            Self::Ascii(run) => ascii_padding(rng, run),
            Self::Entropy => entropy_padding(rng, STREAM_OVERHEAD + payload_len),
        }
    }
}

/// Length of one data-segment padding (prefix or suffix), as upstream.
pub(crate) fn data_padding_len(rng: &dyn SecureRandom) -> Result<u8> {
    // Infallible: `skewed_up_to(255) <= 255`.
    Ok(skewed_up_to(rng, MAX_PADDING)? as u8)
}

fn ascii_padding(rng: &dyn SecureRandom, run: usize) -> Result<Vec<u8>> {
    let len = run + skewed_up_to(rng, MAX_PADDING - run)?;
    let mut padding = random_bytes(rng, len)?;
    let begin = if len > run { below(rng, len - run)? } else { 0 };
    for byte in &mut padding[begin..begin + run] {
        *byte = printable(rng, *byte)?;
    }
    Ok(padding)
}

fn entropy_padding(rng: &dyn SecureRandom, existing_len: usize) -> Result<Vec<u8>> {
    // As upstream, the segment's own ciphertext is modelled by random bytes.
    let existing = random_bytes(rng, existing_len)?;
    let ones: usize = existing.iter().map(|byte| byte.count_ones() as usize).sum();
    let existing_bits = existing_len * 8;
    let (lower_is_zero, lower_count) =
        if existing_bits - ones <= ones { (true, existing_bits - ones) } else { (false, ones) };

    // Solve lower_count = target * (existing_bits + padding_bits).
    let min_bits = ((lower_count as f64 / TARGET_PROBABILITY) - existing_bits as f64).max(0.0) as usize;
    let min_bytes = min_bits.div_ceil(8);
    let len =
        if min_bytes >= MAX_PADDING { MAX_PADDING } else { min_bytes + skewed_up_to(rng, MAX_PADDING - min_bytes)? };
    let target_lower = ((existing_len + len) * 8) as f64 * TARGET_PROBABILITY;
    let flips = (target_lower as usize).saturating_sub(lower_count).min(len * 8);

    let mut padding = vec![if lower_is_zero { 0xFF } else { 0x00 }; len];
    for _ in 0..flips {
        let bit = below(rng, len * 8)?;
        if lower_is_zero {
            padding[bit / 8] &= !(1 << (bit % 8));
        } else {
            padding[bit / 8] |= 1 << (bit % 8);
        }
    }
    Ok(padding)
}

/// Upstream `common.ToPrintableChar` for one byte.
fn printable(rng: &dyn SecureRandom, byte: u8) -> Result<u8> {
    if (PRINTABLE_FIRST..=PRINTABLE_LAST).contains(&byte) {
        return Ok(byte);
    }
    let low = byte & 0x7F;
    if byte & 0x80 != 0 && (PRINTABLE_FIRST..=PRINTABLE_LAST).contains(&low) {
        return Ok(low);
    }
    let span = usize::from(PRINTABLE_LAST - PRINTABLE_FIRST) + 1;
    // Infallible: `below(span) < 95`.
    Ok(PRINTABLE_FIRST + below(rng, span)? as u8)
}

/// Upstream `rng.Intn(n)`: a uniform `0..=n` scaled by `u^1.5`, which
/// favours small values.
fn skewed_up_to(rng: &dyn SecureRandom, n: usize) -> Result<usize> {
    let uniform = below(rng, n + 1)? as f64;
    let scale = unit(rng)?;
    Ok((uniform * (scale * scale * scale).sqrt()) as usize)
}

/// A uniform value in `0..n`, for `n > 0`.
fn below(rng: &dyn SecureRandom, n: usize) -> Result<usize> {
    Ok(((unit(rng)? * n as f64) as usize).min(n.saturating_sub(1)))
}

/// A uniform value in `[0, 1)`.
fn unit(rng: &dyn SecureRandom) -> Result<f64> {
    let mut bytes = [0u8; 8];
    rng.fill(&mut bytes).map_err(|_| MieruError::Crypto("padding rng"))?;
    Ok((u64::from_be_bytes(bytes) >> 11) as f64 / (1u64 << 53) as f64)
}

fn random_bytes(rng: &dyn SecureRandom, len: usize) -> Result<Vec<u8>> {
    let mut bytes = vec![0u8; len];
    if len > 0 {
        rng.fill(&mut bytes).map_err(|_| MieruError::Crypto("padding rng"))?;
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use ring::rand::SystemRandom;

    use super::*;

    fn longest_printable_run(bytes: &[u8]) -> usize {
        let mut longest = 0;
        let mut current = 0;
        for byte in bytes {
            current = if (PRINTABLE_FIRST..=PRINTABLE_LAST).contains(byte) { current + 1 } else { 0 };
            longest = longest.max(current);
        }
        longest
    }

    #[test]
    fn ascii_session_padding_carries_a_printable_run() {
        let rng = SystemRandom::new();
        for run in [MIN_ASCII_RUN, MIN_ASCII_RUN + 16] {
            for _ in 0..200 {
                let padding = SessionPadding::Ascii(run).suffix(&rng, 0).expect("padding");
                assert!((run..=MAX_PADDING).contains(&padding.len()), "length {}", padding.len());
                assert!(longest_printable_run(&padding) >= run, "no {run}-byte printable run");
            }
        }
    }

    /// Upstream biases the bit ratio of metadata + padding towards 0.325.
    #[test]
    fn entropy_session_padding_skews_the_bit_ratio() {
        let rng = SystemRandom::new();
        for _ in 0..200 {
            let padding = SessionPadding::Entropy.suffix(&rng, 0).expect("padding");
            // 64 random bytes carry ~256 zero bits; 0.325 needs ~276 more bits.
            assert!(padding.len() >= 16, "padding too short: {}", padding.len());
            let ones: u32 = padding.iter().map(|byte| byte.count_ones()).sum();
            let ratio = f64::from(ones) / (padding.len() * 8) as f64;
            assert!(!(0.3..=0.7).contains(&ratio), "padding is not bit-biased: {ratio}");
        }
    }

    #[test]
    fn data_padding_spans_the_stream_maximum_and_favours_short_lengths() {
        let rng = SystemRandom::new();
        let lengths: Vec<u8> = (0..4000).map(|_| data_padding_len(&rng).expect("length")).collect();
        assert!(lengths.iter().any(|&len| len > 128), "the stream maximum is 255");
        let short = lengths.iter().filter(|&&len| len < 64).count();
        assert!(short > lengths.len() / 2, "upstream rng.Intn favours short padding");
    }

    #[test]
    fn strategy_is_fixed_per_user() {
        assert_eq!(SessionPadding::for_user(b"alice"), SessionPadding::for_user(b"alice"));
        let strategies: Vec<_> =
            (0..32).map(|index| SessionPadding::for_user(format!("user{index}").as_bytes())).collect();
        assert!(strategies.iter().any(|strategy| matches!(strategy, SessionPadding::Ascii(_))));
        assert!(strategies.contains(&SessionPadding::Entropy));
    }
}
