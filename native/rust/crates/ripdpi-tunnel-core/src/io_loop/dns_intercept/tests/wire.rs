use hickory_proto::op::{Message, ResponseCode};
use hickory_proto::rr::DNSClass;

use super::super::{dns_query_name, parse_dns_query};

#[test]
fn dns_query_name_extracts_labels_and_rejects_compression_pointers() {
    let plain_query = [
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, b'w', b'w', b'w', 0x07, b'e',
        b'x', b'a', b'm', b'p', b'l', b'e', 0x03, b'c', b'o', b'm', 0x00, 0x00, 0x01, 0x00, 0x01,
    ];
    let compressed_query = [0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xc0, 0x0c];

    assert_eq!(dns_query_name(&plain_query), Some("www.example.com".to_string()));
    assert_eq!(dns_query_name(&compressed_query), None);
}

#[test]
fn canonical_parser_requires_exactly_one_in_question() {
    let one = super::support::build_query("one.example");
    let mut none = one.clone();
    none[4..6].copy_from_slice(&0u16.to_be_bytes());
    let mut two = one.clone();
    two[4..6].copy_from_slice(&2u16.to_be_bytes());
    two.extend_from_slice(&one[12..]);
    let mut non_in = Message::from_vec(&one).expect("query");
    non_in.queries[0].set_query_class(DNSClass::CH);

    assert!(parse_dns_query(&one).is_some());
    assert!(parse_dns_query(&none).is_none());
    assert!(parse_dns_query(&two).is_none());
    assert!(parse_dns_query(&non_in.to_vec().expect("encode")).is_none());
}

#[test]
fn refused_response_preserves_id_and_question() {
    let query = super::support::build_query("blocked.example");
    let parsed = parse_dns_query(&query).expect("query");
    let response = Message::from_vec(&parsed.refused_response().expect("refused response")).expect("response");

    assert_eq!(response.metadata.id, 0x1234);
    assert_eq!(response.metadata.response_code, ResponseCode::Refused);
    assert_eq!(response.queries.len(), 1);
    assert_eq!(response.queries[0].name.to_utf8(), "blocked.example.");
    assert!(response.answers.is_empty());
}
