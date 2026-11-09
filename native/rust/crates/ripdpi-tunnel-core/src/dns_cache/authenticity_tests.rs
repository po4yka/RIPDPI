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

// Hand-encoded RFC 9460 wire bytes serve as the independent oracle below.
const FIXTURE_NAME: &[u8] = b"\x07fixture\x04test\x00";
const TARGET: &[u8] = b"\x03svc\x07fixture\x04test\x00";
const TYPE_A: u16 = 1;
const TYPE_SVCB: u16 = 64;
const TYPE_HTTPS: u16 = 65;
const KEY_MANDATORY: u16 = 0;
const KEY_ALPN: u16 = 1;
const KEY_IPV4: u16 = 4;
const KEY_IPV6: u16 = 6;

fn param(key: u16, value: &[u8]) -> Vec<u8> {
    let mut out = key.to_be_bytes().to_vec();
    out.extend_from_slice(&(value.len() as u16).to_be_bytes());
    out.extend_from_slice(value);
    out
}

fn svcb_rdata(priority: u16, params: &[Vec<u8>]) -> Vec<u8> {
    let mut out = priority.to_be_bytes().to_vec();
    out.extend_from_slice(TARGET);
    for p in params {
        out.extend_from_slice(p);
    }
    out
}

fn alpn() -> Vec<u8> {
    param(KEY_ALPN, b"\x02h2")
}

fn ipv4_hint() -> Vec<u8> {
    param(KEY_IPV4, &[203, 0, 113, 10])
}

fn ipv6_hint() -> Vec<u8> {
    param(KEY_IPV6, &[0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10])
}

/// Response for `fixture.test.` with AD set; records are `(type, rdata)`.
fn wire_exchange(qtype: u16, answers: &[(u16, Vec<u8>)], additionals: &[(u16, Vec<u8>)]) -> (Vec<u8>, Vec<u8>) {
    let mut question = FIXTURE_NAME.to_vec();
    question.extend_from_slice(&[(qtype >> 8) as u8, qtype as u8, 0, 1]);
    let mut query = vec![0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
    query.extend_from_slice(&question);

    let mut upstream = vec![0x12, 0x34, 0x81, 0xa0, 0, 1, 0, answers.len() as u8, 0, 0, 0, additionals.len() as u8];
    upstream.extend_from_slice(&question);
    for (rtype, rdata) in answers.iter().chain(additionals) {
        upstream.extend_from_slice(FIXTURE_NAME);
        upstream.extend_from_slice(&[(rtype >> 8) as u8, *rtype as u8, 0, 1, 0, 0, 0, 60]);
        upstream.extend_from_slice(&(rdata.len() as u16).to_be_bytes());
        upstream.extend_from_slice(rdata);
    }
    (query, upstream)
}

fn rewrite_wire(ipv6_enabled: bool, query: &[u8], upstream: &[u8]) -> (Vec<u8>, Message) {
    let mut cache = DnsCache::new(0xc612_0000, 0xfffe_0000, 8).expect("cache");
    cache.set_ipv6_enabled(ipv6_enabled);
    let result = cache.rewrite_response(query, upstream).expect("rewrite");
    let message = Message::from_vec(&result.response).expect("rewritten response decodes");
    assert!(!message.metadata.authentic_data, "AD must be cleared after a change");
    (result.response, message)
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack.windows(needle.len()).any(|window| window == needle)
}

fn param_keys(record: &Record) -> Vec<u16> {
    let svcb = match &record.data {
        RData::HTTPS(https) => &https.0,
        RData::SVCB(svcb) => svcb,
        other => panic!("SVCB-family record expected, got {other:?}"),
    };
    svcb.svc_params.iter().map(|(key, _)| u16::from(*key)).collect()
}

#[test]
fn https_hints_are_stripped_when_ipv6_is_disabled() {
    let rdata = svcb_rdata(1, &[alpn(), ipv4_hint(), ipv6_hint()]);
    let (query, upstream) = wire_exchange(TYPE_HTTPS, &[(TYPE_HTTPS, rdata)], &[]);

    let (wire, response) = rewrite_wire(false, &query, &upstream);

    assert_eq!(param_keys(&response.answers[0]), vec![KEY_ALPN]);
    // Priority, target, and alpn bytes survive unchanged.
    assert!(contains(&wire, &svcb_rdata(1, &[alpn()])));
}

#[test]
fn https_keeps_ipv6_hint_but_drops_ipv4_hint_when_ipv6_is_enabled() {
    let rdata = svcb_rdata(1, &[alpn(), ipv4_hint(), ipv6_hint()]);
    let (query, upstream) = wire_exchange(TYPE_HTTPS, &[(TYPE_HTTPS, rdata)], &[]);

    let (wire, response) = rewrite_wire(true, &query, &upstream);

    assert_eq!(param_keys(&response.answers[0]), vec![KEY_ALPN, KEY_IPV6]);
    assert!(contains(&wire, &svcb_rdata(1, &[alpn(), ipv6_hint()])));
}

#[test]
fn mandatory_list_without_removed_keys_keeps_the_record() {
    let mandatory = param(KEY_MANDATORY, &[0, 1]);
    let rdata = svcb_rdata(1, &[mandatory.clone(), alpn(), ipv4_hint()]);
    let (query, upstream) = wire_exchange(TYPE_HTTPS, &[(TYPE_HTTPS, rdata)], &[]);

    let (wire, response) = rewrite_wire(true, &query, &upstream);

    assert_eq!(param_keys(&response.answers[0]), vec![KEY_MANDATORY, KEY_ALPN]);
    assert!(contains(&wire, &svcb_rdata(1, &[mandatory, alpn()])));
}

#[test]
fn record_with_a_removed_hint_in_mandatory_is_dropped() {
    let mandatory = param(KEY_MANDATORY, &[0, 4]);
    let dropped = svcb_rdata(1, &[mandatory, alpn(), ipv4_hint()]);
    let survivor = svcb_rdata(2, &[alpn()]);
    let (query, upstream) = wire_exchange(TYPE_HTTPS, &[(TYPE_HTTPS, dropped), (TYPE_HTTPS, survivor.clone())], &[]);

    let (wire, response) = rewrite_wire(true, &query, &upstream);

    assert_eq!(response.answers.len(), 1);
    assert!(contains(&wire, &survivor));
}

#[test]
fn svcb_additional_ipv6_hint_is_stripped_when_ipv6_is_disabled() {
    let (query, upstream) =
        wire_exchange(TYPE_A, &[(TYPE_A, vec![203, 0, 113, 7])], &[(TYPE_SVCB, svcb_rdata(1, &[alpn(), ipv6_hint()]))]);

    let (wire, response) = rewrite_wire(false, &query, &upstream);

    assert_eq!(response.additionals.len(), 1);
    assert_eq!(param_keys(&response.additionals[0]), vec![KEY_ALPN]);
    assert!(contains(&wire, &svcb_rdata(1, &[alpn()])));
}

#[test]
fn service_record_left_without_params_still_encodes() {
    let (query, upstream) = wire_exchange(TYPE_HTTPS, &[(TYPE_HTTPS, svcb_rdata(1, &[ipv4_hint()]))], &[]);

    let (wire, response) = rewrite_wire(true, &query, &upstream);

    assert!(param_keys(&response.answers[0]).is_empty());
    assert!(contains(&wire, &svcb_rdata(1, &[])));
}

#[test]
fn additional_a_records_are_removed() {
    let (query, upstream) =
        wire_exchange(TYPE_A, &[(TYPE_A, vec![203, 0, 113, 7])], &[(TYPE_A, vec![203, 0, 113, 99])]);

    let (_, response) = rewrite_wire(true, &query, &upstream);

    assert_eq!(response.answers.len(), 1);
    assert!(response.additionals.is_empty());
}
