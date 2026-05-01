use std::io;

use bytes::{BufMut, BytesMut};
use tokio::io::{AsyncRead, AsyncReadExt};

use crate::error::{HysteriaError, Result};

pub(crate) async fn read_varint<R: AsyncRead + Unpin>(reader: &mut R) -> io::Result<u64> {
    let first = reader.read_u8().await?;
    let tag = first >> 6;
    let value = match tag {
        0 => u64::from(first & 0x3F),
        1 => {
            let second = reader.read_u8().await?;
            u64::from(u16::from_be_bytes([first, second]) & 0x3FFF)
        }
        2 => {
            let mut bytes = [0u8; 4];
            bytes[0] = first;
            reader.read_exact(&mut bytes[1..]).await?;
            u64::from(u32::from_be_bytes(bytes) & 0x3FFF_FFFF)
        }
        _ => {
            let mut bytes = [0u8; 8];
            bytes[0] = first;
            reader.read_exact(&mut bytes[1..]).await?;
            u64::from_be_bytes(bytes) & 0x3FFF_FFFF_FFFF_FFFF
        }
    };
    Ok(value)
}

pub(crate) fn encode_varint(value: u64) -> Vec<u8> {
    let mut buffer = BytesMut::with_capacity(8);
    put_varint(value, &mut buffer);
    buffer.to_vec()
}

pub(crate) fn decode_varint(input: &[u8]) -> Result<(u64, usize)> {
    let Some(first) = input.first().copied() else {
        return Err(HysteriaError::InvalidDatagram("missing Hysteria varint".to_string()));
    };
    let tag = first >> 6;
    let (len, mask): (usize, u64) = match tag {
        0 => (1, 0x3F),
        1 => (2, 0x3FFF),
        2 => (4, 0x3FFF_FFFF),
        _ => (8, 0x3FFF_FFFF_FFFF_FFFF),
    };
    if input.len() < len {
        return Err(HysteriaError::InvalidDatagram("truncated Hysteria varint".to_string()));
    }

    let value = match len {
        1 => u64::from(first & mask as u8),
        2 => u64::from(u16::from_be_bytes([input[0], input[1]]) & mask as u16),
        4 => u64::from(u32::from_be_bytes(input[0..4].try_into().expect("slice length")) & mask as u32),
        _ => u64::from_be_bytes(input[0..8].try_into().expect("slice length")) & mask,
    };
    Ok((value, len))
}

pub(crate) fn put_varint(value: u64, buffer: &mut BytesMut) {
    match value {
        0..=63 => buffer.put_u8(value as u8),
        64..=16_383 => buffer.put_u16(((value & 0x3FFF) | 0x4000) as u16),
        16_384..=1_073_741_823 => buffer.put_u32(((value & 0x3FFF_FFFF) | 0x8000_0000) as u32),
        _ => buffer.put_u64((value & 0x3FFF_FFFF_FFFF_FFFF) | 0xC000_0000_0000_0000),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hysteria_varint_roundtrip() {
        for value in [0, 1, 63, 64, 512, 16_383, 16_384, 1_000_000, u32::MAX as u64] {
            let encoded = encode_varint(value);
            let (decoded, used) = decode_varint(&encoded).expect("decode");
            assert_eq!(decoded, value);
            assert_eq!(used, encoded.len());
        }
    }
}
