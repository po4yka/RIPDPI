use crate::util::{read_u16, read_u24, write_u16, write_u24};

pub(super) fn find_tls_ext_offset(kind: u16, data: &[u8], mut skip: usize) -> Option<usize> {
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

pub(super) fn adjust_tls_lengths(buffer: &mut [u8], ext_len_start: usize, delta: isize) -> bool {
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

pub(super) fn merge_tls_records(buffer: &mut [u8], n: usize) -> usize {
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

pub(super) fn remove_ks_group(buffer: &mut [u8], n: usize, skip: usize, group: u16) -> usize {
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

pub(super) fn remove_tls_ext(buffer: &mut [u8], n: usize, skip: usize, kind: u16) -> usize {
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

pub(super) fn resize_ech_ext(buffer: &mut [u8], n: usize, skip: usize, mut inc: isize) -> isize {
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

pub(super) fn resize_sni(buffer: &mut [u8], n: usize, sni_offs: usize, sni_size: usize, new_size: usize) -> bool {
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
