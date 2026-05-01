use super::wire_header::parse_header;

/// Maximum number of compression pointer jumps before declaring a loop.
const MAX_JUMPS: usize = 3;

/// Check if any DNS name compression pointer in the packet is malformed.
///
/// Walks name fields in answer/authority/additional sections, following
/// compression pointers with a jump limit. Returns `true` if any pointer
/// target is out of bounds or creates a loop.
pub fn has_malformed_compression_pointers(packet: &[u8]) -> bool {
    let Some(header) = parse_header(packet) else {
        return false;
    };

    let mut offset = 12usize;

    for _ in 0..header.question_count {
        match validate_name(packet, offset) {
            Some(end) => offset = end + 4,
            None => return true,
        }
        if offset > packet.len() {
            return true;
        }
    }

    let total_records =
        header.answer_count as usize + header.authority_count as usize + header.additional_count as usize;
    for _ in 0..total_records {
        match validate_name(packet, offset) {
            Some(end) => offset = end,
            None => return true,
        }
        if offset + 10 > packet.len() {
            return true;
        }
        let rdlength = u16::from_be_bytes([packet[offset + 8], packet[offset + 9]]) as usize;
        offset += 10 + rdlength;
        if offset > packet.len() {
            return true;
        }
    }

    false
}

fn validate_name(packet: &[u8], mut offset: usize) -> Option<usize> {
    let mut jumps = 0usize;
    let mut end_offset = None;

    loop {
        if offset >= packet.len() {
            return None;
        }
        let byte = packet[offset];

        if byte & 0xC0 == 0xC0 {
            if offset + 1 >= packet.len() {
                return None;
            }
            let target = ((byte as usize & 0x3F) << 8) | packet[offset + 1] as usize;
            if target >= packet.len() {
                return None;
            }
            jumps += 1;
            if jumps > MAX_JUMPS {
                return None;
            }
            if end_offset.is_none() {
                end_offset = Some(offset + 2);
            }
            offset = target;
            continue;
        }

        if byte == 0 {
            return Some(end_offset.unwrap_or(offset + 1));
        }

        let label_len = byte as usize;
        offset += 1 + label_len;
    }
}
