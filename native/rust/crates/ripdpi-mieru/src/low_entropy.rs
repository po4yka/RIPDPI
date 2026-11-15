//! Pinned Mieru v3.36.0 ciphertext-body expansion. The AEAD tag is unchanged.

use crate::error::{MieruError, Result};
use crate::traffic_pattern::valid_rotation;

pub(crate) const MAX_EXTRACTED: usize = 32 * 1024;
const CHUNK: usize = 8;

fn source_bytes(mode: u8) -> Result<usize> {
    match mode {
        1..=4 => Ok(usize::from(mode) + 3),
        _ => Err(invalid()),
    }
}

fn invalid() -> MieruError {
    MieruError::Protocol("invalid Mieru low entropy payload".into())
}

pub(crate) fn encoded_len(length: usize, mode: u8) -> Result<usize> {
    let source = source_bytes(mode)?;
    if length == 0 || length > MAX_EXTRACTED {
        return Err(invalid());
    }
    let result = length.div_ceil(source) * CHUNK;
    if result > usize::from(u16::MAX) {
        return Err(invalid());
    }
    Ok(result)
}

fn validate(mode: u8, mask: u32, rotation: u8) -> Result<usize> {
    let source = source_bytes(mode)?;
    if mask.count_ones() as usize != source * 4 || !valid_rotation(rotation) {
        return Err(invalid());
    }
    Ok(source)
}

fn chunk_mask(mask: u32, rotation: u8, chunk: usize) -> u64 {
    let repeated = (u64::from(mask) << 32) | u64::from(mask);
    let shift = (chunk % 64) as u32;
    if rotation <= 15 {
        repeated.rotate_right(shift * u32::from(rotation))
    } else {
        repeated.rotate_left(shift * u32::from(rotation / 16))
    }
}

fn deposit(mut value: u64, mut mask: u64) -> u64 {
    let mut result = 0;
    while mask != 0 {
        let bit = mask.isolate_lowest_one();
        if value & 1 != 0 {
            result |= bit;
        }
        value >>= 1;
        mask &= mask - 1;
    }
    result
}

fn extract(value: u64, mut mask: u64) -> u64 {
    let mut result = 0;
    let mut output = 1;
    while mask != 0 {
        let bit = mask.isolate_lowest_one();
        if value & bit != 0 {
            result |= output;
        }
        mask &= mask - 1;
        output <<= 1;
    }
    result
}

fn low_bits(bits: usize) -> u64 {
    if bits == 64 { u64::MAX } else { (1u64 << bits) - 1 }
}

pub(crate) fn encode(source: &[u8], mode: u8, mask: u32, rotation: u8, padding_bit: bool) -> Result<Vec<u8>> {
    let per_chunk = validate(mode, mask, rotation)?;
    let mut output = vec![0; encoded_len(source.len(), mode)?];
    for (index, bytes) in source.chunks(per_chunk).enumerate() {
        let mut scratch = [0; CHUNK];
        scratch[CHUNK - bytes.len()..].copy_from_slice(bytes);
        let mask = chunk_mask(mask, rotation, index);
        let data_mask = deposit(low_bits(bytes.len() * 8), mask);
        let mut value = deposit(u64::from_be_bytes(scratch), mask);
        if padding_bit {
            value |= !data_mask;
        }
        output[index * CHUNK..(index + 1) * CHUNK].copy_from_slice(&value.to_be_bytes());
    }
    Ok(output)
}

pub(crate) fn decode(encoded: &[u8], length: usize, mode: u8, mask: u32, rotation: u8) -> Result<Vec<u8>> {
    let per_chunk = validate(mode, mask, rotation)?;
    if encoded.len() != encoded_len(length, mode)? {
        return Err(invalid());
    }
    let mut output = Vec::with_capacity(length);
    let mut padding_bit = None;
    for (index, bytes) in encoded.as_chunks::<CHUNK>().0.iter().enumerate() {
        let source_len = per_chunk.min(length - output.len());
        let value = u64::from_be_bytes(*bytes);
        let mask = chunk_mask(mask, rotation, index);
        let data_mask = deposit(low_bits(source_len * 8), mask);
        let padding_mask = !data_mask;
        let padding = value & padding_mask;
        let bit = if padding == 0 {
            false
        } else if padding == padding_mask {
            true
        } else {
            return Err(invalid());
        };
        if padding_bit.is_some_and(|previous| previous != bit) {
            return Err(invalid());
        }
        padding_bit = Some(bit);
        let source = extract(value, mask).to_be_bytes();
        output.extend_from_slice(&source[CHUNK - source_len..]);
    }
    Ok(output)
}

pub(crate) fn prepare_metadata(
    meta: &mut [u8; 32],
    length: usize,
    pattern: Option<&crate::MieruTrafficPattern>,
    rng: &dyn ring::rand::SecureRandom,
) -> Result<()> {
    let Some(pattern) = pattern.filter(|pattern| pattern.low_entropy_mode != 0) else {
        return Ok(());
    };
    if !matches!(meta[0], 6 | 7) || length == 0 {
        return Ok(());
    }
    let encoded = encoded_len(length, pattern.low_entropy_mode)?;
    let mut positions: Vec<u8> = (0..32).collect();
    let count = (usize::from(pattern.low_entropy_mode) + 3) * 4;
    for index in 0..count {
        let selected = index + crate::traffic_pattern::random_below(rng, 32 - index)?;
        positions.swap(index, selected);
    }
    let mask = positions[..count].iter().fold(0u32, |mask, bit| mask | (1u32 << bit));
    meta[0] += 4;
    meta[1] = pattern.low_entropy_mode;
    meta[22..24].copy_from_slice(&u16::try_from(encoded).map_err(|_| invalid())?.to_be_bytes());
    meta[25..29].copy_from_slice(&mask.to_be_bytes());
    meta[29..31].copy_from_slice(&u16::try_from(length).map_err(|_| invalid())?.to_be_bytes());
    meta[31] = pattern.low_entropy_rotation;
    Ok(())
}

pub(crate) struct Metadata {
    mode: u8,
    mask: u32,
    rotation: u8,
    length: usize,
    encoded: usize,
}

pub(crate) fn metadata(meta: &[u8; 32]) -> Result<Option<Metadata>> {
    if !matches!(meta[0], 10 | 11) {
        return Ok(None);
    }
    let mode = meta[1];
    let mask = u32::from_be_bytes(meta[25..29].try_into().map_err(|_| invalid())?);
    let length = usize::from(u16::from_be_bytes([meta[29], meta[30]]));
    let encoded = usize::from(u16::from_be_bytes([meta[22], meta[23]]));
    validate(mode, mask, meta[31])?;
    if (if length == 0 { 0 } else { encoded_len(length, mode)? }) != encoded {
        return Err(invalid());
    }
    Ok(Some(Metadata { mode, mask, rotation: meta[31], length, encoded }))
}

pub(crate) fn encode_encrypted(sealed: Vec<u8>, meta: &[u8; 32], padding_bit: bool) -> Result<Vec<u8>> {
    let Some(info) = metadata(meta)? else {
        return Ok(sealed);
    };
    if sealed.len() != info.length + crate::cipher::TAG_LEN {
        return Err(invalid());
    }
    let mut expanded = encode(&sealed[..info.length], info.mode, info.mask, info.rotation, padding_bit)?;
    expanded.extend_from_slice(&sealed[info.length..]);
    Ok(expanded)
}

pub(crate) fn decode_encrypted(sealed: Vec<u8>, meta: &[u8; 32]) -> Result<Vec<u8>> {
    let Some(info) = metadata(meta)? else {
        return Ok(sealed);
    };
    if sealed.len() != info.encoded + crate::cipher::TAG_LEN {
        return Err(invalid());
    }
    let mut decoded = decode(&sealed[..info.encoded], info.length, info.mode, info.mask, info.rotation)?;
    decoded.extend_from_slice(&sealed[info.encoded..]);
    Ok(decoded)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pinned_production_go_vectors_cover_every_mode_rotation_and_padding_polarity() {
        // Generated by actual v3.36.0 encodeLowEntropyPayloadWithPaddingBit, commit 155ebbd60.
        // Production source SHA-256: 7d8eee8eaeb5e30066d04a941101ca9b3e40c4d9cd98ad6150306b8c4a8f9611.
        let cases = [
            (1, 0x0000ffff, 0, false, "000000010000020300000405000006070000080900000a0b000000000000000c"),
            (1, 0x0000ffff, 0, true, "ffff0001ffff0203ffff0405ffff0607ffff0809ffff0a0bffffffffffffff0c"),
            (1, 0x0000ffff, 3, false, "00000001000002030000040500000607080000090800020b000000000000000c"),
            (1, 0x0000ffff, 3, true, "ffff0001ffff02031fffe4051fffe6070bfffc090bfffe0bffffffffff7fff8c"),
            (1, 0x0000ffff, 48, false, "0000000100000203000020280000303800020240000282c00000000000001800"),
            (1, 0x0000ffff, 48, true, "ffff0001ffff0203fff8202ffff8303fffc2027fffc282fffffffffffffe19ff"),
            (2, 0x000fffff, 0, false, "0000001000020304000050600007080900000000000a0b0c"),
            (2, 0x000fffff, 0, true, "fff00010fff20304fff05060fff70809fffffff0fffa0b0c"),
            (2, 0x000fffff, 3, false, "0000001000020304000050606001080900000000a0000b0c"),
            (2, 0x000fffff, 3, true, "fff00010fff203041ffe50607fff0809fffffff0a3ffcb0c"),
            (2, 0x000fffff, 48, false, "00000010000203040002830000384048000000000282c300"),
            (2, 0x000fffff, 48, true, "fff00010fff20304ff828307ffb8404ffffffc3ffe82c33f"),
            (3, 0x00ffffff, 0, false, "00000102000304050006070800090a0b000000000000000c"),
            (3, 0x00ffffff, 0, true, "ff000102ff030405ff060708ff090a0bffffffffffffff0c"),
            (3, 0x00ffffff, 3, false, "00000102000304050006070800090a0b000000000000000c"),
            (3, 0x00ffffff, 3, true, "ff000102ff0304051fe607081fe90a0bffffffffffffff0c"),
            (3, 0x00ffffff, 48, false, "000001020003040500303840004850580000000000000300"),
            (3, 0x00ffffff, 48, true, "ff000102ff030405f8303847f848505fffffffffffffc33f"),
            (4, 0x0fffffff, 0, false, "000010200304050600007080090a0b0c"),
            (4, 0x0fffffff, 0, true, "f0001020f3040506fff07080f90a0b0c"),
            (4, 0x0fffffff, 3, false, "000010200304050600007080810a0b0c"),
            (4, 0x0fffffff, 3, true, "f0001020f3040506fff070809f0a0b0c"),
            (4, 0x0fffffff, 48, false, "00001020030405060003840048505860"),
            (4, 0x0fffffff, 48, true, "f0001020f3040506ff838407c8505867"),
        ];
        let source: Vec<u8> = (0..13).collect();
        for (mode, mask, rotation, bit, encoded) in cases {
            let expected: Vec<u8> = encoded
                .as_bytes()
                .as_chunks::<2>()
                .0
                .iter()
                .map(|pair| {
                    let digits = std::str::from_utf8(pair).expect("fixture ASCII");
                    u8::from_str_radix(digits, 16).expect("fixture hex")
                })
                .collect();
            assert_eq!(encode(&source, mode, mask, rotation, bit).expect("encode"), expected);
            assert_eq!(decode(&expected, source.len(), mode, mask, rotation).expect("decode Go output"), source);
        }
    }

    #[test]
    fn rejects_noncanonical_lengths_masks_and_padding() {
        assert!(encoded_len(0, 1).is_err());
        assert!(encoded_len(MAX_EXTRACTED + 1, 2).is_err());
        assert!(encoded_len(MAX_EXTRACTED, 1).is_err());
        assert!(encode(b"one", 0, 0xffff, 0, false).is_err());
        assert!(encode(b"one", 1, 0xffff, 17, false).is_err());
        assert!(encode(b"one", 1, 0xff, 0, false).is_err());
        let source = [0x5a; 13];
        let mut encoded = encode(&source, 1, 0xffff, 0, false).expect("valid payload");
        encoded[0] ^= 0x80;
        assert!(decode(&encoded, source.len(), 1, 0xffff, 0).is_err());
        assert!(decode(&encoded[..encoded.len() - 1], source.len(), 1, 0xffff, 0).is_err());
    }

    #[test]
    fn all_modes_and_valid_rotations_preserve_boundary_payloads() {
        for mode in 1..=4 {
            let mask = ((1u64 << ((u32::from(mode) + 3) * 4)) - 1) as u32;
            for rotation in (0..=15).chain((16..=240).step_by(16)) {
                for length in [1, 3, 4, 5, 6, 7, 8, 13, 32764] {
                    let payload: Vec<u8> = (0..length).map(|index| (index % 251) as u8).collect();
                    for bit in [false, true] {
                        let encoded = encode(&payload, mode, mask, rotation, bit).expect("encode boundary");
                        assert_eq!(decode(&encoded, length, mode, mask, rotation).expect("decode boundary"), payload);
                    }
                }
            }
        }
    }
}
