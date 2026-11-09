use super::*;

const MALFORMED_HTTPS_TSIG_RESPONSE: &[u8] = &[
    0x28, 0x0a, 0x5c, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46, 0xda, 0x00, 0x00, 0xfa, 0x00, 0x00, 0x00, 0x3e,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x47, 0x8c, 0x30, 0x00, 0x2e,
];
use crate::{
    DohBatchLookup, DohBatchRecordResponse, DohBatchRecordType, DohResolverRole, HttpsRrRecordType,
    HttpsServiceSelection, parse_ech_config_list, parse_https_service_bindings, select_https_service_bindings,
};
use hickory_proto::rr::rdata::https::HTTPS;
use hickory_proto::rr::rdata::svcb::{Alpn, EchConfigList, Mandatory, SVCB, SvcParamKey, SvcParamValue, Unknown};

fn build_service_binding_response(
    query: &[u8],
    record_type: RecordType,
    svc_priority: u16,
    target_name: &str,
    svc_params: Vec<(SvcParamKey, SvcParamValue)>,
) -> Vec<u8> {
    let request = Message::from_vec(query).expect("query parses");
    let mut response = Message::response(request.metadata.id, OpCode::Query);
    response.metadata.recursion_desired = request.metadata.recursion_desired;
    response.metadata.recursion_available = true;
    response.metadata.response_code = ResponseCode::NoError;
    for question in &request.queries {
        response.add_query(question.clone());
        let binding = SVCB::new(svc_priority, Name::from_ascii(target_name).expect("target name"), svc_params.clone());
        let rdata = match record_type {
            RecordType::HTTPS => RData::HTTPS(HTTPS(binding)),
            RecordType::SVCB => RData::SVCB(binding),
            other => panic!("unsupported service binding record type: {other:?}"),
        };
        response.add_answer(Record::from_rdata(question.name.clone(), 120, rdata));
    }
    response.to_vec().expect("response serializes")
}

fn build_ech_config_entry_v18(config_id: u8, public_name: impl AsRef<[u8]>) -> Vec<u8> {
    let public_name = public_name.as_ref();
    let mut contents = Vec::new();
    contents.push(config_id);
    contents.extend(0x0020u16.to_be_bytes());
    contents.extend(4u16.to_be_bytes());
    contents.extend([0x01, 0x02, 0x03, 0x04]);
    contents.extend(4u16.to_be_bytes());
    contents.extend(0x0001u16.to_be_bytes());
    contents.extend(0x0001u16.to_be_bytes());
    contents.push(32);
    contents.push(public_name.len() as u8);
    contents.extend(public_name);
    contents.extend(0u16.to_be_bytes());

    let mut entry = Vec::new();
    entry.extend(0xfe0du16.to_be_bytes());
    entry.extend((contents.len() as u16).to_be_bytes());
    entry.extend(contents);
    entry
}

fn build_unknown_ech_config_entry(version: u16, payload: &[u8]) -> Vec<u8> {
    let mut entry = Vec::new();
    entry.extend(version.to_be_bytes());
    entry.extend((payload.len() as u16).to_be_bytes());
    entry.extend(payload);
    entry
}

fn build_ech_config_list(entries: Vec<Vec<u8>>) -> Vec<u8> {
    let mut payload = Vec::new();
    for entry in entries {
        payload.extend(entry);
    }
    let mut list = Vec::new();
    list.extend((payload.len() as u16).to_be_bytes());
    list.extend(payload);
    list
}

#[test]
fn parse_https_service_bindings_extracts_alpn_and_ech_metadata() {
    let query = build_query_for_type("fixture.test", RecordType::HTTPS);
    let ech_bytes = build_ech_config_list(vec![build_ech_config_entry_v18(7, "public.example")]);
    let response = build_service_binding_response(
        &query,
        RecordType::HTTPS,
        1,
        "svc.fixture.test.",
        vec![
            (SvcParamKey::Alpn, SvcParamValue::Alpn(Alpn(vec!["h2".to_string(), "http/1.1".to_string()]))),
            (SvcParamKey::Port, SvcParamValue::Port(8443)),
            (SvcParamKey::EchConfigList, SvcParamValue::EchConfigList(EchConfigList(ech_bytes.clone()))),
        ],
    );

    let records = parse_https_service_bindings(&response).expect("service bindings parse");

    assert_eq!(records.len(), 1);
    let record = &records[0];
    assert_eq!(record.record_type, HttpsRrRecordType::Https);
    assert_eq!(record.service_priority, 1);
    assert_eq!(record.target_name, "svc.fixture.test.");
    assert_eq!(record.port, Some(8443));
    assert_eq!(record.alpn, vec!["h2".to_string(), "http/1.1".to_string()]);
    assert!(record.ech_capable);
    let ech = record.ech_config.as_ref().expect("ECH config metadata");
    assert_eq!(ech.raw_list_bytes, ech_bytes);
    assert_eq!(ech.configs.len(), 1);
    assert_eq!(ech.configs[0].config_id, Some(7));
    assert_eq!(ech.configs[0].public_name.as_deref(), Some("public.example"));
    assert_eq!(ech.configs[0].public_key_len, Some(4));
    assert_eq!(ech.configs[0].cipher_suites.len(), 1);
}

/// An HTTPS answer for `fixture.test.` with `answers` (owner, priority,
/// target, ECH public name) and `additionals` in the additional section.
fn https_answer(
    answers: &[(&str, u16, &str, Option<&str>)],
    additionals: &[(&str, u16, &str, Option<&str>)],
) -> Vec<u8> {
    let query = build_query_for_type("fixture.test", RecordType::HTTPS);
    let request = Message::from_vec(&query).expect("query parses");
    let mut response = Message::response(request.metadata.id, OpCode::Query);
    response.metadata.response_code = ResponseCode::NoError;
    response.add_query(request.queries[0].clone());
    let record = |(owner, priority, target, public_name): &(&str, u16, &str, Option<&str>)| {
        let params = public_name
            .map(|name| {
                let list = build_ech_config_list(vec![build_ech_config_entry_v18(1, name)]);
                vec![(SvcParamKey::EchConfigList, SvcParamValue::EchConfigList(EchConfigList(list)))]
            })
            .unwrap_or_default();
        let binding = SVCB::new(*priority, Name::from_ascii(target).expect("target"), params);
        Record::from_rdata(Name::from_ascii(owner).expect("owner"), 120, RData::HTTPS(HTTPS(binding)))
    };
    for answer in answers {
        response.add_answer(record(answer));
    }
    for additional in additionals {
        response.add_additional(record(additional));
    }
    response.to_vec().expect("response serializes")
}

fn ech_public_names(selection: HttpsServiceSelection) -> Vec<String> {
    let HttpsServiceSelection::Service(bindings) = selection else {
        panic!("expected ServiceMode bindings, got {selection:?}");
    };
    bindings.into_iter().filter_map(|binding| binding.ech_config?.configs.into_iter().next()?.public_name).collect()
}

#[test]
fn select_https_service_bindings_orders_by_priority_and_ignores_foreign_owners() {
    let response = https_answer(
        &[
            ("other.test.", 1, ".", Some("foreign-owner.example")),
            ("fixture.test.", 2, ".", Some("second.example")),
            ("fixture.test.", 1, ".", Some("first.example")),
        ],
        &[("fixture.test.", 1, ".", Some("additional.example"))],
    );

    let selection = select_https_service_bindings(&response).expect("bindings select");

    assert_eq!(ech_public_names(selection), vec!["first.example".to_string(), "second.example".to_string()]);
}

fn select_ech_with_preferred_params(mut params: Vec<(SvcParamKey, SvcParamValue)>) -> Vec<String> {
    params.push((
        SvcParamKey::EchConfigList,
        SvcParamValue::EchConfigList(EchConfigList(build_ech_config_list(vec![build_ech_config_entry_v18(
            1,
            "preferred.example",
        )]))),
    ));
    params.sort_by_key(|(key, _)| *key);
    let query = build_query_for_type("fixture.test", RecordType::HTTPS);
    let preferred = build_service_binding_response(&query, RecordType::HTTPS, 1, ".", params);
    let mut response = Message::from_vec(&preferred).expect("preferred response parses");
    let alternative = https_answer(&[("fixture.test.", 2, ".", Some("alternative.example"))], &[]);
    response.answers.extend(Message::from_vec(&alternative).expect("alternative response parses").answers);
    ech_public_names(select_https_service_bindings(&response.to_vec().expect("encodes")).expect("bindings select"))
}

#[test]
fn select_https_service_bindings_ignores_unknown_mandatory_key() {
    let selected = select_ech_with_preferred_params(vec![
        (SvcParamKey::Mandatory, SvcParamValue::Mandatory(Mandatory(vec![SvcParamKey::Key(65400)]))),
        (SvcParamKey::Key(65400), SvcParamValue::Unknown(Unknown(vec![1]))),
    ]);

    assert_eq!(selected, vec!["alternative.example"]);
}

#[test]
fn select_https_service_bindings_keeps_known_mandatory_and_unknown_optional_keys() {
    let selected = select_ech_with_preferred_params(vec![
        (SvcParamKey::Mandatory, SvcParamValue::Mandatory(Mandatory(vec![SvcParamKey::EchConfigList]))),
        (SvcParamKey::Key(65400), SvcParamValue::Unknown(Unknown(vec![1]))),
    ]);

    assert_eq!(selected, vec!["preferred.example", "alternative.example"]);
}

#[test]
fn select_https_service_bindings_ignores_inconsistent_mandatory_keys() {
    for mandatory in [
        vec![SvcParamKey::Port],
        vec![SvcParamKey::Mandatory],
        vec![SvcParamKey::EchConfigList, SvcParamKey::EchConfigList],
    ] {
        let selected = select_ech_with_preferred_params(vec![(
            SvcParamKey::Mandatory,
            SvcParamValue::Mandatory(Mandatory(mandatory.clone())),
        )]);
        assert_eq!(selected, vec!["alternative.example"], "mandatory: {mandatory:?}");
    }
}

#[test]
fn select_https_service_bindings_requires_alpn_for_no_default_alpn() {
    assert_eq!(
        select_ech_with_preferred_params(vec![(SvcParamKey::NoDefaultAlpn, SvcParamValue::NoDefaultAlpn)]),
        vec!["alternative.example"]
    );
    assert_eq!(
        select_ech_with_preferred_params(vec![
            (SvcParamKey::Alpn, SvcParamValue::Alpn(Alpn(vec!["h2".to_string()]))),
            (SvcParamKey::NoDefaultAlpn, SvcParamValue::NoDefaultAlpn),
        ]),
        vec!["preferred.example", "alternative.example"]
    );
}

#[test]
fn select_https_service_bindings_follows_the_cname_chain() {
    let mut response =
        Message::from_vec(&https_answer(&[("cdn.test.", 1, ".", Some("cdn.example"))], &[])).expect("response parses");
    response.answers.insert(
        0,
        Record::from_rdata(
            Name::from_ascii("fixture.test.").expect("owner"),
            120,
            RData::CNAME(CNAME(Name::from_ascii("cdn.test.").expect("target"))),
        ),
    );

    let selection = select_https_service_bindings(&response.to_vec().expect("encodes")).expect("bindings select");

    assert_eq!(ech_public_names(selection), vec!["cdn.example".to_string()]);
}

#[test]
fn select_https_service_bindings_reports_alias_mode_over_service_mode() {
    let response = https_answer(
        &[("fixture.test.", 1, ".", Some("ignored.example")), ("fixture.test.", 0, "svc.example.net.", None)],
        &[],
    );

    assert_eq!(
        select_https_service_bindings(&response).expect("bindings select"),
        HttpsServiceSelection::Alias("svc.example.net.".to_string())
    );
}

#[test]
fn parse_svcb_service_bindings_extracts_ech_metadata() {
    let query = build_query_for_type("fixture.test", RecordType::SVCB);
    let response = build_service_binding_response(
        &query,
        RecordType::SVCB,
        3,
        "svc.fixture.test.",
        vec![
            (SvcParamKey::NoDefaultAlpn, SvcParamValue::NoDefaultAlpn),
            (
                SvcParamKey::EchConfigList,
                SvcParamValue::EchConfigList(EchConfigList(build_ech_config_list(vec![build_ech_config_entry_v18(
                    3,
                    "svc.example",
                )]))),
            ),
        ],
    );

    let records = parse_https_service_bindings(&response).expect("service bindings parse");

    assert_eq!(records.len(), 1);
    assert_eq!(records[0].record_type, HttpsRrRecordType::Svcb);
    assert!(records[0].no_default_alpn);
    assert!(records[0].ech_capable);
}

#[test]
fn parse_https_service_bindings_rejects_empty_ech_payload() {
    let query = build_query_for_type("fixture.test", RecordType::HTTPS);
    let response = build_service_binding_response(
        &query,
        RecordType::HTTPS,
        1,
        "svc.fixture.test.",
        vec![(SvcParamKey::EchConfigList, SvcParamValue::EchConfigList(EchConfigList(Vec::new())))],
    );

    let error = parse_https_service_bindings(&response).expect_err("empty ECH payload must fail");

    assert!(error.to_string().contains("malformed") || error.to_string().contains("empty"));
}

#[test]
fn parse_https_service_bindings_rejects_truncated_ech_payload() {
    let query = build_query_for_type("fixture.test", RecordType::HTTPS);
    let response = build_service_binding_response(
        &query,
        RecordType::HTTPS,
        1,
        "svc.fixture.test.",
        vec![(
            SvcParamKey::EchConfigList,
            SvcParamValue::EchConfigList(EchConfigList(vec![0x00, 0x08, 0xfe, 0x0d, 0x00, 0x10])),
        )],
    );

    let error = parse_https_service_bindings(&response).expect_err("truncated ECH payload must fail");

    assert!(error.to_string().contains("truncated"));
}

#[test]
fn parse_https_service_bindings_rejects_malformed_tsig_without_panicking() {
    assert!(parse_https_service_bindings(MALFORMED_HTTPS_TSIG_RESPONSE).is_err());
}

#[test]
fn parse_https_service_bindings_preserves_multi_config_ech_lists() {
    let query = build_query_for_type("fixture.test", RecordType::HTTPS);
    let ech_bytes = build_ech_config_list(vec![
        build_unknown_ech_config_entry(0xfe0c, &[0xaa, 0xbb, 0xcc]),
        build_ech_config_entry_v18(11, "multi.example"),
    ]);
    let response = build_service_binding_response(
        &query,
        RecordType::HTTPS,
        2,
        "svc.fixture.test.",
        vec![(SvcParamKey::EchConfigList, SvcParamValue::EchConfigList(EchConfigList(ech_bytes.clone())))],
    );

    let records = parse_https_service_bindings(&response).expect("service bindings parse");
    let ech = records[0].ech_config.as_ref().expect("ECH config metadata");

    assert_eq!(ech.raw_list_bytes, ech_bytes);
    assert_eq!(ech.configs.len(), 2);
    assert_eq!(ech.configs[0].version, 0xfe0c);
    assert_eq!(ech.configs[0].config_id, None);
    assert_eq!(ech.configs[1].version, 0xfe0d);
    assert_eq!(ech.configs[1].config_id, Some(11));
}

#[test]
fn doh_batch_lookup_exposes_https_service_bindings_and_ech_capability() {
    let https_query = build_query_for_type("fixture.test", RecordType::HTTPS);
    let svcb_query = build_query_for_type("fixture.test", RecordType::SVCB);
    let ech_bytes = build_ech_config_list(vec![build_ech_config_entry_v18(5, "svc.example")]);
    let lookup = DohBatchLookup {
        domain: "fixture.test".to_string(),
        resolver_role: DohResolverRole::Primary,
        endpoint_label: "https://fixture.test/dns-query".to_string(),
        records: vec![
            DohBatchRecordResponse {
                record_type: DohBatchRecordType::Https,
                response_bytes: build_service_binding_response(
                    &https_query,
                    RecordType::HTTPS,
                    1,
                    "svc.fixture.test.",
                    vec![(SvcParamKey::EchConfigList, SvcParamValue::EchConfigList(EchConfigList(ech_bytes.clone())))],
                ),
                min_ttl_secs: Some(120),
            },
            DohBatchRecordResponse {
                record_type: DohBatchRecordType::Svcb,
                response_bytes: build_service_binding_response(
                    &svcb_query,
                    RecordType::SVCB,
                    2,
                    "svc2.fixture.test.",
                    vec![(SvcParamKey::Alpn, SvcParamValue::Alpn(Alpn(vec!["h3".to_string()])))],
                ),
                min_ttl_secs: Some(120),
            },
        ],
        cache_ttl_secs: Some(120),
    };

    let bindings = lookup.https_service_bindings().expect("batch bindings parse");

    assert_eq!(bindings.len(), 2);
    assert!(lookup.ech_capable().expect("ech capability"));
    assert_eq!(bindings[0].record_type, HttpsRrRecordType::Https);
    assert_eq!(bindings[1].record_type, HttpsRrRecordType::Svcb);
    assert_eq!(bindings[1].alpn, vec!["h3".to_string()]);
}

// Hand-built wire bytes for the DNS-08 regressions. Expected values come from
// RFC 9460 2.2, 7.2, 7.3 and RFC 9849 4, not from the parser under test.
mod wire_fixture {
    pub fn name(n: &str) -> Vec<u8> {
        let mut out = Vec::new();
        for label in n.trim_end_matches('.').split('.').filter(|l| !l.is_empty()) {
            out.push(label.len() as u8);
            out.extend(label.as_bytes());
        }
        out.push(0);
        out
    }

    pub fn param(key: u16, value: &[u8]) -> Vec<u8> {
        let mut out = key.to_be_bytes().to_vec();
        out.extend((value.len() as u16).to_be_bytes());
        out.extend(value);
        out
    }

    /// An HTTPS answer for `example.com` with one ServiceMode record at priority 1, target ".".
    pub fn answer_with_params(params: &[Vec<u8>]) -> Vec<u8> {
        let mut rdata = 1u16.to_be_bytes().to_vec();
        rdata.push(0);
        for p in params {
            rdata.extend(p);
        }
        let mut p = vec![0x12, 0x34, 0x81, 0x80, 0, 1, 0, 1, 0, 0, 0, 0];
        p.extend(name("example.com"));
        p.extend(65u16.to_be_bytes());
        p.extend(1u16.to_be_bytes());
        p.extend([0xc0, 0x0c]);
        p.extend(65u16.to_be_bytes());
        p.extend(1u16.to_be_bytes());
        p.extend(300u32.to_be_bytes());
        p.extend((rdata.len() as u16).to_be_bytes());
        p.extend(rdata);
        p
    }
}

#[test]
fn invalid_public_name_drops_only_that_config() {
    let good = build_ech_config_entry_v18(1, "ech.example");
    let list = build_ech_config_list(vec![good.clone(), build_ech_config_entry_v18(2, "bad..example")]);
    let packet = wire_fixture::answer_with_params(&[wire_fixture::param(5, &list)]);

    let HttpsServiceSelection::Service(bindings) = select_https_service_bindings(&packet).expect("RRset stays valid")
    else {
        panic!("expected ServiceMode");
    };
    let ech = bindings[0].ech_config.as_ref().expect("ech kept");

    assert_eq!(ech.configs.len(), 2);
    assert_eq!(ech.configs[0].public_name.as_deref(), Some("ech.example"));
    assert_eq!(ech.configs[1].public_name, None);
    assert_eq!(ech.raw_list_bytes, list);
    assert_eq!(ech.supported_config_list(), Some(build_ech_config_list(vec![good])));
}

#[test]
fn public_name_acceptance_matches_boringssl() {
    let supported = |public_name: &[u8]| {
        let list = build_ech_config_list(vec![build_ech_config_entry_v18(1, public_name)]);
        parse_ech_config_list(&list).expect("structure is valid").supported_config_list().is_some()
    };
    let label63 = "abcdefhijklmnopqrstuvwxyz-ABCDEFGHIJKLMNOPQRSTUVWXYZ-0123456789";
    let name254 = format!("{0}.{0}.{0}.{1}", "a".repeat(63), "a".repeat(62));
    assert_eq!(name254.len(), 254);

    // Oracle: BoringSSL ssl_internal_test.cc, TEST(SSLTest, ECHPublicName).
    let table: Vec<(&[u8], bool)> = vec![
        (b"", false),
        (b"example.com", true),
        (b".example.com", false),
        (b"example.com.", false),
        (b"example..com", false),
        (b"www.-example.com", false),
        (b"www.example-.com", false),
        (b"no_underscores.example", false),
        (b"invalid_chars.\x01.example", false),
        (b"invalid_chars.\xff.example", false),
        (b"test\0", false),
        (label63.as_bytes(), true),
        (b"abcdefhijklmnopqrstuvwxyz-ABCDEFGHIJKLMNOPQRSTUVWXYZ-01234567899", false),
        (b"127.0.0.1", false),
        (b"example.1", false),
        (b"example.01", false),
        (b"example.0x01", false),
        (b"example.0X01", false),
        (b"example.123456789000000000000000", false),
        (b"example.012345678900000000000000", false),
        (b"example.0x123456789abcdefABCDEF0", false),
        (b"example.0x0123456789abcdefABCDEF", false),
        (b"example.1234567890a", true),
        (b"example.01234567890a", true),
        (b"example.0x123456789abcdefg", true),
        (b"1", false),
        (b"01", false),
        (b"0x01", false),
        (b"0X01", false),
        (b"1.", false),
        (b"01.", false),
        (b"0x01.", false),
        (b"0X01.", false),
    ];
    for (name, expected) in table {
        assert_eq!(supported(name), expected, "{:?}", String::from_utf8_lossy(name));
    }
    // Valid LDH, but longer than the 253 bytes rustls accepts.
    assert!(!supported(name254.as_bytes()));
    assert!(supported(&name254.as_bytes()[..253]));

    let good = build_ech_config_entry_v18(1, "ech.example");
    let list = build_ech_config_list(vec![good.clone(), build_ech_config_entry_v18(2, name254.as_bytes())]);
    assert_eq!(
        parse_ech_config_list(&list).expect("parses").supported_config_list(),
        Some(build_ech_config_list(vec![good]))
    );
}

#[test]
fn malformed_record_for_another_name_does_not_reject_the_answer() {
    let mut packet = wire_fixture::answer_with_params(&[wire_fixture::param(3, &[0x20, 0xfb])]);
    // One additional record: SVCB for other.example with a 3-byte port.
    packet[10..12].copy_from_slice(&1u16.to_be_bytes());
    let mut rdata = 1u16.to_be_bytes().to_vec();
    rdata.push(0);
    rdata.extend(wire_fixture::param(3, &[1, 2, 3]));
    packet.extend(wire_fixture::name("other.example"));
    packet.extend(64u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    packet.extend(300u32.to_be_bytes());
    packet.extend((rdata.len() as u16).to_be_bytes());
    packet.extend(rdata);

    let HttpsServiceSelection::Service(bindings) = select_https_service_bindings(&packet).expect("answer survives")
    else {
        panic!("expected ServiceMode");
    };

    assert_eq!(bindings.len(), 1);
    assert_eq!(bindings[0].port, Some(8443));
    assert!(parse_https_service_bindings(&packet).is_err());
}

#[test]
fn empty_rdata_https_record_is_rejected_by_the_decoder() {
    let mut packet = wire_fixture::answer_with_params(&[]);
    // Replace the RDATA (priority + root target = 3 bytes) with an empty one.
    packet.truncate(packet.len() - 3);
    let len_at = packet.len() - 2;
    packet[len_at..].copy_from_slice(&0u16.to_be_bytes());

    // Hickory rejects the message before the wire scan runs, so the scan never reads past it.
    assert!(select_https_service_bindings(&packet).is_err());
}

#[test]
fn list_with_no_supported_entry_has_no_supported_list() {
    let list = build_ech_config_list(vec![build_unknown_ech_config_entry(0xfe0e, &[1, 2, 3])]);

    assert_eq!(parse_ech_config_list(&list).expect("parses").supported_config_list(), None);
}

#[test]
fn malformed_service_params_reject_the_rrset() {
    for params in [
        vec![wire_fixture::param(3, &[0x01, 0xbb, 0xff])],
        vec![wire_fixture::param(3, &[0x01])],
        vec![wire_fixture::param(4, &[])],
        vec![wire_fixture::param(6, &[])],
        vec![wire_fixture::param(4, &[1, 2, 3])],
    ] {
        let packet = wire_fixture::answer_with_params(&params);
        assert!(select_https_service_bindings(&packet).is_err(), "{params:?}");
        assert!(parse_https_service_bindings(&packet).is_err(), "{params:?}");
    }
}

#[test]
fn well_formed_service_params_are_accepted() {
    let packet = wire_fixture::answer_with_params(&[
        wire_fixture::param(3, &[0x20, 0xfb]),
        wire_fixture::param(4, &[192, 0, 2, 1]),
        wire_fixture::param(6, &[0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]),
    ]);

    let records = parse_https_service_bindings(&packet).expect("parses");

    assert_eq!(records[0].port, Some(8443));
    assert_eq!(records[0].ipv4_hints.len(), 1);
    assert_eq!(records[0].ipv6_hints.len(), 1);
    assert!(select_https_service_bindings(&packet).is_ok());
}
