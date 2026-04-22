use super::*;
use crate::{
    parse_https_service_bindings, DohBatchLookup, DohBatchRecordResponse, DohBatchRecordType, DohResolverRole,
    HttpsRrRecordType,
};
use hickory_proto::rr::rdata::https::HTTPS;
use hickory_proto::rr::rdata::svcb::{Alpn, EchConfigList, SvcParamKey, SvcParamValue, SVCB};

fn build_service_binding_response(
    query: &[u8],
    record_type: RecordType,
    svc_priority: u16,
    target_name: &str,
    svc_params: Vec<(SvcParamKey, SvcParamValue)>,
) -> Vec<u8> {
    let request = Message::from_vec(query).expect("query parses");
    let mut response = Message::new();
    response
        .set_id(request.id())
        .set_message_type(MessageType::Response)
        .set_op_code(OpCode::Query)
        .set_recursion_desired(request.recursion_desired())
        .set_recursion_available(true)
        .set_response_code(ResponseCode::NoError);
    for question in request.queries() {
        response.add_query(question.clone());
        let binding = SVCB::new(svc_priority, Name::from_ascii(target_name).expect("target name"), svc_params.clone());
        let rdata = match record_type {
            RecordType::HTTPS => RData::HTTPS(HTTPS(binding)),
            RecordType::SVCB => RData::SVCB(binding),
            other => panic!("unsupported service binding record type: {other:?}"),
        };
        response.add_answer(Record::from_rdata(question.name().clone(), 120, rdata));
    }
    response.to_vec().expect("response serializes")
}

fn build_ech_config_entry_v18(config_id: u8, public_name: &str) -> Vec<u8> {
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
    contents.extend(public_name.as_bytes());
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
