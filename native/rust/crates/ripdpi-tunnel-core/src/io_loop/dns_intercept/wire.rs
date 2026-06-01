pub(in crate::io_loop) fn dns_query_name(packet: &[u8]) -> Option<String> {
    if packet.len() < 12 {
        return None;
    }
    if u16::from_be_bytes([packet[4], packet[5]]) == 0 {
        return None;
    }

    let mut offset = 12usize;
    let mut labels = Vec::new();
    loop {
        let length = *packet.get(offset)? as usize;
        offset += 1;
        if length == 0 {
            break;
        }
        if length & 0b1100_0000 != 0 {
            return None;
        }
        let label = packet.get(offset..offset + length)?;
        labels.push(std::str::from_utf8(label).ok()?.to_string());
        offset += length;
    }
    if labels.is_empty() { None } else { Some(labels.join(".")) }
}
