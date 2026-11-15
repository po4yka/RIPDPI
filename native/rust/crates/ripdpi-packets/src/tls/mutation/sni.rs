use crate::types::{OracleRng, PacketMutation};
use crate::util::{copy_name_seeded, read_u16, read_u24, write_u16};

use super::super::edit::{
    find_tls_ext_offset, merge_tls_records, remove_ks_group, remove_tls_ext, resize_ech_ext, resize_sni,
};
use super::padding::grow_padding;

pub fn change_tls_sni_seeded_like_c(input: &[u8], host: &[u8], capacity: usize, seed: u32) -> PacketMutation {
    if capacity < input.len() {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }
    let normalized = match normalize_declared_lengths(input) {
        Ok(Some(bytes)) => bytes,
        Ok(None) => return change_tls_sni_normalized(input, host, capacity, seed),
        Err(()) => return PacketMutation { rc: -1, bytes: input.to_vec() },
    };
    // Bytes dropped from the input are not given back as spare room.
    let capacity = capacity - (input.len() - normalized.len());
    let mutation = change_tls_sni_normalized(&normalized, host, capacity, seed);
    if mutation.rc == 0 { mutation } else { PacketMutation { rc: -1, bytes: input.to_vec() } }
}

/// Built-in fakes may declare more bytes than they carry: a template cut inside
/// its padding, or oversized record/handshake lengths. The same holds for a real
/// hello cut short by a partial read. The rewrite sizes its output from the
/// declared record length, so keep only the complete extensions inside the
/// declared extension list and set every length from what remains. A cut
/// extension and any dangling bytes are dropped. `Ok(None)` means the input
/// already fits its record; `Err` means it cannot be normalized (no complete
/// SNI extension).
fn normalize_declared_lengths(input: &[u8]) -> Result<Option<Vec<u8>>, ()> {
    let Some(record_len) = read_u16(input, 3) else { return Ok(None) };
    if record_len + 5 <= input.len() || input.len() < 9 {
        return Ok(None);
    }
    let mut fixed = input.to_vec();
    write_u16(&mut fixed, 3, input.len() - 5);
    write_u16(&mut fixed, 7, input.len() - 9);
    fixed[6] = 0;
    let (ext_len_offset, end) = {
        let parsed = crate::tls_nom::parse_client_hello_record(&fixed).ok_or(())?;
        let ext_len_offset = parsed.ext_len_offset;
        let list_end = (ext_len_offset + 2 + read_u16(&fixed, ext_len_offset).ok_or(())?).min(fixed.len());
        let mut end = ext_len_offset + 2;
        let mut has_sni = false;
        for ext in &parsed.extensions {
            let ext_end = ext.type_offset + 4 + ext.declared_len;
            if ext_end > list_end {
                break;
            }
            has_sni |= ext.ext_type == 0x0000;
            end = ext_end;
        }
        if !has_sni {
            return Err(());
        }
        (ext_len_offset, end)
    };
    fixed.truncate(end);
    write_u16(&mut fixed, ext_len_offset, end - ext_len_offset - 2);
    write_u16(&mut fixed, 7, end - 9);
    write_u16(&mut fixed, 3, end - 5);
    Ok(Some(fixed))
}

fn change_tls_sni_normalized(input: &[u8], host: &[u8], capacity: usize, seed: u32) -> PacketMutation {
    if capacity < input.len() || host.len() > u16::MAX as usize {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    // The handshake length cannot be read until its whole header is in the first record.
    if read_u16(input, 3).is_none_or(|record_len| record_len < 4) {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    // A complete ClientHello followed by more bytes is not a single fake record.
    // Reject it before merge_tls_records can consume or discard the following record.
    if let (Some(record_len), Some(handshake_len)) = (read_u16(input, 3), read_u24(input, 6))
        && handshake_len + 4 <= record_len
        && input.len() > record_len + 5
    {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let handshake_len = read_u24(input, 6);
    let mut output = vec![0; capacity];
    output[..input.len()].copy_from_slice(input);
    let n = input.len();
    let removed = merge_tls_records(&mut output, n);
    let mut avail = removed as isize + (capacity - n) as isize;
    let Some(mut record_size) = read_u16(&output, 3).map(|value| value as isize) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    if n - removed > 5 + record_size as usize || handshake_len.is_some_and(|len| record_size as usize > len + 4) {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }
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
        let used = record_end - avail;
        if record_end > capacity as isize || used < 9 {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
        // Make the used part a valid record, then give the spare bytes to the padding.
        let used_record_size = (used - 5) as usize;
        let _ = write_u16(&mut output, 3, used_record_size);
        let _ = write_u16(&mut output, 7, used_record_size.saturating_sub(4));
        let _ = write_u16(&mut output, skip, (used as usize).saturating_sub(skip + 2));
        let mut record = output[..used as usize].to_vec();
        if !grow_padding(&mut record, avail as usize) {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
        output[..record.len()].copy_from_slice(&record);
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
