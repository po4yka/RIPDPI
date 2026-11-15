//! Resolved Mieru v3.36.0 traffic settings. Kotlin owns implicit default selection.

use ring::rand::SecureRandom;

use crate::error::{MieruError, Result};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MieruTrafficPattern {
    pub fragment: bool,
    pub fragment_max_sleep_ms: u8,
    pub nonce_type: u8,
    pub nonce_min_len: u8,
    pub nonce_max_len: u8,
    pub nonce_prefixes: Vec<Vec<u8>>,
    pub max_middle_padding: u8,
    pub max_end_padding: u8,
    pub low_entropy_mode: u8,
    pub low_entropy_rotation: u8,
}

impl MieruTrafficPattern {
    pub fn validate(&self) -> Result<()> {
        if self.fragment_max_sleep_ms > 100
            || self.nonce_type > 3
            || self.nonce_min_len > 12
            || self.nonce_max_len > 12
            || self.nonce_min_len > self.nonce_max_len
            || self.nonce_prefixes.len() > 256
            || self.nonce_prefixes.iter().any(|value| value.len() > 12)
            || self.low_entropy_mode > 4
            || !valid_rotation(self.low_entropy_rotation)
        {
            return Err(MieruError::Protocol("invalid Mieru traffic pattern".into()));
        }
        Ok(())
    }

    pub(crate) fn shape_nonce(&self, nonce: &mut [u8], rng: &dyn SecureRandom) -> Result<()> {
        let minimum = self.nonce_min_len.min(self.nonce_max_len);
        let len = usize::from(minimum) + random_below(rng, usize::from(self.nonce_max_len - minimum) + 1)?;
        match self.nonce_type {
            0 => {}
            1 => {
                for byte in &mut nonce[..len] {
                    if !(0x20..=0x7e).contains(byte) {
                        let low = *byte & 0x7f;
                        *byte = if *byte & 0x80 != 0 && (0x20..=0x7e).contains(&low) {
                            low
                        } else {
                            0x20 + random_below(rng, 95)? as u8
                        };
                    }
                }
            }
            2 => {
                const COMMON: &[u8; 64] = b"A7k9mP2vX5bW1qRtN8zL4fJyHcVsDwQxPlKzMbRtNjFfGyHcVsDwQxPlKzMbRtNj";
                for byte in &mut nonce[..len] {
                    *byte = COMMON[usize::from(*byte & 63)];
                }
            }
            3 if !self.nonce_prefixes.is_empty() => {
                let prefix = &self.nonce_prefixes[random_below(rng, self.nonce_prefixes.len())?];
                nonce[..prefix.len()].copy_from_slice(prefix);
            }
            3 => {}
            _ => return Err(MieruError::Protocol("invalid Mieru nonce type".into())),
        }
        Ok(())
    }
}

pub(crate) fn random_below(rng: &dyn SecureRandom, bound: usize) -> Result<usize> {
    if bound <= 1 {
        return Ok(0);
    }
    let bound = u32::try_from(bound).map_err(|_| MieruError::Protocol("random bound too large".into()))?;
    let cutoff = u32::MAX - u32::MAX % bound;
    loop {
        let mut bytes = [0; 4];
        rng.fill(&mut bytes).map_err(|_| MieruError::Crypto("traffic pattern rng"))?;
        let value = u32::from_be_bytes(bytes);
        if value < cutoff {
            return Ok((value % bound) as usize);
        }
    }
}

pub(crate) fn valid_rotation(rotation: u8) -> bool {
    rotation <= 15 || rotation.is_multiple_of(16)
}

/// # Cancel safety:
/// NOT cancel-safe: fragments are one authenticated frame. Only its owned carrier writer may call this and discard the transport on cancellation.
pub(crate) async fn write_frame<W: tokio::io::AsyncWrite + Unpin>(
    writer: &mut W,
    bytes: &[u8],
    pattern: Option<&MieruTrafficPattern>,
    rng: &ring::rand::SystemRandom,
) -> Result<()> {
    use tokio::io::AsyncWriteExt;
    if let Some(pattern) = pattern.filter(|pattern| pattern.fragment) {
        let minimum = bytes.len().isqrt() + 1;
        let maximum = minimum.max(bytes.len() / 2);
        let mut offset = 0;
        while offset < bytes.len() {
            let length = (minimum + random_below(rng, maximum - minimum + 1)?).min(bytes.len() - offset);
            writer.write_all(&bytes[offset..offset + length]).await?;
            offset += length;
            if pattern.fragment_max_sleep_ms != 0 {
                let delay = random_below(rng, usize::from(pattern.fragment_max_sleep_ms) + 1)?;
                tokio::time::sleep(std::time::Duration::from_millis(delay as u64)).await;
            }
        }
    } else {
        writer.write_all(bytes).await?;
    }
    writer.flush().await?;
    Ok(())
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use ring::rand::SystemRandom;

    pub(crate) fn pattern() -> MieruTrafficPattern {
        MieruTrafficPattern {
            fragment: false,
            fragment_max_sleep_ms: 0,
            nonce_type: 0,
            nonce_min_len: 6,
            nonce_max_len: 12,
            nonce_prefixes: Vec::new(),
            max_middle_padding: 0,
            max_end_padding: 0,
            low_entropy_mode: 0,
            low_entropy_rotation: 0,
        }
    }

    #[test]
    fn fixed_nonce_and_common64_follow_pinned_prefix_rules() {
        let mut config = pattern();
        config.nonce_type = 3;
        config.nonce_prefixes = vec![vec![0, 1, 2, 3]];
        let mut bytes = [0xab; 24];
        config.shape_nonce(&mut bytes, &SystemRandom::new()).expect("shape");
        assert_eq!(&bytes[..4], &[0, 1, 2, 3]);
        assert_eq!(&bytes[4..], &[0xab; 20]);
        config.nonce_type = 2;
        config.nonce_min_len = 12;
        config.nonce_max_len = 12;
        let mut bytes = [0; 24];
        config.shape_nonce(&mut bytes, &SystemRandom::new()).expect("shape");
        assert_eq!(&bytes[..12], b"AAAAAAAAAAAA");
        assert_eq!(&bytes[12..], &[0; 12]);
    }

    #[test]
    fn pattern_bounds_reject_unsafe_prefix_lengths_and_delay() {
        let mut config = pattern();
        config.nonce_max_len = 13;
        assert!(config.validate().is_err());
        config.nonce_max_len = 12;
        config.nonce_min_len = 12;
        config.nonce_max_len = 6;
        assert!(config.validate().is_err());
        config.nonce_min_len = 6;
        config.nonce_max_len = 12;
        config.fragment_max_sleep_ms = 101;
        assert!(config.validate().is_err());
        config.fragment_max_sleep_ms = 0;
        config.low_entropy_rotation = 17;
        assert!(config.validate().is_err());
    }
}
