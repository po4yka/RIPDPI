use std::net::{Ipv4Addr, Ipv6Addr};

use hickory_proto::op::{Edns, Message, MessageType, OpCode, Query, ResponseCode};
use hickory_proto::rr::rdata::{A, AAAA, SOA, TXT};
use hickory_proto::rr::{Name, RData, Record, RecordType};

use super::DnsCache;

fn exchange(record_type: RecordType) -> (Message, Message) {
    let mut query = Message::new(0x1234, MessageType::Query, OpCode::Query);
    query.metadata.recursion_desired = true;
    query.metadata.authentic_data = true;
    query.metadata.checking_disabled = true;
    query.add_query(Query::new(Name::from_ascii("fixture.test.").expect("host"), record_type));
    let mut edns = Edns::new();
    edns.set_max_payload(1232).set_dnssec_ok(true);
    query.edns = Some(edns.clone());

    let mut response = Message::response(0x1234, OpCode::Query);
    response.metadata.recursion_desired = true;
    response.metadata.recursion_available = true;
    response.metadata.authentic_data = true;
    response.metadata.checking_disabled = true;
    response.add_query(query.queries[0].clone());
    response.edns = Some(edns);
    (query, response)
}

fn record(data: RData) -> Record {
    Record::from_rdata(Name::from_ascii("fixture.test.").expect("host"), 60, data)
}

fn rewrite(query: &Message, upstream: &Message, ipv6_enabled: bool) -> Message {
    let mut cache = DnsCache::new(0xc612_0000, 0xfffe_0000, 8).expect("cache");
    cache.set_ipv6_enabled(ipv6_enabled);
    let result = cache
        .rewrite_response(&query.to_vec().expect("query"), &upstream.to_vec().expect("upstream"))
        .expect("rewrite");
    let response = Message::from_vec(&result.response).expect("rewritten response");
    assert_eq!(response.metadata.id, upstream.metadata.id);
    assert_eq!(response.metadata.op_code, upstream.metadata.op_code);
    assert_eq!(response.metadata.response_code, upstream.metadata.response_code);
    assert_eq!(response.metadata.recursion_desired, upstream.metadata.recursion_desired);
    assert_eq!(response.metadata.recursion_available, upstream.metadata.recursion_available);
    assert_eq!(response.metadata.checking_disabled, upstream.metadata.checking_disabled);
    assert_eq!(response.queries, upstream.queries);
    let edns = response.edns.as_ref().expect("EDNS is preserved");
    assert_eq!(edns.max_payload(), 1232);
    assert!(edns.flags().dnssec_ok);
    assert_eq!(result.response[3] & 0x20 != 0, response.metadata.authentic_data);
    response
}

#[test]
fn a_synthesis_clears_ad_and_preserves_other_metadata() {
    let (query, mut upstream) = exchange(RecordType::A);
    upstream.add_answer(record(RData::A(A(Ipv4Addr::new(203, 0, 113, 7)))));

    let response = rewrite(&query, &upstream, false);

    assert!(!response.metadata.authentic_data);
    assert_eq!(response.answers[0].ttl, 0);
    assert_eq!(response.answers[0].data, RData::A(A(Ipv4Addr::new(198, 18, 0, 0))));
}

#[test]
fn aaaa_filtering_in_each_section_clears_ad() {
    for section in 0..3 {
        let (query, mut upstream) = exchange(RecordType::AAAA);
        let address = record(RData::AAAA(AAAA(Ipv6Addr::LOCALHOST)));
        match section {
            0 => upstream.answers.push(address),
            1 => upstream.authorities.push(address),
            _ => upstream.additionals.push(address),
        }

        let response = rewrite(&query, &upstream, false);

        assert!(!response.metadata.authentic_data, "AD must be cleared after filtering section {section}");
        assert!(response.answers.is_empty());
        assert!(response.authorities.is_empty());
        assert!(response.additionals.is_empty());
    }
}

#[test]
fn unchanged_txt_response_preserves_ad() {
    let (query, mut upstream) = exchange(RecordType::TXT);
    upstream.add_answer(record(RData::TXT(TXT::new(vec!["fixture".to_string()]))));

    let response = rewrite(&query, &upstream, false);

    assert!(response.metadata.authentic_data);
    assert_eq!(response.answers, upstream.answers);
}

#[test]
fn unchanged_negative_response_preserves_ad() {
    let (query, mut upstream) = exchange(RecordType::A);
    upstream.metadata.response_code = ResponseCode::NXDomain;
    upstream.add_authority(record(RData::SOA(SOA::new(
        Name::from_ascii("ns.test.").expect("nameserver"),
        Name::from_ascii("hostmaster.test.").expect("contact"),
        1,
        60,
        60,
        3600,
        120,
    ))));

    let response = rewrite(&query, &upstream, false);

    assert!(response.metadata.authentic_data);
    assert_eq!(response.authorities, upstream.authorities);
}

#[test]
fn unchanged_routed_aaaa_response_preserves_ad() {
    let (query, mut upstream) = exchange(RecordType::AAAA);
    upstream.add_answer(record(RData::AAAA(AAAA(Ipv6Addr::LOCALHOST))));

    let response = rewrite(&query, &upstream, true);

    assert!(response.metadata.authentic_data);
    assert_eq!(response.answers, upstream.answers);
}
