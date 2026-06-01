use crate::types::PacketMutation;
use crate::util::{read_u16, write_u16};

use super::super::detect::is_tls_client_hello;
use super::super::edit::adjust_tls_lengths;

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
                if pad_offs + 4 <= output.len()
                    && let Some(pad_len) = read_u16(input, pad_offs + 2)
                {
                    let _ = write_u16(&mut output, pad_offs + 2, pad_len.saturating_add(grow));
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
            if let Some(pad_offs) = pad_offs
                && pad_offs + 4 <= output.len()
                && let Some(pad_len) = read_u16(input, pad_offs + 2)
            {
                let _ = write_u16(&mut output, pad_offs + 2, pad_len.saturating_sub(shrink));
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
