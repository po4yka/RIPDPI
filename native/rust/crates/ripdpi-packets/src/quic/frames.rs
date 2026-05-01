use crate::types::QuicCryptoFrameInfo;

use super::QUIC_MAX_CRYPTO_LEN;

pub(super) fn read_quic_varint(data: &[u8], offset: usize) -> Option<(u64, usize)> {
    let first = *data.get(offset)?;
    let len = 1usize << ((first >> 6) as usize);
    let bytes = data.get(offset..offset + len)?;
    let mut value = (bytes[0] & 0x3f) as u64;
    for byte in &bytes[1..] {
        value = (value << 8) | u64::from(*byte);
    }
    Some((value, len))
}

pub(super) fn encode_quic_varint(value: u64) -> Vec<u8> {
    match value {
        0..=63 => vec![value as u8],
        64..=16_383 => ((0x4000 | value as u16).to_be_bytes()).to_vec(),
        16_384..=1_073_741_823 => ((0x8000_0000 | value as u32).to_be_bytes()).to_vec(),
        _ => {
            let mut bytes = value.to_be_bytes();
            bytes[0] |= 0xc0;
            bytes.to_vec()
        }
    }
}

pub(super) fn append_quic_crypto_frame(out: &mut Vec<u8>, offset: u64, data: &[u8]) {
    out.push(0x06);
    out.extend_from_slice(&encode_quic_varint(offset));
    out.extend_from_slice(&encode_quic_varint(data.len() as u64));
    out.extend_from_slice(data);
}

pub(super) fn append_segmented_quic_crypto_frames(
    out: &mut Vec<u8>,
    client_hello: &[u8],
    split_offsets: &[usize],
) -> Option<()> {
    let mut cursor = 0usize;
    let mut offsets =
        split_offsets.iter().copied().filter(|offset| *offset > 0 && *offset < client_hello.len()).collect::<Vec<_>>();
    offsets.sort_unstable();
    offsets.dedup();

    for boundary in offsets.into_iter().chain(std::iter::once(client_hello.len())) {
        let chunk = client_hello.get(cursor..boundary)?;
        if chunk.is_empty() {
            return None;
        }
        append_quic_crypto_frame(out, cursor as u64, chunk);
        cursor = boundary;
    }
    Some(())
}

pub(super) fn collect_quic_crypto_frames(payload: &[u8]) -> Option<Vec<QuicCryptoFrameInfo>> {
    let mut frames = Vec::new();
    let mut cursor = 0usize;

    while cursor < payload.len() {
        match payload[cursor] {
            0x00 | 0x01 => {
                cursor += 1;
            }
            0x06 => {
                cursor += 1;
                let (offset, offset_len) = read_quic_varint(payload, cursor)?;
                cursor += offset_len;
                let (frame_len, frame_len_len) = read_quic_varint(payload, cursor)?;
                cursor += frame_len_len;
                let offset: usize = offset.try_into().ok()?;
                let frame_len: usize = frame_len.try_into().ok()?;
                let end = cursor.checked_add(frame_len)?;
                if end > payload.len() {
                    return None;
                }
                let piece_end = offset.checked_add(frame_len)?;
                if piece_end > QUIC_MAX_CRYPTO_LEN {
                    return None;
                }
                frames.push(QuicCryptoFrameInfo { crypto_offset: offset, data_offset: cursor, data_len: frame_len });
                cursor = end;
            }
            _ => return None,
        }
    }

    (!frames.is_empty()).then_some(frames)
}

pub(super) fn defrag_quic_crypto_frames(payload: &[u8]) -> Option<(Vec<u8>, bool)> {
    let frames = collect_quic_crypto_frames(payload)?;
    let max_end = frames.iter().map(|frame| frame.crypto_offset + frame.data_len).max().unwrap_or(0);

    // Fast path: single contiguous frame at offset 0 (the common case).
    if frames.len() == 1 && frames[0].crypto_offset == 0 {
        let frame = frames[0];
        return Some((payload[frame.data_offset..frame.data_offset + frame.data_len].to_vec(), true));
    }

    // General case: reassemble from multiple/scattered frames.
    let mut data = vec![0u8; max_end];
    let mut covered = vec![false; max_end];
    for frame in frames {
        let end = frame.crypto_offset + frame.data_len;
        data[frame.crypto_offset..end].copy_from_slice(&payload[frame.data_offset..frame.data_offset + frame.data_len]);
        covered[frame.crypto_offset..end].fill(true);
    }

    Some((data, covered.iter().all(|c| *c)))
}
