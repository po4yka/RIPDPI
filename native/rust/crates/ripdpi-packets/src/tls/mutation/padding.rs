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

/// Add `grow` bytes of padding to a ClientHello record: extend an existing
/// padding extension in place, or insert a new one (needs `grow >= 4`) before
/// `pre_shared_key`, which RFC 8446 section 4.2.11 requires to stay last.
/// Updates the record, handshake and extension lengths. Returns false with
/// the record unchanged when the padding cannot grow by exactly `grow`.
pub(super) fn grow_padding(record: &mut Vec<u8>, grow: usize) -> bool {
    let Some(parsed) = crate::tls_nom::parse_client_hello_record(record) else {
        return false;
    };
    let ext_len_offset = parsed.ext_len_offset;
    let existing =
        parsed.extensions.iter().find(|ext| ext.ext_type == 0x0015).map(|ext| (ext.type_offset, ext.data.len()));
    let psk_offset = parsed.extensions.iter().find(|ext| ext.ext_type == 0x0029).map(|ext| ext.type_offset);
    let mut grown = record.clone();
    match existing {
        Some((offset, len)) => {
            if len + grow > usize::from(u16::MAX) || !write_u16(&mut grown, offset + 2, len + grow) {
                return false;
            }
            let end = offset + 4 + len;
            grown.splice(end..end, std::iter::repeat_n(0, grow));
        }
        None => {
            let Some(pad_len) = grow.checked_sub(4).and_then(|len| u16::try_from(len).ok()) else {
                return false;
            };
            let mut ext = vec![0x00, 0x15];
            ext.extend_from_slice(&pad_len.to_be_bytes());
            ext.resize(grow, 0);
            let at = psk_offset.unwrap_or(grown.len());
            grown.splice(at..at, ext);
        }
    }
    if !adjust_tls_lengths(&mut grown, ext_len_offset, grow as isize) {
        return false;
    }
    *record = grown;
    true
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
            if !grow_padding(&mut output, target_size - original_len) {
                return PacketMutation { rc: -1, bytes: input.to_vec() };
            }
            PacketMutation { rc: 0, bytes: output }
        }
        std::cmp::Ordering::Less => {
            // Cutting the tail would truncate other extensions, so remove bytes only
            // from the padding payload, or drop whole optional extensions.
            let shrink = original_len - target_size;
            if let Some(pad_offs) = pad_offs
                && let Some(pad_len) = read_u16(input, pad_offs + 2)
                && pad_len >= shrink
                && pad_offs + 4 + shrink <= original_len
            {
                let payload_start = pad_offs + 4;
                let _ = write_u16(&mut output, pad_offs + 2, pad_len - shrink);
                output.drain(payload_start..payload_start + shrink);
                if !adjust_tls_lengths(&mut output, ext_len_start, -(shrink as isize)) {
                    return PacketMutation { rc: -1, bytes: input.to_vec() };
                }
                return PacketMutation { rc: 0, bytes: output };
            }
            drop_optional_extensions_to_fit(input, &parsed, target_size)
        }
    }
}

/// Extension sets a fake may lose without becoming malformed, tried in order:
/// first the padding, then also the ECH offer.
const DROPPABLE_EXTENSION_SETS: [&[u16]; 2] = [&[0x0015], &[0x0015, 0xfe0d]];

/// Shrink a ClientHello to `target_size` by removing whole optional extensions
/// and filling any gap with a new padding extension at the end. Returns rc -1
/// with the input unchanged when no set reaches the target exactly.
fn drop_optional_extensions_to_fit(
    input: &[u8],
    parsed: &crate::tls_nom::ClientHelloParsed<'_>,
    target_size: usize,
) -> PacketMutation {
    for droppable in DROPPABLE_EXTENSION_SETS {
        let mut output = input.to_vec();
        // Extensions are in buffer order; drain from the back so earlier offsets stay valid.
        for ext in parsed.extensions.iter().rev().filter(|ext| droppable.contains(&ext.ext_type)) {
            output.drain(ext.type_offset..ext.type_offset + 4 + ext.data.len());
        }
        let Some(gap) = target_size.checked_sub(output.len()) else {
            continue;
        };
        let removed = input.len() - output.len();
        if !adjust_tls_lengths(&mut output, parsed.ext_len_offset, -(removed as isize)) {
            continue;
        }
        if gap == 0 || grow_padding(&mut output, gap) {
            return PacketMutation { rc: 0, bytes: output };
        }
    }
    PacketMutation { rc: -1, bytes: input.to_vec() }
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
