use crate::types::{OracleRng, PacketMutation, TlsMarkerInfo};
use crate::util::{copy_name_seeded, fill_random_tls_host_like_c, read_u16, read_u24, write_u16, write_u24};

pub(crate) const TLS_RECORD_HEADER_LEN: usize = 5;

fn find_tls_ext_offset(kind: u16, data: &[u8], mut skip: usize) -> Option<usize> {
    if data.len() <= skip + 2 {
        return None;
    }
    let ext_len = read_u16(data, skip)?;
    skip += 2;
    let mut size = data.len();
    if ext_len < size.saturating_sub(skip) {
        size = ext_len + skip;
    }
    while skip + 4 < size {
        let curr = read_u16(data, skip)? as u16;
        if curr == kind {
            return Some(skip);
        }
        skip += read_u16(data, skip + 2)? + 4;
    }
    None
}

fn find_tls_ext_len_offset_in_handshake(data: &[u8]) -> Option<usize> {
    let mut offset = 1 + 3 + 2 + 32;
    let sid_len = *data.get(offset)? as usize;
    offset += 1 + sid_len;
    let cipher_len = read_u16(data, offset)?;
    offset += 2 + cipher_len;
    let compression_len = *data.get(offset)? as usize;
    offset += 1 + compression_len;
    if offset + 1 >= data.len() {
        return None;
    }
    Some(offset)
}

fn find_tls_ext_len_offset(data: &[u8]) -> Option<usize> {
    Some(find_tls_ext_len_offset_in_handshake(data.get(TLS_RECORD_HEADER_LEN..)?)? + TLS_RECORD_HEADER_LEN)
}

fn find_ext_block(data: &[u8]) -> Option<usize> {
    find_tls_ext_len_offset(data)
}

fn adjust_tls_lengths(buffer: &mut [u8], ext_len_start: usize, delta: isize) -> bool {
    let Some(record_len) = read_u16(buffer, 3).map(|value| value as isize) else {
        return false;
    };
    let Some(handshake_len) = read_u24(buffer, 6).map(|value| value as isize) else {
        return false;
    };
    let Some(ext_len) = read_u16(buffer, ext_len_start).map(|value| value as isize) else {
        return false;
    };

    let record_len = record_len + delta;
    let handshake_len = handshake_len + delta;
    let ext_len = ext_len + delta;
    if record_len < 0 || handshake_len < 0 || ext_len < 0 {
        return false;
    }
    write_u16(buffer, 3, record_len as usize)
        && write_u24(buffer, 6, handshake_len as usize)
        && write_u16(buffer, ext_len_start, ext_len as usize)
}

pub(crate) fn tls_client_hello_marker_info_in_handshake(buffer: &[u8]) -> Option<TlsMarkerInfo> {
    let parsed = crate::tls_nom::parse_client_hello_handshake(buffer)?;
    crate::tls_nom::to_marker_info(&parsed, buffer.len())
}

fn tls_client_hello_marker_info_in_record(buffer: &[u8]) -> Option<TlsMarkerInfo> {
    if !is_tls_client_hello(buffer) {
        return None;
    }
    let parsed = crate::tls_nom::parse_client_hello_record(buffer)?;
    crate::tls_nom::to_marker_info(&parsed, buffer.len())
}

fn merge_tls_records(buffer: &mut [u8], n: usize) -> usize {
    if n < 5 {
        return 0;
    }
    let Some(mut record_size) = read_u16(buffer, 3) else {
        return 0;
    };
    let mut full_size = 0usize;
    let mut removed = 0usize;

    loop {
        full_size += record_size;
        if 5 + full_size > n.saturating_sub(5) || buffer[5 + full_size] != buffer[0] {
            break;
        }
        let Some(next_record_size) = read_u16(buffer, 5 + full_size + 3) else {
            break;
        };
        if full_size + 10 + next_record_size > n {
            break;
        }
        buffer.copy_within(10 + full_size..n, 5 + full_size);
        removed += 5;
        record_size = next_record_size;
    }

    let _ = write_u16(buffer, 3, full_size);
    let _ = write_u16(buffer, 7, full_size.saturating_sub(4));
    removed
}

fn remove_ks_group(buffer: &mut [u8], n: usize, skip: usize, group: u16) -> usize {
    let Some(ks_offs) = find_tls_ext_offset(0x0033, &buffer[..n], skip) else {
        return 0;
    };
    if ks_offs + 6 >= n {
        return 0;
    }
    let Some(ks_size) = read_u16(buffer, ks_offs + 2) else {
        return 0;
    };
    if ks_offs + 4 + ks_size > n {
        return 0;
    }
    let ks_end = ks_offs + 4 + ks_size;
    let mut group_offs = ks_offs + 6;
    while group_offs + 4 < ks_end {
        let Some(group_size) = read_u16(buffer, group_offs + 2) else {
            return 0;
        };
        let group_end = group_offs + 4 + group_size;
        if group_end > ks_end || group_end > n {
            return 0;
        }
        let Some(group_type) = read_u16(buffer, group_offs).map(|value| value as u16) else {
            return 0;
        };
        if group_type == group {
            buffer.copy_within(group_end..n, group_offs);
            let new_size = ks_size.saturating_sub(4 + group_size);
            let _ = write_u16(buffer, ks_offs + 2, new_size);
            let _ = write_u16(buffer, ks_offs + 4, new_size.saturating_sub(2));
            return 4 + group_size;
        }
        group_offs += 4 + group_size;
    }
    0
}

fn remove_tls_ext(buffer: &mut [u8], n: usize, skip: usize, kind: u16) -> usize {
    let Some(ext_offs) = find_tls_ext_offset(kind, &buffer[..n], skip) else {
        return 0;
    };
    let Some(ext_size) = read_u16(buffer, ext_offs + 2) else {
        return 0;
    };
    let ext_end = ext_offs + 4 + ext_size;
    if ext_end > n {
        return 0;
    }
    buffer.copy_within(ext_end..n, ext_offs);
    ext_size + 4
}

fn resize_ech_ext(buffer: &mut [u8], n: usize, skip: usize, mut inc: isize) -> isize {
    let Some(ech_offs) = find_tls_ext_offset(0xfe0d, &buffer[..n], skip) else {
        return 0;
    };
    let Some(ech_size) = read_u16(buffer, ech_offs + 2).map(|value| value as isize) else {
        return 0;
    };
    let ech_end = ech_offs as isize + 4 + ech_size;
    if ech_size < 12 || ech_end as usize > n {
        return 0;
    }
    let Some(enc_size) = read_u16(buffer, ech_offs + 10).map(|value| value as isize) else {
        return 0;
    };
    let payload_offs = ech_offs as isize + 12 + enc_size;
    let payload_size = ech_size - (8 + enc_size + 2);
    if payload_offs + 2 > n as isize {
        return 0;
    }
    if payload_size < -inc {
        inc = -payload_size;
    }
    if ech_size + inc < 0 || payload_size + inc < 0 {
        return 0;
    }
    let dest = ech_end + inc;
    let tail_len = n.saturating_sub(ech_end as usize);
    if dest < 0 || dest as usize > buffer.len().saturating_sub(tail_len) {
        return 0;
    }
    let _ = write_u16(buffer, ech_offs + 2, (ech_size + inc) as usize);
    let _ = write_u16(buffer, payload_offs as usize, (payload_size + inc) as usize);
    buffer.copy_within(ech_end as usize..n, dest as usize);
    inc
}

fn resize_sni(buffer: &mut [u8], n: usize, sni_offs: usize, sni_size: usize, new_size: usize) -> bool {
    let delta = new_size as isize - (sni_size as isize - 5);
    let sni_end = sni_offs + 4 + sni_size;
    if sni_end > n {
        return false;
    }
    let dest = sni_end as isize + delta;
    let tail_len = n.saturating_sub(sni_end);
    if dest < 0 || dest as usize > buffer.len().saturating_sub(tail_len) {
        return false;
    }
    if !write_u16(buffer, sni_offs + 2, new_size + 5)
        || !write_u16(buffer, sni_offs + 4, new_size + 3)
        || !write_u16(buffer, sni_offs + 7, new_size)
    {
        return false;
    }
    buffer.copy_within(sni_end..n, dest as usize);
    true
}

pub fn is_tls_client_hello(buffer: &[u8]) -> bool {
    buffer.len() > 5 && read_u16(buffer, 0) == Some(0x1603) && buffer[5] == 0x01
}

pub fn is_tls_server_hello(buffer: &[u8]) -> bool {
    buffer.len() > 5 && read_u16(buffer, 0) == Some(0x1603) && buffer[5] == 0x02
}

pub fn parse_tls(buffer: &[u8]) -> Option<&[u8]> {
    let markers = tls_client_hello_marker_info_in_record(buffer)?;
    Some(&buffer[markers.host_start..markers.host_end])
}

pub fn tls_marker_info(buffer: &[u8]) -> Option<TlsMarkerInfo> {
    tls_client_hello_marker_info_in_record(buffer)
}

pub fn tls_session_id_mismatch(req: &[u8], resp: &[u8]) -> bool {
    if req.len() < 75 || resp.len() < 75 {
        return false;
    }
    if !is_tls_client_hello(req) || read_u16(resp, 0) != Some(0x1603) {
        return false;
    }
    let sid_len = req[43] as usize;
    let skip = 44 + sid_len + 3;
    if find_tls_ext_offset(0x002b, resp, skip).is_none() {
        return false;
    }
    if req[43] != resp[43] {
        return true;
    }
    req.get(44..44 + sid_len) != resp.get(44..44 + sid_len)
}

pub fn part_tls_like_c(input: &[u8], pos: isize) -> PacketMutation {
    let n = input.len();
    if n < 3 || pos < 0 || pos as usize + 5 > n {
        return PacketMutation { rc: 0, bytes: input.to_vec() };
    }
    let mut output = vec![0; n + 5];
    output[..n].copy_from_slice(input);

    let Some(record_size) = read_u16(&output, 3) else {
        return PacketMutation { rc: 0, bytes: input.to_vec() };
    };
    if record_size < pos as usize {
        return PacketMutation { rc: n as isize, bytes: input.to_vec() };
    }

    let pos = pos as usize;
    output.copy_within(5 + pos..n, 10 + pos);
    output[5 + pos..5 + pos + 3].copy_from_slice(&input[..3]);
    let _ = write_u16(&mut output, 3, pos);
    let _ = write_u16(&mut output, 8 + pos, record_size.saturating_sub(pos));

    PacketMutation { rc: 5, bytes: output }
}

pub fn randomize_tls_seeded_like_c(input: &[u8], seed: u32) -> PacketMutation {
    let mut output = input.to_vec();
    if output.len() < 44 {
        return PacketMutation { rc: 0, bytes: output };
    }
    let sid_len = output[43] as usize;
    if output.len() < 44 + sid_len + 2 {
        return PacketMutation { rc: 0, bytes: output };
    }
    let mut rng = OracleRng::seeded(seed);
    for byte in &mut output[11..43] {
        *byte = rng.next_u8();
    }
    for byte in &mut output[44..44 + sid_len] {
        *byte = rng.next_u8();
    }

    let Some(parsed) = crate::tls_nom::parse_client_hello_record(&output) else {
        return PacketMutation { rc: 0, bytes: output };
    };
    let Some(ks_offs) = crate::tls_nom::find_extension_offset(&parsed, 0x0033) else {
        return PacketMutation { rc: 0, bytes: output };
    };
    if ks_offs + 6 >= output.len() {
        return PacketMutation { rc: 0, bytes: output };
    }
    let Some(ks_size) = read_u16(&output, ks_offs + 2) else {
        return PacketMutation { rc: 0, bytes: output };
    };
    if ks_offs + 4 + ks_size > output.len() {
        return PacketMutation { rc: 0, bytes: output };
    }
    let ks_end = ks_offs + 4 + ks_size;
    let mut group_offs = ks_offs + 6;
    while group_offs + 4 < ks_end {
        let Some(group_size) = read_u16(&output, group_offs + 2) else {
            return PacketMutation { rc: 0, bytes: output };
        };
        let group_end = group_offs + 4 + group_size;
        if group_end > ks_end || group_end > output.len() {
            return PacketMutation { rc: 0, bytes: output };
        }
        for byte in &mut output[group_offs + 4..group_end] {
            *byte = rng.next_u8();
        }
        group_offs += 4 + group_size;
    }

    PacketMutation { rc: 0, bytes: output }
}

pub fn randomize_tls_sni_seeded_like_c(input: &[u8], seed: u32) -> PacketMutation {
    let Some(markers) = tls_marker_info(input) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    let mut output = input.to_vec();
    let mut rng = OracleRng::seeded(seed);
    fill_random_tls_host_like_c(&mut output[markers.host_start..markers.host_end], &mut rng);
    PacketMutation { rc: 0, bytes: output }
}

pub fn duplicate_tls_session_id_like_c(fake_input: &[u8], original_input: &[u8]) -> PacketMutation {
    let mut output = fake_input.to_vec();
    if !is_tls_client_hello(fake_input)
        || !is_tls_client_hello(original_input)
        || output.len() < 44
        || original_input.len() < 44
    {
        return PacketMutation { rc: -1, bytes: output };
    }
    let sid_len = output[43] as usize;
    if output.len() < 44 + sid_len || original_input[43] as usize != sid_len || original_input.len() < 44 + sid_len {
        return PacketMutation { rc: -1, bytes: output };
    }
    output[44..44 + sid_len].copy_from_slice(&original_input[44..44 + sid_len]);
    PacketMutation { rc: 0, bytes: output }
}

pub fn tune_tls_padding_size_like_c(input: &[u8], target_size: usize) -> PacketMutation {
    if target_size == input.len() {
        return PacketMutation { rc: 0, bytes: input.to_vec() };
    }
    let Some(parsed) = crate::tls_nom::parse_client_hello_record(input) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    let ext_len_start = parsed.ext_len_offset;
    let mut output = input.to_vec();
    let original_len = output.len();
    let pad_offs = crate::tls_nom::find_extension_offset(&parsed, 0x0015);

    match target_size.cmp(&original_len) {
        std::cmp::Ordering::Equal => PacketMutation { rc: 0, bytes: output },
        std::cmp::Ordering::Greater => {
            output.resize(target_size, 0);
            let grow = target_size - original_len;
            if let Some(pad_offs) = pad_offs {
                if pad_offs + 4 <= output.len() {
                    if let Some(pad_len) = read_u16(input, pad_offs + 2) {
                        let _ = write_u16(&mut output, pad_offs + 2, pad_len.saturating_add(grow));
                    }
                }
            } else if grow >= 4 {
                let pad_offs = original_len;
                let _ = write_u16(&mut output, pad_offs, 0x0015);
                let _ = write_u16(&mut output, pad_offs + 2, grow - 4);
            }
            if !adjust_tls_lengths(&mut output, ext_len_start, grow as isize) {
                return PacketMutation { rc: -1, bytes: input.to_vec() };
            }
            PacketMutation { rc: 0, bytes: output }
        }
        std::cmp::Ordering::Less => {
            let shrink = original_len - target_size;
            output.truncate(target_size);
            if let Some(pad_offs) = pad_offs {
                if pad_offs + 4 <= output.len() {
                    if let Some(pad_len) = read_u16(input, pad_offs + 2) {
                        let _ = write_u16(&mut output, pad_offs + 2, pad_len.saturating_sub(shrink));
                    }
                }
            }
            if !adjust_tls_lengths(&mut output, ext_len_start, -(shrink as isize)) {
                return PacketMutation { rc: -1, bytes: input.to_vec() };
            }
            PacketMutation { rc: 0, bytes: output }
        }
    }
}

pub fn padencap_tls_like_c(input: &[u8], payload_len: usize) -> PacketMutation {
    let Some(parsed) = crate::tls_nom::parse_client_hello_record(input) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    let ext_len_start = parsed.ext_len_offset;
    let mut output = input.to_vec();
    let pad_len_offs = if let Some(pad_offs) = crate::tls_nom::find_extension_offset(&parsed, 0x0015) {
        pad_offs + 2
    } else {
        let pad_offs = output.len();
        output.extend_from_slice(&[0x00, 0x15, 0x00, 0x00]);
        if !adjust_tls_lengths(&mut output, ext_len_start, 4) {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
        pad_offs + 2
    };
    let Some(pad_len) = read_u16(&output, pad_len_offs) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    if !write_u16(&mut output, pad_len_offs, pad_len + payload_len)
        || !adjust_tls_lengths(&mut output, ext_len_start, payload_len as isize)
    {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }
    PacketMutation { rc: 0, bytes: output }
}

pub fn change_tls_sni_seeded_like_c(input: &[u8], host: &[u8], capacity: usize, seed: u32) -> PacketMutation {
    if capacity < input.len() || host.len() > u16::MAX as usize {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let mut output = vec![0; capacity];
    output[..input.len()].copy_from_slice(input);
    let n = input.len();
    let mut avail = merge_tls_records(&mut output, n) as isize + (capacity - n) as isize;
    let Some(mut record_size) = read_u16(&output, 3).map(|value| value as isize) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    record_size += avail;

    let Some(parsed) = crate::tls_nom::parse_client_hello_record(&output[..n]) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    let skip = parsed.ext_len_offset;
    let Some(mut sni_offs) = crate::tls_nom::find_extension_offset(&parsed, 0x0000) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    let Some(sni_size) = read_u16(&output, sni_offs + 2) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    if sni_offs + 4 + sni_size > n {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let mut diff = host.len() as isize - (sni_size as isize - 5);
    avail -= diff;
    if diff < 0 && avail > 0 {
        if !resize_sni(&mut output, n, sni_offs, sni_size, host.len()) {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
        diff = 0;
    }
    if avail != 0 {
        avail -= resize_ech_ext(&mut output, n, skip, avail);
    }
    if avail < -50 {
        avail += remove_ks_group(&mut output, n, skip, 0x11ec) as isize;
    }
    for kind in [0x0015u16, 0x0031, 0x0010, 0x001c, 0x0023, 0x0005, 0x0022, 0x0012, 0x001b] {
        if avail == 0 || avail >= 4 {
            break;
        }
        avail += remove_tls_ext(&mut output, n, skip, kind) as isize;
    }
    if avail != 0 && avail < 4 {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let Some(new_sni_offs) = find_tls_ext_offset(0x0000, &output[..n], skip) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    sni_offs = new_sni_offs;
    if diff != 0 {
        let curr_n = capacity as isize - avail - diff;
        if curr_n < 0 || curr_n > capacity as isize {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
        if !resize_sni(&mut output, curr_n as usize, sni_offs, sni_size, host.len()) {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
    }
    if sni_offs + 9 + host.len() > capacity {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let mut rng = OracleRng::seeded(seed);
    copy_name_seeded(&mut output[sni_offs + 9..sni_offs + 9 + host.len()], host, &mut rng);

    if avail > 0 {
        avail -= resize_ech_ext(&mut output, n, skip, avail);
    }
    if avail >= 4 {
        let record_end = 5 + record_size;
        let pad_offs = record_end - avail;
        if record_end > capacity as isize || pad_offs < 0 || pad_offs + avail > capacity as isize {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
        let pad_offs = pad_offs as usize;
        let avail = avail as usize;
        let _ = write_u16(&mut output, pad_offs, 0x0015);
        let _ = write_u16(&mut output, pad_offs + 2, avail.saturating_sub(4));
        output[pad_offs + 4..pad_offs + avail].fill(0);
    }

    if record_size < 4
        || !write_u16(&mut output, 3, record_size as usize)
        || !write_u16(&mut output, 7, (record_size - 4) as usize)
        || !write_u16(&mut output, skip, (5 + record_size - skip as isize - 2).max(0) as usize)
    {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let out_len = (5 + record_size) as usize;
    if out_len > output.len() {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }
    PacketMutation { rc: 0, bytes: output[..out_len].to_vec() }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::DEFAULT_FAKE_TLS;

    #[test]
    fn parse_tls_extracts_default_fake_sni() {
        assert!(is_tls_client_hello(DEFAULT_FAKE_TLS));
        assert_eq!(parse_tls(DEFAULT_FAKE_TLS), Some(&b"www.wikipedia.org"[..]));
    }

    #[test]
    fn tls_marker_info_tracks_sni_and_extensions_offsets() {
        let markers = tls_marker_info(DEFAULT_FAKE_TLS).expect("parse tls markers");

        assert_eq!(&DEFAULT_FAKE_TLS[markers.host_start..markers.host_end], b"www.wikipedia.org");
        assert_eq!(markers.host_start, markers.sni_ext_start + 5);
        assert!(markers.ext_len_start < markers.sni_ext_start);
    }

    #[test]
    fn tls_marker_info_rejects_truncated_sni_payload() {
        let markers = tls_marker_info(DEFAULT_FAKE_TLS).expect("original tls markers");
        let mut truncated = DEFAULT_FAKE_TLS.to_vec();
        truncated.truncate(markers.host_start + 4);

        assert!(tls_marker_info(&truncated).is_none());
    }

    #[test]
    fn randomize_tls_sni_preserves_valid_host_length() {
        let original = parse_tls(DEFAULT_FAKE_TLS).expect("original tls sni").to_vec();
        let mutation = randomize_tls_sni_seeded_like_c(DEFAULT_FAKE_TLS, 7);
        let randomized = parse_tls(&mutation.bytes).expect("randomized tls sni").to_vec();

        assert_eq!(mutation.rc, 0);
        assert_eq!(randomized.len(), original.len());
        assert_ne!(randomized, original);
        assert!(randomized.iter().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || *byte == b'.'));
    }

    #[test]
    fn duplicate_tls_session_id_uses_original_when_compatible() {
        let source = DEFAULT_FAKE_TLS.to_vec();
        let mut fake = DEFAULT_FAKE_TLS.to_vec();
        let sid_len = fake[43] as usize;
        fake[44..44 + sid_len].fill(b'Z');

        let mutation = duplicate_tls_session_id_like_c(&fake, &source);

        assert_eq!(mutation.rc, 0);
        assert_eq!(&mutation.bytes[44..44 + mutation.bytes[43] as usize], &source[44..44 + source[43] as usize]);
    }

    #[test]
    fn duplicate_tls_session_id_rejects_incompatible_lengths() {
        let mut source = DEFAULT_FAKE_TLS.to_vec();
        source[43] = source[43].saturating_sub(1);

        let mutation = duplicate_tls_session_id_like_c(DEFAULT_FAKE_TLS, &source);

        assert_eq!(mutation.rc, -1);
        assert_eq!(mutation.bytes, DEFAULT_FAKE_TLS);
    }

    #[test]
    fn tune_tls_padding_size_can_grow_and_shrink_default_fake() {
        let grown = tune_tls_padding_size_like_c(DEFAULT_FAKE_TLS, DEFAULT_FAKE_TLS.len() + 12);
        let shrunk = tune_tls_padding_size_like_c(DEFAULT_FAKE_TLS, DEFAULT_FAKE_TLS.len() - 12);

        assert_eq!(grown.rc, 0);
        assert_eq!(grown.bytes.len(), DEFAULT_FAKE_TLS.len() + 12);
        assert_eq!(parse_tls(&grown.bytes), Some(&b"www.wikipedia.org"[..]));

        assert_eq!(shrunk.rc, 0);
        assert_eq!(shrunk.bytes.len(), DEFAULT_FAKE_TLS.len() - 12);
        assert_eq!(parse_tls(&shrunk.bytes), Some(&b"www.wikipedia.org"[..]));
    }

    #[test]
    fn padencap_tls_updates_padding_and_lengths() {
        let mutation = padencap_tls_like_c(DEFAULT_FAKE_TLS, 24);
        let ext_len_start = find_tls_ext_len_offset(&mutation.bytes).expect("ext len offset");
        let pad_offs = find_tls_ext_offset(0x0015, &mutation.bytes, ext_len_start).expect("padding ext");
        let pad_len = read_u16(&mutation.bytes, pad_offs + 2).expect("pad len");

        assert_eq!(mutation.rc, 0);
        assert_eq!(mutation.bytes.len(), DEFAULT_FAKE_TLS.len());
        assert_eq!(read_u16(DEFAULT_FAKE_TLS, 3).unwrap() + 24, read_u16(&mutation.bytes, 3).unwrap());
        assert_eq!(read_u16(DEFAULT_FAKE_TLS, pad_offs + 2).unwrap() + 24, pad_len);
        assert_eq!(parse_tls(&mutation.bytes), Some(&b"www.wikipedia.org"[..]));
    }

    proptest::proptest! {
        #[test]
        fn parse_tls_never_panics(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..1024)) {
            let _ = is_tls_client_hello(&data);
            let _ = is_tls_server_hello(&data);
            let _ = parse_tls(&data);
            let _ = tls_session_id_mismatch(&data, &data);
        }
    }

    /// Use `tls-parser` as an independent oracle to extract the SNI hostname
    /// from a TLS record. Returns `None` when the record is malformed or does
    /// not contain an SNI extension.
    fn extract_sni_via_tls_parser(data: &[u8]) -> Option<Vec<u8>> {
        use tls_parser::{parse_tls_plaintext, TlsMessage, TlsMessageHandshake};

        let (_, record) = parse_tls_plaintext(data).ok()?;
        for msg in &record.msg {
            if let TlsMessage::Handshake(TlsMessageHandshake::ClientHello(ch)) = msg {
                if let Some(ext_data) = ch.ext {
                    let (_, exts) = tls_parser::parse_tls_client_hello_extensions(ext_data).ok()?;
                    for ext in &exts {
                        if let tls_parser::TlsExtension::SNI(sni_list) = ext {
                            for (name_type, name) in sni_list {
                                if *name_type == tls_parser::SNIType::HostName {
                                    return Some(name.to_vec());
                                }
                            }
                        }
                    }
                }
            }
        }
        None
    }

    /// Strategy that produces structurally valid TLS ClientHello records with
    /// random but well-formed fields, exercising the parser on inputs that
    /// look like real TLS traffic with varied field sizes.
    fn arb_client_hello() -> impl proptest::strategy::Strategy<Value = Vec<u8>> {
        use proptest::prelude::*;
        use proptest::collection::vec as arb_vec;

        // Hostname: 3..=63 ASCII lowercase letters/dots, must contain at least
        // one dot, must not start/end with dot, no consecutive dots.
        let hostname_strategy = arb_vec(
            prop_oneof![
                8 => b'a'..=b'z',
                1 => Just(b'.'),
            ],
            3..=63usize,
        )
        .prop_filter("hostname must contain at least one dot", |h| h.contains(&b'.'))
        .prop_filter("hostname must not start or end with dot", |h| {
            h[0] != b'.' && h[h.len() - 1] != b'.'
        })
        .prop_filter("hostname must not have consecutive dots", |h| {
            !h.windows(2).any(|w| w[0] == b'.' && w[1] == b'.')
        });

        (
            arb_vec(any::<u8>(), 32..=32usize),   // random (32 bytes)
            0..=32u8,                               // session_id length
            arb_vec(any::<u8>(), 32..=32usize),    // session_id bytes pool
            1..=15u16,                              // cipher_suites pair count
            arb_vec(any::<u8>(), 30..=30usize),    // cipher_suites bytes pool
            1..=3u8,                                // compression_methods length
            arb_vec(any::<u8>(), 3..=3usize),      // compression_methods bytes pool
            hostname_strategy,
            0..=50usize,                            // padding extension data length
        )
            .prop_map(
                |(random, sid_len, sid_pool, cs_pairs, cs_pool, comp_len, comp_pool, hostname, pad_len)| {
                    let mut buf = Vec::with_capacity(512);

                    // TLS record header (5 bytes) -- placeholder lengths
                    buf.extend_from_slice(&[0x16, 0x03, 0x01, 0x00, 0x00]);

                    // Handshake header
                    buf.push(0x01); // ClientHello type
                    buf.extend_from_slice(&[0x00, 0x00, 0x00]); // placeholder length

                    // Client version: TLS 1.2
                    buf.extend_from_slice(&[0x03, 0x03]);

                    // Random (32 bytes)
                    buf.extend_from_slice(&random);

                    // Session ID
                    let sid_actual_len = sid_len as usize;
                    buf.push(sid_len);
                    buf.extend_from_slice(&sid_pool[..sid_actual_len]);

                    // Cipher suites
                    let cs_byte_len = (cs_pairs as usize) * 2;
                    buf.extend_from_slice(&(cs_byte_len as u16).to_be_bytes());
                    buf.extend_from_slice(&cs_pool[..cs_byte_len]);

                    // Compression methods (first byte is always 0x00 = null)
                    let comp_actual_len = comp_len as usize;
                    buf.push(comp_len);
                    buf.push(0x00);
                    if comp_actual_len > 1 {
                        buf.extend_from_slice(&comp_pool[..comp_actual_len - 1]);
                    }

                    // Extensions block
                    let ext_block_start = buf.len();
                    buf.extend_from_slice(&[0x00, 0x00]); // placeholder extensions length

                    // SNI extension (type 0x0000)
                    let host_len = hostname.len() as u16;
                    let sni_list_len: u16 = 1 + 2 + host_len;
                    let sni_ext_data_len: u16 = 2 + sni_list_len;
                    buf.extend_from_slice(&0x0000u16.to_be_bytes());
                    buf.extend_from_slice(&sni_ext_data_len.to_be_bytes());
                    buf.extend_from_slice(&sni_list_len.to_be_bytes());
                    buf.push(0x00); // host_name type
                    buf.extend_from_slice(&host_len.to_be_bytes());
                    buf.extend_from_slice(&hostname);

                    // Optional padding extension (type 0x0015)
                    if pad_len > 0 {
                        buf.extend_from_slice(&0x0015u16.to_be_bytes());
                        buf.extend_from_slice(&(pad_len as u16).to_be_bytes());
                        buf.resize(buf.len() + pad_len, 0x00);
                    }

                    // Patch extensions length
                    let ext_data_len = (buf.len() - ext_block_start - 2) as u16;
                    buf[ext_block_start..ext_block_start + 2]
                        .copy_from_slice(&ext_data_len.to_be_bytes());

                    // Patch handshake length (bytes 6-8)
                    let hs_body_len = (buf.len() - 9) as u32;
                    buf[6] = (hs_body_len >> 16) as u8;
                    buf[7] = (hs_body_len >> 8) as u8;
                    buf[8] = hs_body_len as u8;

                    // Patch record length (bytes 3-4)
                    let record_payload_len = (buf.len() - 5) as u16;
                    buf[3..5].copy_from_slice(&record_payload_len.to_be_bytes());

                    buf
                },
            )
    }

    proptest::proptest! {
        #[test]
        fn structurally_valid_client_hello_cross_validation(data in arb_client_hello()) {
            // 1. Must be recognized as a ClientHello
            proptest::prop_assert!(is_tls_client_hello(&data));

            // 2. Manual parser must extract SNI
            let manual_sni = parse_tls(&data);
            proptest::prop_assert!(manual_sni.is_some(), "manual parser failed on valid ClientHello");

            // 3. tls-parser must also extract SNI (record is well-formed)
            let oracle_sni = extract_sni_via_tls_parser(&data);
            proptest::prop_assert!(oracle_sni.is_some(), "tls-parser failed on valid ClientHello");
            let oracle_bytes = oracle_sni.unwrap();

            // 4. Both must agree
            let manual_bytes = manual_sni.unwrap();
            proptest::prop_assert_eq!(manual_bytes, oracle_bytes.as_slice());

            // 5. TlsMarkerInfo structural invariants
            let info = tls_marker_info(&data).unwrap();
            proptest::prop_assert!(info.ext_len_start < info.sni_ext_start);
            proptest::prop_assert!(info.sni_ext_start < info.host_start);
            proptest::prop_assert!(info.host_start < info.host_end);
            proptest::prop_assert!(info.host_end <= data.len());
            proptest::prop_assert_eq!(&data[info.host_start..info.host_end], manual_bytes);
        }
    }
}
