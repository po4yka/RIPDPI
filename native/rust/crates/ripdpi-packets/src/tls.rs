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
        let parsed = crate::tls_nom::parse_client_hello_record(&mutation.bytes).expect("nom parse");
        let pad_offs = crate::tls_nom::find_extension_offset(&parsed, 0x0015).expect("padding ext");
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

    // ── randomize_tls_seeded_like_c ──────────────────────────────────

    #[test]
    fn randomize_tls_seeded_changes_random_and_key_share() {
        let mutation = randomize_tls_seeded_like_c(DEFAULT_FAKE_TLS, 42);
        assert_eq!(mutation.rc, 0);
        assert_eq!(mutation.bytes.len(), DEFAULT_FAKE_TLS.len());
        // Random field (bytes 11-43) should differ from original
        assert_ne!(&mutation.bytes[11..43], &DEFAULT_FAKE_TLS[11..43]);
        // Session ID should differ (bytes 44..44+sid_len)
        let sid_len = DEFAULT_FAKE_TLS[43] as usize;
        assert_ne!(&mutation.bytes[44..44 + sid_len], &DEFAULT_FAKE_TLS[44..44 + sid_len]);
        // SNI should be preserved
        assert_eq!(parse_tls(&mutation.bytes), Some(&b"www.wikipedia.org"[..]));
    }

    #[test]
    fn randomize_tls_seeded_is_deterministic() {
        let a = randomize_tls_seeded_like_c(DEFAULT_FAKE_TLS, 42);
        let b = randomize_tls_seeded_like_c(DEFAULT_FAKE_TLS, 42);
        assert_eq!(a.bytes, b.bytes);
    }

    #[test]
    fn randomize_tls_seeded_different_seeds_differ() {
        let a = randomize_tls_seeded_like_c(DEFAULT_FAKE_TLS, 1);
        let b = randomize_tls_seeded_like_c(DEFAULT_FAKE_TLS, 2);
        assert_ne!(a.bytes, b.bytes);
    }

    #[test]
    fn randomize_tls_seeded_handles_short_input() {
        let mutation = randomize_tls_seeded_like_c(&[0x16, 0x03, 0x01], 1);
        assert_eq!(mutation.rc, 0);
        assert_eq!(mutation.bytes, &[0x16, 0x03, 0x01]);
    }

    // ── change_tls_sni_seeded_like_c ──────────────────────────────────

    #[test]
    fn change_tls_sni_replaces_hostname() {
        use crate::fake_profiles::{tls_fake_profile_bytes, TlsFakeProfile};
        let input = tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome);
        let new_host = b"test.example.com";
        let capacity = input.len() + 100; // extra room
        let mutation = change_tls_sni_seeded_like_c(input, new_host, capacity, 42);
        assert_eq!(mutation.rc, 0);
        let sni = parse_tls(&mutation.bytes);
        // The SNI should contain bytes derived from new_host (copy_name_seeded may alter slightly)
        assert!(sni.is_some(), "SNI should be extractable after change");
        assert_eq!(sni.unwrap().len(), new_host.len(), "SNI length should match new host length");
    }

    #[test]
    fn change_tls_sni_capacity_too_small_fails() {
        let mutation = change_tls_sni_seeded_like_c(DEFAULT_FAKE_TLS, b"x.co", DEFAULT_FAKE_TLS.len() - 1, 1);
        assert_eq!(mutation.rc, -1);
    }

    #[test]
    fn change_tls_sni_same_length_host() {
        use crate::fake_profiles::{tls_fake_profile_bytes, TlsFakeProfile};
        // Use a well-formed single-record profile to avoid multi-record edge cases
        let input = tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome);
        let original_sni = parse_tls(input).unwrap();
        let new_host = vec![b'a'; original_sni.len()];
        let capacity = input.len() + 100;
        let mutation = change_tls_sni_seeded_like_c(input, &new_host, capacity, 7);
        assert_eq!(mutation.rc, 0);
        let new_sni = parse_tls(&mutation.bytes);
        assert!(new_sni.is_some());
        assert_eq!(new_sni.unwrap().len(), original_sni.len());
    }

    // ── part_tls_like_c ───────────────────────────────────────────────

    #[test]
    fn part_tls_splits_record_at_midpoint() {
        let mutation = part_tls_like_c(DEFAULT_FAKE_TLS, 100);
        assert_eq!(mutation.rc, 5);
        // The output should be larger (second record header added)
        assert!(mutation.bytes.len() > DEFAULT_FAKE_TLS.len());
        // First record should start with 0x16
        assert_eq!(mutation.bytes[0], 0x16);
        // There should be a second record header somewhere
        // The first record's declared length should be ~100 bytes
        let first_record_len = u16::from_be_bytes([mutation.bytes[3], mutation.bytes[4]]) as usize;
        assert!(first_record_len <= 100);
    }

    #[test]
    fn part_tls_at_zero_returns_split() {
        let mutation = part_tls_like_c(DEFAULT_FAKE_TLS, 0);
        // Position 0 means split at the very beginning
        assert_eq!(mutation.rc, 5);
    }

    #[test]
    fn part_tls_beyond_record_returns_original() {
        let mutation = part_tls_like_c(DEFAULT_FAKE_TLS, DEFAULT_FAKE_TLS.len() as isize + 100);
        // Position beyond the record -- should return original unchanged
        assert_eq!(mutation.bytes.len(), DEFAULT_FAKE_TLS.len());
    }

    // ── tune_tls_padding_size_like_c on well-formed profiles ──────────

    #[test]
    fn tune_tls_padding_on_well_formed_profile() {
        use crate::fake_profiles::{tls_fake_profile_bytes, TlsFakeProfile};
        let input = tls_fake_profile_bytes(TlsFakeProfile::IanaFirefox);
        let grown = tune_tls_padding_size_like_c(input, input.len() + 20);
        assert_eq!(grown.rc, 0);
        assert_eq!(grown.bytes.len(), input.len() + 20);
        assert_eq!(parse_tls(&grown.bytes), parse_tls(input));
    }

    // ── padencap_tls_like_c on well-formed profiles ───────────────────

    #[test]
    fn padencap_on_well_formed_profile() {
        use crate::fake_profiles::{tls_fake_profile_bytes, TlsFakeProfile};
        let input = tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome);
        let mutation = padencap_tls_like_c(input, 32);
        assert_eq!(mutation.rc, 0);
        // SNI should still be extractable
        assert_eq!(parse_tls(&mutation.bytes), parse_tls(input));
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

    // ── is_tls_server_hello ─────────────────────────────────────────

    #[test]
    fn is_tls_server_hello_accepts_valid_server_hello() {
        // Minimal buffer: 0x16 0x03 (TLS handshake), then version byte, length, then 0x02 (ServerHello type)
        let buf = [0x16, 0x03, 0x03, 0x00, 0x05, 0x02, 0x00, 0x00, 0x00, 0x00];
        assert!(is_tls_server_hello(&buf));
    }

    #[test]
    fn is_tls_server_hello_rejects_client_hello() {
        assert!(!is_tls_server_hello(DEFAULT_FAKE_TLS));
    }

    #[test]
    fn is_tls_server_hello_rejects_short_input() {
        assert!(!is_tls_server_hello(&[0x16, 0x03, 0x03, 0x00, 0x05]));
        assert!(!is_tls_server_hello(&[0x16, 0x03]));
        assert!(!is_tls_server_hello(&[]));
    }

    #[test]
    fn is_tls_server_hello_rejects_non_handshake_content_type() {
        let buf = [0x17, 0x03, 0x03, 0x00, 0x05, 0x02, 0x00, 0x00, 0x00, 0x00];
        assert!(!is_tls_server_hello(&buf));
    }

    // ── tls_session_id_mismatch ─────────────────────────────────────

    /// Build a minimal ServerHello response buffer.
    ///
    /// Layout (matching real ServerHello):
    /// - Bytes 0-4: record header (0x16 0x03 0x03 + 2-byte length)
    /// - Byte 5: 0x02 (ServerHello handshake type)
    /// - Bytes 6-8: handshake length (3 bytes)
    /// - Bytes 9-10: version (0x03 0x03)
    /// - Bytes 11-42: random (32 bytes)
    /// - Byte 43: session_id_length
    /// - Bytes 44..44+sid_len: session_id
    /// - 2 bytes: cipher suite
    /// - 1 byte: compression method
    /// - 2 bytes: extensions list length
    /// - extensions data
    fn build_server_hello_resp(sid: &[u8], include_supported_versions: bool) -> Vec<u8> {
        let mut buf = Vec::with_capacity(128);
        // Record header
        buf.extend_from_slice(&[0x16, 0x03, 0x03, 0x00, 0x00]); // placeholder length
        // Handshake header: ServerHello (0x02)
        buf.push(0x02);
        buf.extend_from_slice(&[0x00, 0x00, 0x00]); // placeholder hs length
        // Version
        buf.extend_from_slice(&[0x03, 0x03]);
        // Random (32 bytes)
        buf.extend_from_slice(&[0xBB; 32]);
        // Session ID
        buf.push(sid.len() as u8);
        buf.extend_from_slice(sid);
        // Cipher suite (2 bytes)
        buf.extend_from_slice(&[0x13, 0x01]); // TLS_AES_128_GCM_SHA256
        // Compression method (1 byte)
        buf.push(0x00);
        // Extensions
        if include_supported_versions {
            let ext_data: &[u8] = &[0x00, 0x2b, 0x00, 0x02, 0x03, 0x04]; // supported_versions ext
            let ext_list_len = ext_data.len() as u16;
            buf.extend_from_slice(&ext_list_len.to_be_bytes());
            buf.extend_from_slice(ext_data);
        } else {
            buf.extend_from_slice(&[0x00, 0x00]); // empty extensions
        }
        // Patch lengths
        let hs_len = (buf.len() - 9) as u32;
        buf[6] = (hs_len >> 16) as u8;
        buf[7] = (hs_len >> 8) as u8;
        buf[8] = hs_len as u8;
        let record_len = (buf.len() - 5) as u16;
        buf[3..5].copy_from_slice(&record_len.to_be_bytes());
        // Ensure minimum length of 75
        if buf.len() < 75 {
            buf.resize(75, 0);
        }
        buf
    }

    #[test]
    fn tls_session_id_mismatch_returns_false_for_short_inputs() {
        assert!(!tls_session_id_mismatch(&[0; 74], &[0; 74]));
        assert!(!tls_session_id_mismatch(&[], &[]));
    }

    #[test]
    fn tls_session_id_mismatch_returns_false_for_non_client_hello_req() {
        let non_ch = vec![0; 80];
        let resp = build_server_hello_resp(&[0; 32], true);
        assert!(!tls_session_id_mismatch(&non_ch, &resp));
    }

    #[test]
    fn tls_session_id_mismatch_returns_false_without_supported_versions() {
        let sid_len = DEFAULT_FAKE_TLS[43] as usize;
        let sid = &DEFAULT_FAKE_TLS[44..44 + sid_len];
        let resp = build_server_hello_resp(sid, false);
        assert!(!tls_session_id_mismatch(DEFAULT_FAKE_TLS, &resp));
    }

    #[test]
    fn tls_session_id_mismatch_detects_different_sid_lengths() {
        // The function uses req[43] (sid_len) to compute the extension search
        // offset in resp. When resp has a different sid_len, the extension scan
        // still starts at 44 + req_sid_len + 3 in resp. We craft a resp where
        // the supported_versions extension is findable at that offset despite
        // having a different sid_len byte at resp[43].
        let req_sid_len = DEFAULT_FAKE_TLS[43] as usize; // 32
        // Build resp with same-length SID so extensions align, then flip resp[43]
        let resp_sid = vec![0xAA; req_sid_len];
        let mut resp = build_server_hello_resp(&resp_sid, true);
        // Overwrite sid_len byte to a different value while keeping the actual
        // byte layout (so extensions are still at the right offset).
        resp[43] = (req_sid_len as u8).wrapping_sub(1);
        // req[43] != resp[43] => mismatch should be detected
        assert!(tls_session_id_mismatch(DEFAULT_FAKE_TLS, &resp));
    }

    #[test]
    fn tls_session_id_mismatch_detects_different_sid_content() {
        let sid_len = DEFAULT_FAKE_TLS[43] as usize;
        if sid_len == 0 {
            return; // Can't test content mismatch with zero-length SID
        }
        let mut sid = DEFAULT_FAKE_TLS[44..44 + sid_len].to_vec();
        sid[0] ^= 0xFF; // Flip bits in first byte
        let resp = build_server_hello_resp(&sid, true);
        assert!(tls_session_id_mismatch(DEFAULT_FAKE_TLS, &resp));
    }

    #[test]
    fn tls_session_id_mismatch_returns_false_for_matching_sids() {
        let sid_len = DEFAULT_FAKE_TLS[43] as usize;
        let sid = &DEFAULT_FAKE_TLS[44..44 + sid_len];
        let resp = build_server_hello_resp(sid, true);
        assert!(!tls_session_id_mismatch(DEFAULT_FAKE_TLS, &resp));
    }

    // ── merge_tls_records ───────────────────────────────────────────

    #[test]
    fn merge_tls_records_single_record_removes_nothing() {
        let mut buf = DEFAULT_FAKE_TLS.to_vec();
        let n = buf.len();
        let removed = merge_tls_records(&mut buf, n);
        assert_eq!(removed, 0);
    }

    #[test]
    fn merge_tls_records_short_input_returns_zero() {
        let mut buf = [0u8; 4];
        let n = buf.len();
        assert_eq!(merge_tls_records(&mut buf, n), 0);
        assert_eq!(merge_tls_records(&mut buf, 0), 0);
    }

    #[test]
    fn merge_tls_records_invalid_length_returns_zero() {
        // Buffer where read_u16 at offset 3 would fail (too short)
        let mut buf = [0x16, 0x03, 0x01, 0x00];
        let n = buf.len();
        assert_eq!(merge_tls_records(&mut buf, n), 0);
    }

    // ── find_tls_ext_offset and adjust_tls_lengths ──────────────────

    #[test]
    fn find_tls_ext_offset_locates_sni_in_default_fake() {
        let markers = tls_marker_info(DEFAULT_FAKE_TLS).unwrap();
        let found = find_tls_ext_offset(0x0000, DEFAULT_FAKE_TLS, markers.ext_len_start);
        assert!(found.is_some(), "should find SNI extension");
    }

    #[test]
    fn find_tls_ext_offset_returns_none_for_absent_type() {
        let markers = tls_marker_info(DEFAULT_FAKE_TLS).unwrap();
        let found = find_tls_ext_offset(0xDEAD, DEFAULT_FAKE_TLS, markers.ext_len_start);
        assert!(found.is_none());
    }

    #[test]
    fn find_tls_ext_offset_returns_none_for_short_data() {
        assert!(find_tls_ext_offset(0x0000, &[0x00], 0).is_none());
        assert!(find_tls_ext_offset(0x0000, &[], 0).is_none());
    }

    #[test]
    fn adjust_tls_lengths_positive_delta_updates_all_fields() {
        let mut buf = DEFAULT_FAKE_TLS.to_vec();
        let markers = tls_marker_info(&buf).unwrap();
        let orig_record = read_u16(&buf, 3).unwrap();
        let orig_hs = read_u24(&buf, 6).unwrap();
        let orig_ext = read_u16(&buf, markers.ext_len_start).unwrap();

        assert!(adjust_tls_lengths(&mut buf, markers.ext_len_start, 10));

        assert_eq!(read_u16(&buf, 3).unwrap(), orig_record + 10);
        assert_eq!(read_u24(&buf, 6).unwrap(), orig_hs + 10);
        assert_eq!(read_u16(&buf, markers.ext_len_start).unwrap(), orig_ext + 10);
    }

    #[test]
    fn adjust_tls_lengths_negative_delta_updates_all_fields() {
        let mut buf = DEFAULT_FAKE_TLS.to_vec();
        let markers = tls_marker_info(&buf).unwrap();
        let orig_record = read_u16(&buf, 3).unwrap();

        assert!(adjust_tls_lengths(&mut buf, markers.ext_len_start, -5));
        assert_eq!(read_u16(&buf, 3).unwrap(), orig_record - 5);
    }

    #[test]
    fn adjust_tls_lengths_rejects_overflow_negative() {
        let mut buf = DEFAULT_FAKE_TLS.to_vec();
        let markers = tls_marker_info(&buf).unwrap();
        assert!(!adjust_tls_lengths(&mut buf, markers.ext_len_start, -100_000));
    }

    #[test]
    fn adjust_tls_lengths_rejects_short_buffer() {
        let mut buf = [0u8; 4];
        assert!(!adjust_tls_lengths(&mut buf, 2, 1));
    }

    // ── tls_client_hello_marker_info_in_handshake ───────────────────

    #[test]
    fn marker_info_in_handshake_parses_bare_handshake() {
        // DEFAULT_FAKE_TLS has a 5-byte record header, strip it
        let handshake = &DEFAULT_FAKE_TLS[5..];
        let marker = tls_client_hello_marker_info_in_handshake(handshake);
        assert!(marker.is_some(), "should parse bare handshake");

        // Compare with record-based parse (offsets should differ by 5)
        let record_marker = tls_marker_info(DEFAULT_FAKE_TLS).unwrap();
        let hs_marker = marker.unwrap();
        assert_eq!(record_marker.ext_len_start, hs_marker.ext_len_start + 5);
        assert_eq!(record_marker.host_start, hs_marker.host_start + 5);
        assert_eq!(record_marker.host_end, hs_marker.host_end + 5);
    }

    // ── build_ext_test_buffer helper ─────────────────────────────────

    /// Build a minimal buffer that simulates a TLS record with an extension list.
    /// Layout: [record header 5B][handshake header 4B][35B filler][ext_list_len 2B][extensions...]
    fn build_ext_test_buffer(extensions: &[(u16, &[u8])]) -> (Vec<u8>, usize) {
        let ext_data_len: usize = extensions.iter().map(|(_, data)| 4 + data.len()).sum();
        let handshake_len = 35 + 2 + ext_data_len;
        let record_len = handshake_len + 4;
        let mut buf = Vec::with_capacity(5 + record_len + 64); // extra capacity for resize tests
        // Record header: 0x16 0x03 0x03 [len]
        buf.push(0x16);
        buf.extend_from_slice(&0x0303u16.to_be_bytes());
        buf.extend_from_slice(&(record_len as u16).to_be_bytes());
        // Handshake header: 0x01 [3-byte len]
        buf.push(0x01);
        buf.push(0x00);
        buf.extend_from_slice(&(handshake_len as u16).to_be_bytes());
        // 35 bytes of ClientHello filler (version + random + session_id_len)
        buf.extend_from_slice(&[0u8; 35]);
        let ext_list_offset = buf.len();
        // Extension list length
        buf.extend_from_slice(&(ext_data_len as u16).to_be_bytes());
        for (kind, data) in extensions {
            buf.extend_from_slice(&kind.to_be_bytes());
            buf.extend_from_slice(&(data.len() as u16).to_be_bytes());
            buf.extend_from_slice(data);
        }
        (buf, ext_list_offset)
    }

    // ── remove_tls_ext tests ─────────────────────────────────────────

    #[test]
    fn remove_tls_ext_removes_known_extension() {
        let (mut buf, skip) = build_ext_test_buffer(&[
            (0x0000, b"sni-data"),
            (0x0010, b"alpn-data"),
        ]);
        let n = buf.len();
        let removed = remove_tls_ext(&mut buf, n, skip, 0x0010);
        assert_eq!(removed, 4 + 9); // 4-byte header + "alpn-data".len()
    }

    #[test]
    fn remove_tls_ext_returns_zero_for_absent_extension() {
        let (mut buf, skip) = build_ext_test_buffer(&[(0x0000, b"sni")]);
        let n = buf.len();
        assert_eq!(remove_tls_ext(&mut buf, n, skip, 0xffff), 0);
    }

    #[test]
    fn remove_tls_ext_preserves_remaining_data() {
        let (mut buf, skip) = build_ext_test_buffer(&[
            (0x0000, b"sni"),
            (0x0010, b"alpn"),
            (0x002b, b"sv"),
        ]);
        let n = buf.len();
        let removed = remove_tls_ext(&mut buf, n, skip, 0x0010);
        assert!(removed > 0);
        // After removal, supported_versions (0x002b) should still be findable
        let new_n = n - removed;
        assert!(find_tls_ext_offset(0x002b, &buf[..new_n], skip).is_some());
    }

    // ── remove_ks_group tests ────────────────────────────────────────

    #[test]
    fn remove_ks_group_removes_matching_group() {
        let group_x25519: u16 = 0x001d;
        let group_kyber: u16 = 0x11ec;
        let key_x25519 = [0xAA; 32];
        let key_kyber = [0xBB; 64];
        let mut ks_data = Vec::new();
        let groups_len = (2 + 2 + key_x25519.len()) + (2 + 2 + key_kyber.len());
        ks_data.extend_from_slice(&(groups_len as u16).to_be_bytes());
        ks_data.extend_from_slice(&group_x25519.to_be_bytes());
        ks_data.extend_from_slice(&(key_x25519.len() as u16).to_be_bytes());
        ks_data.extend_from_slice(&key_x25519);
        ks_data.extend_from_slice(&group_kyber.to_be_bytes());
        ks_data.extend_from_slice(&(key_kyber.len() as u16).to_be_bytes());
        ks_data.extend_from_slice(&key_kyber);
        let (mut buf, skip) = build_ext_test_buffer(&[(0x0033, &ks_data)]);
        let n = buf.len();
        let removed = remove_ks_group(&mut buf, n, skip, group_kyber);
        assert_eq!(removed, 4 + key_kyber.len()); // 4-byte group header + 64-byte key
    }

    #[test]
    fn remove_ks_group_returns_zero_for_absent_group() {
        let key = [0xAA; 32];
        let mut ks_data = Vec::new();
        ks_data.extend_from_slice(&36u16.to_be_bytes()); // groups_list_len
        ks_data.extend_from_slice(&0x001du16.to_be_bytes()); // x25519
        ks_data.extend_from_slice(&(key.len() as u16).to_be_bytes());
        ks_data.extend_from_slice(&key);
        let (mut buf, skip) = build_ext_test_buffer(&[(0x0033, &ks_data)]);
        let n = buf.len();
        assert_eq!(remove_ks_group(&mut buf, n, skip, 0x11ec), 0);
    }

    #[test]
    fn remove_ks_group_returns_zero_without_key_share_ext() {
        let (mut buf, skip) = build_ext_test_buffer(&[(0x0000, b"sni")]);
        let n = buf.len();
        assert_eq!(remove_ks_group(&mut buf, n, skip, 0x001d), 0);
    }

    // ── resize_sni tests ─────────────────────────────────────────────

    #[test]
    fn resize_sni_grows_extension() {
        let sni_name = b"ab";
        let mut sni_ext_data = Vec::new();
        sni_ext_data.extend_from_slice(&((sni_name.len() + 3) as u16).to_be_bytes());
        sni_ext_data.push(0x00);
        sni_ext_data.extend_from_slice(&(sni_name.len() as u16).to_be_bytes());
        sni_ext_data.extend_from_slice(sni_name);
        let sni_size = sni_ext_data.len();
        let (mut buf, skip) = build_ext_test_buffer(&[(0x0000, &sni_ext_data)]);
        buf.extend_from_slice(&[0u8; 64]); // extra capacity for growth
        let n = buf.len() - 64;
        let sni_offs = find_tls_ext_offset(0x0000, &buf[..n], skip).unwrap();
        assert!(resize_sni(&mut buf, n, sni_offs, sni_size, 10));
    }

    #[test]
    fn resize_sni_shrinks_extension() {
        let sni_name = b"longexample.com";
        let mut sni_ext_data = Vec::new();
        sni_ext_data.extend_from_slice(&((sni_name.len() + 3) as u16).to_be_bytes());
        sni_ext_data.push(0x00);
        sni_ext_data.extend_from_slice(&(sni_name.len() as u16).to_be_bytes());
        sni_ext_data.extend_from_slice(sni_name);
        let sni_size = sni_ext_data.len();
        let (mut buf, skip) = build_ext_test_buffer(&[(0x0000, &sni_ext_data)]);
        let n = buf.len();
        let sni_offs = find_tls_ext_offset(0x0000, &buf[..n], skip).unwrap();
        assert!(resize_sni(&mut buf, n, sni_offs, sni_size, 3));
    }

    #[test]
    fn resize_sni_rejects_overflow() {
        let sni_name = b"a";
        let mut sni_ext_data = Vec::new();
        sni_ext_data.extend_from_slice(&((sni_name.len() + 3) as u16).to_be_bytes());
        sni_ext_data.push(0x00);
        sni_ext_data.extend_from_slice(&(sni_name.len() as u16).to_be_bytes());
        sni_ext_data.extend_from_slice(sni_name);
        let sni_size = sni_ext_data.len();
        let (mut buf, skip) = build_ext_test_buffer(&[(0x0000, &sni_ext_data)]);
        let n = buf.len();
        let sni_offs = find_tls_ext_offset(0x0000, &buf[..n], skip).unwrap();
        assert!(!resize_sni(&mut buf, n, sni_offs, sni_size, 50000));
    }

    // ── resize_ech_ext tests ─────────────────────────────────────────

    #[test]
    fn resize_ech_ext_returns_zero_when_absent() {
        let (mut buf, skip) = build_ext_test_buffer(&[(0x0000, b"sni")]);
        let n = buf.len();
        assert_eq!(resize_ech_ext(&mut buf, n, skip, 10), 0);
    }
}
