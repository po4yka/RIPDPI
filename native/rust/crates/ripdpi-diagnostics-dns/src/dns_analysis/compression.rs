use std::collections::BTreeSet;

use super::wire_header::parse_header;

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
    let mut visited = BTreeSet::new();
    let mut end_offset = None;

    loop {
        if offset >= packet.len() || !visited.insert(offset) {
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

#[cfg(test)]
mod tests {
    use super::has_malformed_compression_pointers;

    const BACKWARD_CHAIN: &[u8] = &[
        0x04, 0xd2, 0x81, 0x80, 0, 1, 0, 4, 0, 0, 0, 0, 3, b'w', b'w', b'w', 7, b'e', b'x', b'a', b'm', b'p', b'l',
        b'e', 4, b't', b'e', b's', b't', 0, 0, 1, 0, 1, 0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192, 0, 2, 1, 0xc0,
        34, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192, 0, 2, 2, 0xc0, 50, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192, 0, 2, 3, 0xc0,
        66, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192, 0, 2, 4,
    ];

    #[test]
    fn four_backward_pointer_hops_are_valid() {
        assert!(!has_malformed_compression_pointers(BACKWARD_CHAIN));
    }

    #[test]
    fn repeated_pointer_offset_is_a_real_cycle() {
        let mut packet = BACKWARD_CHAIN.to_vec();
        packet[35] = 34;
        assert!(has_malformed_compression_pointers(&packet));
    }
}
