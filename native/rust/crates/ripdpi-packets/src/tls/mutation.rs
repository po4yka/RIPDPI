use crate::types::{OracleRng, PacketMutation};
use crate::util::{copy_name_seeded, fill_random_tls_host_like_c, read_u16, write_u16};

use super::detect::{is_tls_client_hello, tls_marker_info};
use super::edit::{
    adjust_tls_lengths, find_tls_ext_offset, merge_tls_records, remove_ks_group, remove_tls_ext, resize_ech_ext,
    resize_sni,
};

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

/// Randomize TLS Random, Session ID, and Key Share fields in place.
pub fn randomize_tls_seeded_inplace(buf: &mut [u8], seed: u32) -> isize {
    if buf.len() < 44 {
        return 0;
    }
    let sid_len = buf[43] as usize;
    if buf.len() < 44 + sid_len + 2 {
        return 0;
    }
    let mut rng = OracleRng::seeded(seed);
    for byte in &mut buf[11..43] {
        *byte = rng.next_u8();
    }
    for byte in &mut buf[44..44 + sid_len] {
        *byte = rng.next_u8();
    }
    let Some(parsed) = crate::tls_nom::parse_client_hello_record(buf) else {
        return 0;
    };
    let Some(ks_offs) = crate::tls_nom::find_extension_offset(&parsed, 0x0033) else {
        return 0;
    };
    if ks_offs + 6 >= buf.len() {
        return 0;
    }
    let Some(ks_size) = read_u16(buf, ks_offs + 2) else {
        return 0;
    };
    if ks_offs + 4 + ks_size > buf.len() {
        return 0;
    }
    let ks_end = ks_offs + 4 + ks_size;
    let mut group_offs = ks_offs + 6;
    while group_offs + 4 < ks_end {
        let Some(group_size) = read_u16(buf, group_offs + 2) else {
            return 0;
        };
        let group_end = group_offs + 4 + group_size;
        if group_end > ks_end || group_end > buf.len() {
            return 0;
        }
        for byte in &mut buf[group_offs + 4..group_end] {
            *byte = rng.next_u8();
        }
        group_offs += 4 + group_size;
    }
    0
}

pub fn randomize_tls_seeded_like_c(input: &[u8], seed: u32) -> PacketMutation {
    let mut output = input.to_vec();
    let rc = randomize_tls_seeded_inplace(&mut output, seed);
    PacketMutation { rc, bytes: output }
}

/// Randomize the SNI hostname bytes in place.
pub fn randomize_tls_sni_seeded_inplace(buf: &mut [u8], seed: u32) -> isize {
    let Some(markers) = tls_marker_info(buf) else {
        return -1;
    };
    let mut rng = OracleRng::seeded(seed);
    fill_random_tls_host_like_c(&mut buf[markers.host_start..markers.host_end], &mut rng);
    0
}

pub fn randomize_tls_sni_seeded_like_c(input: &[u8], seed: u32) -> PacketMutation {
    let mut output = input.to_vec();
    let rc = randomize_tls_sni_seeded_inplace(&mut output, seed);
    PacketMutation { rc, bytes: output }
}

/// Copy the Session ID from `original` into `fake` in place.
pub fn duplicate_tls_session_id_inplace(fake: &mut [u8], original: &[u8]) -> isize {
    if !is_tls_client_hello(fake) || !is_tls_client_hello(original) || fake.len() < 44 || original.len() < 44 {
        return -1;
    }
    let sid_len = fake[43] as usize;
    if fake.len() < 44 + sid_len || original[43] as usize != sid_len || original.len() < 44 + sid_len {
        return -1;
    }
    fake[44..44 + sid_len].copy_from_slice(&original[44..44 + sid_len]);
    0
}

pub fn duplicate_tls_session_id_like_c(fake_input: &[u8], original_input: &[u8]) -> PacketMutation {
    let mut output = fake_input.to_vec();
    let rc = duplicate_tls_session_id_inplace(&mut output, original_input);
    PacketMutation { rc, bytes: output }
}

/// Resize TLS padding in place, transferring result into the caller's Vec.
pub fn tune_tls_padding_size_into(buf: &mut Vec<u8>, target_size: usize) -> isize {
    let mutation = tune_tls_padding_size_like_c(buf, target_size);
    if mutation.rc == 0 && is_tls_client_hello(&mutation.bytes) {
        *buf = mutation.bytes;
    }
    mutation.rc
}

/// Encapsulate payload into TLS padding, transferring result into the caller's Vec.
pub fn padencap_tls_into(buf: &mut Vec<u8>, payload_len: usize) -> isize {
    let mutation = padencap_tls_like_c(buf, payload_len);
    if mutation.rc == 0 && is_tls_client_hello(&mutation.bytes) {
        *buf = mutation.bytes;
    }
    mutation.rc
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

pub fn remove_tls_key_share_group_like_c(input: &[u8], group: u16) -> PacketMutation {
    if !is_tls_client_hello(input) {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let Some(parsed) = crate::tls_nom::parse_client_hello_record(input) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };

    let mut output = input.to_vec();
    let removed = remove_ks_group(&mut output, input.len(), parsed.ext_len_offset, group);
    if removed == 0 {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }
    let Some(key_share_offs) = crate::tls_nom::find_extension_offset(&parsed, 0x0033) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    let Some(remaining_key_share_len) = read_u16(&output, key_share_offs + 2) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    if remaining_key_share_len <= 2 {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }
    if !adjust_tls_lengths(&mut output, parsed.ext_len_offset, -(removed as isize)) {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }
    output.truncate(input.len() - removed);
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
