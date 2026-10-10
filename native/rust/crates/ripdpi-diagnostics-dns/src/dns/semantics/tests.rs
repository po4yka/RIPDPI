use super::*;
use hickory_proto::op::{Edns, Query};
use hickory_proto::rr::rdata::{A, AAAA, CNAME, SOA};
use hickory_proto::rr::{Name, Record};

fn exchange(kind: RecordType) -> (Vec<u8>, Message) {
    let mut query = Message::new(1234, MessageType::Query, OpCode::Query);
    query.add_query(Query::new(Name::from_ascii("www.example.test").unwrap(), kind));
    let mut response = Message::response(1234, OpCode::Query);
    response.add_query(query.queries[0].clone());
    (query.to_vec().unwrap(), response)
}
fn parse(query: &[u8], response: &Message) -> (Vec<String>, DnsResponseSemantics) {
    parse_dns_response_semantics(query, &response.to_vec().unwrap()).unwrap()
}
fn soa(response: &mut Message, owner: &str) {
    response.add_authority(Record::from_rdata(
        Name::from_ascii(owner).unwrap(),
        90,
        RData::SOA(SOA::new(
            Name::from_ascii("ns.example.test").unwrap(),
            Name::from_ascii("hostmaster.example.test").unwrap(),
            1,
            600,
            60,
            3600,
            120,
        )),
    ));
}
#[test]
fn negatives_keep_rcode_flags_and_relevant_soa_negative_ttl() {
    for (rcode, expected) in [
        (ResponseCode::NoError, DnsResponseOutcome::Nodata),
        (ResponseCode::NXDomain, DnsResponseOutcome::Nxdomain),
        (ResponseCode::ServFail, DnsResponseOutcome::Servfail),
        (ResponseCode::Refused, DnsResponseOutcome::Refused),
        (ResponseCode::FormErr, DnsResponseOutcome::OtherRcode),
    ] {
        let (query, mut response) = exchange(RecordType::A);
        response.metadata.response_code = rcode;
        response.metadata.authoritative = true;
        response.metadata.authentic_data = true;
        soa(&mut response, "example.test");
        let (addresses, facts) = parse(&query, &response);
        assert!(addresses.is_empty());
        assert_eq!(facts.outcome, expected);
        assert_eq!(facts.rcode, Some(u16::from(rcode)));
        assert_eq!(facts.authenticated_data, Some(true));
        assert_eq!(
            facts.negative_ttl_seconds,
            matches!(expected, DnsResponseOutcome::Nodata | DnsResponseOutcome::Nxdomain).then_some(90)
        );
    }
    let (query, mut response) = exchange(RecordType::A);
    soa(&mut response, "unrelated.test");
    assert_eq!(parse(&query, &response).1.negative_ttl_seconds, None);
}
#[test]
fn binds_identity_question_family_and_answer_owner() {
    let (query, mut response) = exchange(RecordType::AAAA);
    let owner = response.queries[0].name.clone();
    response.add_answer(Record::from_rdata(owner.clone(), 17, RData::A(A("192.0.2.1".parse().unwrap()))));
    response.add_answer(Record::from_rdata(owner, 42, RData::AAAA(AAAA("2001:db8::1".parse().unwrap()))));
    let (addresses, facts) = parse(&query, &response);
    assert_eq!(addresses, ["2001:db8::1"]);
    assert_eq!(facts.query_type, "AAAA");
    assert_eq!(facts.ttl_min_seconds, Some(42));
    response.metadata.id += 1;
    assert!(parse_dns_response_semantics(&query, &response.to_vec().unwrap()).is_err());
    response.metadata.id -= 1;
    response.queries[0].query_type = RecordType::A;
    assert!(parse_dns_response_semantics(&query, &response.to_vec().unwrap()).is_err());
    let (query, mut response) = exchange(RecordType::A);
    response.add_answer(Record::from_rdata(
        Name::from_ascii("other.test").unwrap(),
        42,
        RData::A(A("192.0.2.1".parse().unwrap())),
    ));
    assert!(parse_dns_response_semantics(&query, &response.to_vec().unwrap()).is_err());
}
#[test]
fn cname_ttl_and_numeric_ede_only_are_preserved() {
    let (query, mut response) = exchange(RecordType::A);
    let alias = Name::from_ascii("alias.example.test").unwrap();
    response.add_answer(Record::from_rdata(response.queries[0].name.clone(), 5, RData::CNAME(CNAME(alias.clone()))));
    response.add_answer(Record::from_rdata(alias.clone(), 17, RData::A(A("192.0.2.1".parse().unwrap()))));
    response.add_answer(Record::from_rdata(alias, u32::MAX, RData::A(A("192.0.2.2".parse().unwrap()))));
    let mut edns = Edns::new();
    for _ in 0..20 {
        edns.options_mut().options.push((15u16.into(), EdnsOption::Unknown(15, b"\0\x0fprivate error text".to_vec())));
    }
    response.set_edns(edns);
    let (_, facts) = parse(&query, &response);
    assert_eq!(facts.cname_targets, ["alias.example.test"]);
    assert_eq!(facts.ttl_min_seconds, Some(17));
    assert_eq!(facts.ttl_max_seconds, Some(u32::MAX));
    assert_eq!(facts.extended_dns_error_codes, vec![15; 16]);
}
#[test]
fn truncated_and_incomplete_aliases_are_never_usable_answers() {
    let (query, mut response) = exchange(RecordType::A);
    response.metadata.truncation = true;
    assert_eq!(parse(&query, &response).1.outcome, DnsResponseOutcome::Truncated);
    response.metadata.truncation = false;
    response.add_answer(Record::from_rdata(
        response.queries[0].name.clone(),
        5,
        RData::CNAME(CNAME(Name::from_ascii("alias.example.test").unwrap())),
    ));
    assert_eq!(parse(&query, &response).1.outcome, DnsResponseOutcome::NotObserved);
}
#[test]
fn malformed_and_truncated_byte_prefixes_cannot_panic_or_be_answers() {
    let (query, response) = exchange(RecordType::A);
    let packet = response.to_vec().unwrap();
    for end in 0..packet.len() {
        assert!(parse_dns_response_semantics(&query, &packet[..end]).is_err());
    }
    for seed in 0..=255u8 {
        let bytes = vec![seed; usize::from(seed)];
        let _ = parse_dns_response_semantics(&query, &bytes);
    }
}

#[test]
fn cname_cycles_referrals_and_unrelated_aliases_are_not_answers() {
    use hickory_proto::rr::rdata::NS;
    let (query, mut response) = exchange(RecordType::A);
    response.add_authority(Record::from_rdata(
        Name::from_ascii("example.test").unwrap(),
        90,
        RData::NS(NS(Name::from_ascii("ns.example.test").unwrap())),
    ));
    assert_eq!(parse(&query, &response).1.outcome, DnsResponseOutcome::NotObserved);
    let owner = response.queries[0].name.clone();
    response.add_answer(Record::from_rdata(owner.clone(), 60, RData::CNAME(CNAME(owner))));
    assert!(parse_dns_response_semantics(&query, &response.to_vec().unwrap()).is_err());
}

#[test]
fn strict_runtime_validator_still_rejects_negative_and_truncated_responses() {
    let (query, mut response) = exchange(RecordType::A);
    response.metadata.response_code = ResponseCode::NXDomain;
    assert_eq!(parse(&query, &response).1.outcome, DnsResponseOutcome::Nxdomain);
    assert!(ripdpi_dns_resolver::validate_dns_response_for_query(&query, &response.to_vec().unwrap()).is_err());
    response.metadata.response_code = ResponseCode::NoError;
    response.metadata.truncation = true;
    assert_eq!(parse(&query, &response).1.outcome, DnsResponseOutcome::Truncated);
    assert!(ripdpi_dns_resolver::validate_dns_response_for_query(&query, &response.to_vec().unwrap()).is_err());
}

#[test]
fn cname_owner_cannot_also_supply_address_records() {
    for with_terminal_answer in [false, true] {
        let (query, mut response) = exchange(RecordType::A);
        let owner = response.queries[0].name.clone();
        let target = Name::from_ascii("alias.example.test").unwrap();
        response.add_answer(Record::from_rdata(owner.clone(), 5, RData::CNAME(CNAME(target.clone()))));
        response.add_answer(Record::from_rdata(owner, 1, RData::A(A("192.0.2.9".parse().unwrap()))));
        if with_terminal_answer {
            response.add_answer(Record::from_rdata(target, 30, RData::A(A("192.0.2.1".parse().unwrap()))));
        }
        assert!(parse_dns_response_semantics(&query, &response.to_vec().unwrap()).is_err());
    }
}
