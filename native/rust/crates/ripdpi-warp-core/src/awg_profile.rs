//! Bounded AWG 3.1 ranges and signature programs. Noise and cookies are unchanged.

use crate::amneziawg::{fill_random, rand_u32};
use std::time::{SystemTime, UNIX_EPOCH};

pub(crate) const WIRE_PROFILE: &str = "awg-3.1-ranges-v1";
const MAX_PACKET: usize = 1280;
const MAX_TEXT: usize = 4096;
const MAX_TAGS: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct HeaderRange {
    low: u32,
    high: u32,
}

impl HeaderRange {
    pub(crate) fn parse(text: &str) -> Option<Self> {
        let (low, high) = match text.split_once('-') {
            Some((low, high)) => {
                let (low, high) = (canonical_u32(low)?, canonical_u32(high)?);
                // amneziawg-go `UintRange.FromString` accepts `x-x`; only `hi < lo` is invalid.
                if low > high {
                    return None;
                }
                (low, high)
            }
            None => {
                let value = canonical_u32(text)?;
                (value, value)
            }
        };
        Some(Self { low, high })
    }
    pub(crate) fn high(self) -> u32 {
        self.high
    }
    pub(crate) fn contains(self, value: u32) -> bool {
        (self.low..=self.high).contains(&value)
    }
    pub(crate) fn overlaps(self, other: Self) -> bool {
        self.low <= other.high && other.low <= self.high
    }
    pub(crate) fn is_singleton(self, value: u32) -> bool {
        self.low == value && self.high == value
    }
    pub(crate) fn allowed_for_type(self, message_type: u32) -> bool {
        self.low > 4 || self.is_singleton(message_type)
    }
    pub(crate) fn sample(self) -> u32 {
        self.sample_with(&mut rand_u32)
    }
    fn sample_with(self, random: &mut impl FnMut() -> u32) -> u32 {
        let span = u64::from(self.high) - u64::from(self.low) + 1;
        let space = u64::from(u32::MAX) + 1;
        let limit = space - space % span;
        loop {
            let value = u64::from(random());
            if value < limit {
                return (u64::from(self.low) + value % span) as u32;
            }
        }
    }
}
fn canonical_u32(text: &str) -> Option<u32> {
    if text.is_empty() || (text.len() > 1 && text.starts_with('0')) || !text.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    text.parse().ok()
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum SignatureTag {
    Bytes(Vec<u8>),
    Random(usize),
    Digits(usize),
    Characters(usize),
    Timestamp,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct SignatureProgram {
    tags: Vec<SignatureTag>,
    size: usize,
}

impl SignatureProgram {
    pub(crate) fn parse(text: &str) -> Option<Self> {
        if text.is_empty() || text.len() > MAX_TEXT {
            return None;
        }
        let mut remaining = text;
        let mut tags = Vec::new();
        let mut size = 0usize;
        while !remaining.is_empty() {
            let (tag, rest) = remaining.strip_prefix('<')?.split_once('>')?;
            let (tag, added) = if tag == "t" {
                (SignatureTag::Timestamp, 4)
            } else if let Some(hex) = tag.strip_prefix("b 0x") {
                if hex.is_empty()
                    || hex.len() % 2 != 0
                    || !hex.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                {
                    return None;
                }
                let bytes = hex
                    .as_bytes()
                    .as_chunks::<2>()
                    .0
                    .iter()
                    .map(|pair| {
                        let nibble = |b: u8| if b.is_ascii_digit() { b - b'0' } else { b - b'a' + 10 };
                        nibble(pair[0]) * 16 + nibble(pair[1])
                    })
                    .collect::<Vec<_>>();
                let length = bytes.len();
                (SignatureTag::Bytes(bytes), length)
            } else {
                let (kind, count) = tag.split_once(' ')?;
                let count = usize::try_from(canonical_u32(count)?).ok()?;
                if !(1..=MAX_PACKET).contains(&count) {
                    return None;
                }
                let tag = match kind {
                    "r" => SignatureTag::Random(count),
                    "rd" => SignatureTag::Digits(count),
                    "rc" => SignatureTag::Characters(count),
                    _ => return None,
                };
                (tag, count)
            };
            size = size.checked_add(added)?;
            if size > MAX_PACKET || tags.len() == MAX_TAGS {
                return None;
            }
            tags.push(tag);
            remaining = rest;
        }
        Some(Self { tags, size })
    }
    pub(crate) fn generate(&self) -> Vec<u8> {
        let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as u32;
        self.generate_with(timestamp, &mut fill_random)
    }
    fn generate_with(&self, timestamp: u32, random: &mut impl FnMut(&mut [u8])) -> Vec<u8> {
        const LETTERS: &[u8] = b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        const DIGITS: &[u8] = b"0123456789";
        let mut packet = Vec::with_capacity(self.size);
        for tag in &self.tags {
            match tag {
                SignatureTag::Bytes(bytes) => packet.extend_from_slice(bytes),
                SignatureTag::Timestamp => packet.extend_from_slice(&timestamp.to_be_bytes()),
                SignatureTag::Random(count) | SignatureTag::Digits(count) | SignatureTag::Characters(count) => {
                    let start = packet.len();
                    packet.resize(start + count, 0);
                    random(&mut packet[start..]);
                    let alphabet = match tag {
                        SignatureTag::Digits(_) => Some(DIGITS),
                        SignatureTag::Characters(_) => Some(LETTERS),
                        _ => None,
                    };
                    if let Some(alphabet) = alphabet {
                        for byte in &mut packet[start..] {
                            *byte = alphabet[usize::from(*byte) % alphabet.len()];
                        }
                    }
                }
            }
        }
        packet
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ranges_are_canonical_bounded_and_disjoint() {
        for invalid in ["", "01", "+1", " 1", "1 ", "2-1", "1-02", "4294967296", "1-2-3"] {
            assert!(HeaderRange::parse(invalid).is_none(), "{invalid}");
        }
        assert!(HeaderRange::parse("7-7").unwrap().is_singleton(7));
        let range = HeaderRange::parse("4294967290-4294967295").unwrap();
        assert!(range.contains(u32::MAX));
        assert!(!range.contains(1));
        assert!(range.overlaps(HeaderRange::parse("4294967295").unwrap()));
        assert!(!range.overlaps(HeaderRange::parse("1-100").unwrap()));
        assert_eq!(HeaderRange::parse("0-4294967295").unwrap().sample_with(&mut || u32::MAX), u32::MAX);
        let mut samples = [u32::MAX, 3].into_iter();
        assert_eq!(HeaderRange::parse("10-19").unwrap().sample_with(&mut || samples.next().unwrap()), 13);
    }
    #[test]
    fn signature_matches_upstream_byte_and_timestamp_layout() {
        let program = SignatureProgram::parse("<b 0x01ab><r 2><rd 2><rc 2><t>").unwrap();
        let packet = program.generate_with(0x12345678, &mut |bytes| bytes.fill(1));
        assert_eq!(packet, [1, 0xab, 1, 1, b'1', b'1', b'b', b'b', 0x12, 0x34, 0x56, 0x78]);
    }
    #[test]
    fn signature_refuses_unsupported_unbounded_and_noncanonical_programs() {
        for invalid in [
            "",
            "aabb",
            "<b 0x>",
            "<b 0xA0>",
            "<b 0x0>",
            "<r 0>",
            "<r 01>",
            "<r 1281>",
            "<t >",
            "<d>",
            "<t> ",
            "<t",
            "<r 1><r 1280>",
            "<r  1>",
        ] {
            assert!(SignatureProgram::parse(invalid).is_none(), "{invalid}");
        }
        assert!(SignatureProgram::parse(&"<t>".repeat(65)).is_none());
        assert!(SignatureProgram::parse("<r 1280>").is_some());
        assert!(SignatureProgram::parse(&"<t>".repeat(64)).is_some());
        assert!(SignatureProgram::parse(&format!("<b 0x{}>", "aa".repeat(1281))).is_none());
    }
    #[test]
    fn signature_generates_fresh_dynamic_values_in_valid_alphabets() {
        let program = SignatureProgram::parse("<rd 128><rc 128><r 64>").unwrap();
        let first = program.generate();
        let second = program.generate();
        assert!(first[..128].iter().all(u8::is_ascii_digit));
        assert!(first[128..256].iter().all(u8::is_ascii_alphabetic));
        assert_ne!(first, second);
    }
}
