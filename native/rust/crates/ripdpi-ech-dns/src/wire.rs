use std::net::Ipv4Addr;

pub const DNS_RECORD_TYPE_A: u16 = 1;
pub(crate) const DNS_RECORD_TYPE_SVCB: u16 = 64;
pub(crate) const DNS_RECORD_TYPE_HTTPS: u16 = 65;

#[cfg(test)]
pub fn build_dns_query(domain: &str, query_id: u16) -> Result<Vec<u8>, String> {
    build_dns_query_with_type(domain, query_id, DNS_RECORD_TYPE_A)
}

pub fn build_dns_query_with_type(domain: &str, query_id: u16, record_type: u16) -> Result<Vec<u8>, String> {
    let mut packet = Vec::with_capacity(512);
    packet.extend(query_id.to_be_bytes());
    packet.extend(0x0100u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    for label in domain.split('.') {
        if label.is_empty() || label.len() > 63 {
            return Err("invalid_dns_name".to_string());
        }
        packet.push(label.len() as u8);
        packet.extend(label.as_bytes());
    }
    packet.push(0);
    packet.extend(record_type.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    Ok(packet)
}

pub fn parse_dns_response(packet: &[u8], expected_id: u16) -> Result<Vec<String>, String> {
    if packet.len() < 12 {
        return Err("dns_response_too_short".to_string());
    }
    let id = u16::from_be_bytes([packet[0], packet[1]]);
    if id != expected_id {
        return Err("dns_response_id_mismatch".to_string());
    }
    let rcode = packet[3] & 0x0F;
    if rcode == 3 {
        return Err("dns_nxdomain".to_string());
    }
    if rcode == 2 {
        return Err("dns_servfail".to_string());
    }
    if rcode == 5 {
        return Err("dns_refused".to_string());
    }
    let answer_count = u16::from_be_bytes([packet[6], packet[7]]) as usize;
    let question_count = u16::from_be_bytes([packet[4], packet[5]]) as usize;
    let mut offset = 12usize;
    for _ in 0..question_count {
        offset = skip_dns_name(packet, offset)?;
        offset += 4;
        if offset > packet.len() {
            return Err("dns_question_truncated".to_string());
        }
    }

    let mut answers = Vec::new();
    for _ in 0..answer_count {
        offset = skip_dns_name(packet, offset)?;
        if offset + 10 > packet.len() {
            return Err("dns_answer_truncated".to_string());
        }
        let record_type = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
        let data_len = u16::from_be_bytes([packet[offset + 8], packet[offset + 9]]) as usize;
        offset += 10;
        if offset + data_len > packet.len() {
            return Err("dns_rdata_truncated".to_string());
        }
        if record_type == DNS_RECORD_TYPE_A && data_len == 4 {
            answers.push(
                Ipv4Addr::new(packet[offset], packet[offset + 1], packet[offset + 2], packet[offset + 3]).to_string(),
            );
        }
        offset += data_len;
    }
    if answers.is_empty() {
        return Err("dns_empty".to_string());
    }
    Ok(answers)
}

pub fn skip_dns_name(packet: &[u8], mut offset: usize) -> Result<usize, String> {
    loop {
        let Some(length) = packet.get(offset).copied() else {
            return Err("dns_name_truncated".to_string());
        };
        if length & 0b1100_0000 == 0b1100_0000 {
            if offset + 1 >= packet.len() {
                return Err("dns_pointer_truncated".to_string());
            }
            return Ok(offset + 2);
        }
        offset += 1;
        if length == 0 {
            return Ok(offset);
        }
        offset += length as usize;
        if offset > packet.len() {
            return Err("dns_label_truncated".to_string());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_dns_query_valid_domain() {
        let result = build_dns_query("example.com", 0x1234).unwrap();
        assert_eq!(result.len(), 12 + 8 + 4 + 1 + 4);
        assert_eq!(result[0], 0x12);
        assert_eq!(result[1], 0x34);
        assert_eq!(result[2], 0x01);
        assert_eq!(result[3], 0x00);
        assert_eq!(u16::from_be_bytes([result[4], result[5]]), 1);
    }

    #[test]
    fn build_dns_query_rejects_empty_label() {
        let result = build_dns_query("example..com", 1);
        assert!(result.is_err());
    }

    #[test]
    fn parse_dns_response_with_single_a_record() {
        let query_id: u16 = 0xABCD;
        let mut packet = Vec::new();
        packet.extend(query_id.to_be_bytes());
        packet.extend(0x8180u16.to_be_bytes());
        packet.extend(1u16.to_be_bytes());
        packet.extend(1u16.to_be_bytes());
        packet.extend(0u16.to_be_bytes());
        packet.extend(0u16.to_be_bytes());
        packet.push(7);
        packet.extend(b"example");
        packet.push(3);
        packet.extend(b"com");
        packet.push(0);
        packet.extend(1u16.to_be_bytes());
        packet.extend(1u16.to_be_bytes());
        packet.extend(0xC00Cu16.to_be_bytes());
        packet.extend(1u16.to_be_bytes());
        packet.extend(1u16.to_be_bytes());
        packet.extend(300u32.to_be_bytes());
        packet.extend(4u16.to_be_bytes());
        packet.extend([1, 2, 3, 4]);

        let answers = parse_dns_response(&packet, query_id).unwrap();
        assert_eq!(answers, vec!["1.2.3.4"]);
    }

    #[test]
    fn parse_dns_response_id_mismatch() {
        let mut packet = vec![0u8; 12];
        packet[0] = 0x00;
        packet[1] = 0x01;
        let result = parse_dns_response(&packet, 0x0002);
        assert_eq!(result.unwrap_err(), "dns_response_id_mismatch");
    }

    #[test]
    fn parse_dns_response_too_short() {
        let result = parse_dns_response(&[0u8; 5], 1);
        assert_eq!(result.unwrap_err(), "dns_response_too_short");
    }

    #[test]
    fn skip_dns_name_with_labels() {
        let data = [7, b'e', b'x', b'a', b'm', b'p', b'l', b'e', 3, b'c', b'o', b'm', 0];
        let end = skip_dns_name(&data, 0).unwrap();
        assert_eq!(end, 13);
    }

    #[test]
    fn skip_dns_name_with_pointer() {
        let data = [0xC0, 0x0C, 0x00];
        let end = skip_dns_name(&data, 0).unwrap();
        assert_eq!(end, 2);
    }

    #[test]
    fn skip_dns_name_truncated() {
        let data: [u8; 0] = [];
        let result = skip_dns_name(&data, 0);
        assert!(result.is_err());
    }
}
