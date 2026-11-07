use hickory_proto::op::{Header, Message, MessageType, OpCode, Query, ResponseCode};
use hickory_proto::rr::{Name, RData, RecordType};
use hickory_proto::serialize::binary::{BinDecodable, BinDecoder, DecodeError};

pub const DNS_RECORD_TYPE_A: u16 = 1;
pub(crate) const DNS_RECORD_TYPE_SVCB: u16 = 64;
pub(crate) const DNS_RECORD_TYPE_HTTPS: u16 = 65;

#[cfg(test)]
pub fn build_dns_query(domain: &str, query_id: u16) -> Result<Vec<u8>, String> {
    build_dns_query_with_type(domain, query_id, DNS_RECORD_TYPE_A)
}

pub fn build_dns_query_with_type(domain: &str, query_id: u16, record_type: u16) -> Result<Vec<u8>, String> {
    if domain.is_empty() || domain == "." {
        return Err("invalid_dns_name".to_string());
    }
    let name = Name::from_ascii(domain).map_err(|_| "invalid_dns_name".to_string())?;
    let mut message = Message::new(query_id, MessageType::Query, OpCode::Query);
    message.metadata.recursion_desired = true;
    message.add_query(Query::new(name, RecordType::from(record_type)));
    message.to_vec().map_err(|_| "invalid_dns_name".to_string())
}

pub fn parse_dns_response(packet: &[u8], expected_id: u16) -> Result<Vec<String>, String> {
    let header = Header::read(&mut BinDecoder::new(packet)).map_err(|_| "dns_response_too_short".to_string())?;
    if header.metadata.id != expected_id {
        return Err("dns_response_id_mismatch".to_string());
    }
    match header.metadata.response_code {
        ResponseCode::NXDomain => return Err("dns_nxdomain".to_string()),
        ResponseCode::ServFail => return Err("dns_servfail".to_string()),
        ResponseCode::Refused => return Err("dns_refused".to_string()),
        _ => {}
    }
    let message = Message::from_vec(packet).map_err(|_| "dns_malformed_response".to_string())?;
    let answers = message
        .answers
        .iter()
        .filter_map(|record| match &record.data {
            RData::A(address) => Some(address.0.to_string()),
            _ => None,
        })
        .collect::<Vec<_>>();
    if answers.is_empty() {
        return Err("dns_empty".to_string());
    }
    Ok(answers)
}

pub fn parse_dns_response_for_query(packet: &[u8], query: &[u8]) -> Result<Vec<String>, String> {
    let id_bytes = query.get(..2).ok_or("dns_query_too_short")?;
    let expected_id = u16::from_be_bytes([id_bytes[0], id_bytes[1]]);
    ripdpi_dns_resolver::validate_dns_response_for_query(query, packet).map_err(|error| error.to_string())?;
    parse_dns_response(packet, expected_id)
}

pub fn skip_dns_name(packet: &[u8], offset: usize) -> Result<usize, String> {
    let mut decoder = BinDecoder::new(packet);
    decoder.read_slice(offset).map_err(|_| "dns_name_truncated".to_string())?;
    Name::read(&mut decoder).map_err(|error| match error {
        DecodeError::InsufficientBytes => "dns_name_truncated".to_string(),
        _ => "dns_invalid_name".to_string(),
    })?;
    Ok(decoder.index())
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

        let query = build_dns_query("example.com", query_id).unwrap();
        let answers = parse_dns_response_for_query(&packet, &query).unwrap();
        assert_eq!(answers, vec!["1.2.3.4"]);
    }

    #[test]
    fn validated_response_rejects_wrong_question_and_answer_owner() {
        let query = build_dns_query("example.com", 0x1234).unwrap();
        let mut response = query[..12].to_vec();
        response[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
        response[6..8].copy_from_slice(&1u16.to_be_bytes());
        response.extend_from_slice(&query[12..]);
        let answer_offset = response.len();
        response.extend_from_slice(&[0xC0, 0x0C]);
        response.extend_from_slice(&1u16.to_be_bytes());
        response.extend_from_slice(&1u16.to_be_bytes());
        response.extend_from_slice(&60u32.to_be_bytes());
        response.extend_from_slice(&4u16.to_be_bytes());
        response.extend_from_slice(&[1, 2, 3, 4]);
        assert_eq!(parse_dns_response_for_query(&response, &query).unwrap(), vec!["1.2.3.4"]);

        let mut changed_case = response.clone();
        changed_case[13] = b'E';
        assert_eq!(parse_dns_response_for_query(&changed_case, &query).unwrap(), vec!["1.2.3.4"]);

        let other_query = build_dns_query("other.com", 0x1234).unwrap();
        let mut wrong_question = response.clone();
        wrong_question.splice(12..answer_offset, other_query[12..].iter().copied());
        assert!(parse_dns_response_for_query(&wrong_question, &query).is_err());

        let mut wrong_qtype = response.clone();
        wrong_qtype[answer_offset - 4..answer_offset - 2].copy_from_slice(&28u16.to_be_bytes());
        assert!(parse_dns_response_for_query(&wrong_qtype, &query).is_err());

        let mut wrong_nxdomain = wrong_question;
        wrong_nxdomain[3] = (wrong_nxdomain[3] & 0xf0) | 3;
        assert!(parse_dns_response_for_query(&wrong_nxdomain, &query).is_err());

        let mut wrong_owner = response.clone();
        wrong_owner.splice(answer_offset..answer_offset + 2, b"\x05other\x03com\x00".iter().copied());
        assert!(parse_dns_response_for_query(&wrong_owner, &query).is_err());

        let mut wrong_qr = response;
        wrong_qr[2] &= 0x7f;
        assert!(parse_dns_response_for_query(&wrong_qr, &query).is_err());
    }

    #[test]
    fn validated_response_accepts_cname_answer_chain() {
        let query = build_dns_query("example.com", 0x1234).unwrap();
        let mut response = query[..12].to_vec();
        response[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
        response[6..8].copy_from_slice(&2u16.to_be_bytes());
        response.extend_from_slice(&query[12..]);
        response.extend_from_slice(&[0xC0, 0x0C]);
        response.extend_from_slice(&5u16.to_be_bytes());
        response.extend_from_slice(&1u16.to_be_bytes());
        response.extend_from_slice(&60u32.to_be_bytes());
        response.extend_from_slice(&11u16.to_be_bytes());
        response.extend_from_slice(b"\x05alias\x03com\x00");
        response.extend_from_slice(b"\x05alias\x03com\x00");
        response.extend_from_slice(&1u16.to_be_bytes());
        response.extend_from_slice(&1u16.to_be_bytes());
        response.extend_from_slice(&60u32.to_be_bytes());
        response.extend_from_slice(&4u16.to_be_bytes());
        response.extend_from_slice(&[1, 2, 3, 4]);

        assert_eq!(parse_dns_response_for_query(&response, &query).unwrap(), vec!["1.2.3.4"]);
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
        let data = [1, b'a', 0, 0xc0, 0];
        let end = skip_dns_name(&data, 3).unwrap();
        assert_eq!(end, 5);
    }

    #[test]
    fn skip_dns_name_truncated() {
        let data: [u8; 0] = [];
        let result = skip_dns_name(&data, 0);
        assert_eq!(result, Err("dns_name_truncated".to_string()));
        assert_eq!(skip_dns_name(&[1], 0), Err("dns_name_truncated".to_string()));
    }
    #[test]
    fn standard_fqdn_query_matches_independent_wire_vector() {
        let expected = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x07example\x03com\x00\x00\x01\x00\x01";
        for domain in ["example.com", "example.com."] {
            assert_eq!(build_dns_query_with_type(domain, 0x1234, 1).expect("valid FQDN"), expected);
        }
    }

    #[test]
    fn standard_name_decoder_rejects_invalid_pointers() {
        for packet in [&[0xc0, 0][..], &[0xc0, 2, 0], &[0xc0, 12, 0]] {
            assert!(skip_dns_name(packet, 0).is_err(), "invalid compressed name must fail");
        }
    }

    #[test]
    fn query_name_and_record_type_boundaries() {
        for domain in [
            "".to_string(),
            ".".to_string(),
            "a..b".to_string(),
            "a".repeat(64),
            ["a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(62)].join("."),
        ] {
            assert_eq!(build_dns_query_with_type(&domain, 7, 1), Err("invalid_dns_name".to_string()));
        }
        let longest = ["a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61)].join(".");
        assert_eq!(build_dns_query_with_type(&longest, 7, 1).unwrap().len(), 271);
        for record_type in [1u16, 28, 64, 65, 65000] {
            let packet = build_dns_query_with_type("example.com", 7, record_type).unwrap();
            assert_eq!(&packet[25..], &[record_type.to_be_bytes(), [0, 1]].concat());
        }
    }

    #[test]
    fn response_keeps_ipv4_wire_order_and_duplicates() {
        let query = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x07example\x03com\x00\x00\x01\x00\x01";
        let mut response = query.to_vec();
        response[2..4].copy_from_slice(&[0x81, 0x80]);
        response[6..8].copy_from_slice(&[0, 3]);
        for ip in [[9, 9, 9, 9], [1, 2, 3, 4], [9, 9, 9, 9]] {
            response.extend_from_slice(b"\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04");
            response.extend_from_slice(&ip);
        }
        let expected = vec!["9.9.9.9", "1.2.3.4", "9.9.9.9"];
        assert_eq!(parse_dns_response(&response, 0x1234).unwrap(), expected);
        assert_eq!(parse_dns_response_for_query(&response, query).unwrap(), expected);
        response.pop();
        assert_eq!(parse_dns_response(&response, 0x1234), Err("dns_malformed_response".to_string()));
        assert!(parse_dns_response_for_query(&response, query).is_err());
    }

    #[test]
    fn empty_non_ipv4_and_negative_responses_keep_categories() {
        let mut response = [0u8; 12].to_vec();
        assert_eq!(parse_dns_response(&response, 0), Err("dns_empty".to_string()));
        response[6..8].copy_from_slice(&[0, 1]);
        response.extend_from_slice(b"\x00\x00\x1c\x00\x01\x00\x00\x00\x3c\x00\x10");
        response.extend_from_slice(&[0; 16]);
        assert_eq!(parse_dns_response(&response, 0), Err("dns_empty".to_string()));
        for (rcode, expected) in [(3, "dns_nxdomain"), (2, "dns_servfail"), (5, "dns_refused")] {
            response.truncate(12);
            response[3] = rcode;
            assert_eq!(parse_dns_response(&response, 0), Err(expected.to_string()));
        }
        assert_eq!(parse_dns_response_for_query(&response, &[0]), Err("dns_query_too_short".to_string()));
        for (packet, offset) in [(&[0xc0][..], 0), (&[1, b'a'][..], 0), (&[0x40, 0][..], 0), (&[0][..], usize::MAX)] {
            assert!(skip_dns_name(packet, offset).is_err());
        }
    }

    proptest::proptest! {
        #[test]
        fn arbitrary_packets_do_not_panic(packet in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..4096), offset in proptest::prelude::any::<usize>()) {
            let _ = parse_dns_response(&packet, 0);
            if let Ok(end) = skip_dns_name(&packet, offset) {
                proptest::prop_assert!(end > offset && end <= packet.len());
            }
        }
        #[test]
        fn query_keeps_all_id_and_type_bits(id in proptest::prelude::any::<u16>(), record_type in proptest::prelude::any::<u16>()) {
            let query = build_dns_query_with_type("example.com.", id, record_type).unwrap();
            proptest::prop_assert_eq!(&query[..2], &id.to_be_bytes());
            proptest::prop_assert_eq!(&query[2..12], &[1, 0, 0, 1, 0, 0, 0, 0, 0, 0]);
            proptest::prop_assert_eq!(&query[12..25], b"\x07example\x03com\x00");
            proptest::prop_assert_eq!(&query[25..27], &record_type.to_be_bytes());
            proptest::prop_assert_eq!(&query[27..], &[0, 1]);
        }
    }
}
