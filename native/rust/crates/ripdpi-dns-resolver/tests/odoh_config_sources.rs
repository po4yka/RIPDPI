use hickory_proto::op::{Message, MessageType, OpCode, Query, ResponseCode};
use hickory_proto::rr::rdata::https::HTTPS;
use hickory_proto::rr::rdata::svcb::{SVCB, SvcParamKey, SvcParamValue, Unknown};
use hickory_proto::rr::{Name, RData, Record, RecordType};
use ripdpi_dns_resolver::{
    OdohConfigLookupSecurity, OdohConfigSource, OdohConfigSourceKind, OdohError, parse_https_service_bindings,
};

const ODOHCONFIGS: &str =
    "002c000100280020000100010020c6a793bedbd601c25970b1cc46bea80fdb1a8ec51540d79e4f9f17b8baa9da33";
const KEY_ID: &str = "9265d14d640ff991b31892f36326ab601ea84d61964fc7a9c7f981a5313e58b9";
const ODOHCONFIG_SVCB_KEY: u16 = 32769;

#[test]
fn odoh_bundled_config_source_resolves_key_id_and_freshness() {
    let resolved =
        OdohConfigSource::Bundled { configs_wire: odoh_configs(), retrieved_at_secs: 1_000, ttl_secs: 86_400 }
            .resolve_at(1_200)
            .expect("bundled ODoH config resolves");

    assert_eq!(resolved.source_kind(), OdohConfigSourceKind::Bundled);
    assert_eq!(resolved.target_config().key_id(), hex_bytes(KEY_ID).as_slice());
    assert_eq!(resolved.expires_at_secs(), 87_400);
    assert!(resolved.is_fresh_at(87_399));
    assert!(!resolved.is_fresh_at(87_400));
}

#[test]
fn odoh_config_source_rejects_stale_config_material() {
    let error = OdohConfigSource::Bundled { configs_wire: odoh_configs(), retrieved_at_secs: 1_000, ttl_secs: 60 }
        .resolve_at(1_061)
        .expect_err("stale ODoH config must be rejected");

    assert!(matches!(error, OdohError::StaleConfig { .. }));
}

#[test]
fn odoh_multiple_configs_selects_supported_default_suite_after_unsupported_entry() {
    let resolved = OdohConfigSource::CustomBytes {
        configs_wire: unsupported_then_supported_configs(),
        retrieved_at_secs: 10,
        ttl_secs: 60,
    }
    .resolve_at(10)
    .expect("supported ODoH config is selected");

    assert_eq!(resolved.source_kind(), OdohConfigSourceKind::CustomBytes);
    assert_eq!(resolved.target_config().key_id(), hex_bytes(KEY_ID).as_slice());
}

#[test]
fn odoh_https_svcb_config_source_extracts_odohconfig_param() {
    let response = build_https_response_with_odoh_config(120, odoh_configs());
    let bindings = parse_https_service_bindings(&response).expect("HTTPS RR parses");
    assert_eq!(bindings.len(), 1);
    assert_eq!(bindings[0].odoh_config.as_deref(), Some(odoh_configs().as_slice()));
    assert!(bindings[0].odoh_capable);

    let resolved = OdohConfigSource::HttpsSvcb {
        response_packet: response,
        lookup_security: OdohConfigLookupSecurity::EncryptedDns,
        retrieved_at_secs: 2_000,
    }
    .resolve_at(2_000)
    .expect("HTTPS/SVCB ODoH config resolves");

    assert_eq!(resolved.source_kind(), OdohConfigSourceKind::HttpsSvcb);
    assert_eq!(resolved.target_config().key_id(), hex_bytes(KEY_ID).as_slice());
    assert_eq!(resolved.expires_at_secs(), 2_120);
}

#[test]
fn odoh_https_svcb_config_source_refuses_plaintext_lookup() {
    let error = OdohConfigSource::HttpsSvcb {
        response_packet: build_https_response_with_odoh_config(120, odoh_configs()),
        lookup_security: OdohConfigLookupSecurity::PlainDns,
        retrieved_at_secs: 2_000,
    }
    .resolve_at(2_000)
    .expect_err("plain DNS ODoH config lookup must be refused");

    assert!(matches!(error, OdohError::PlaintextConfigLookup));
}

fn build_https_response_with_odoh_config(ttl_secs: u32, config_bytes: Vec<u8>) -> Vec<u8> {
    let mut query = Message::new(42, MessageType::Query, OpCode::Query);
    query.add_query(Query::new(Name::from_ascii("odoh-target.test.").expect("query name"), RecordType::HTTPS));

    let mut response = Message::response(query.metadata.id, OpCode::Query);
    response.metadata.recursion_desired = query.metadata.recursion_desired;
    response.metadata.recursion_available = true;
    response.metadata.response_code = ResponseCode::NoError;
    for question in &query.queries {
        response.add_query(question.clone());
        let binding = SVCB::new(
            1,
            Name::from_ascii("svc.odoh-target.test.").expect("target name"),
            vec![(SvcParamKey::Key(ODOHCONFIG_SVCB_KEY), SvcParamValue::Unknown(Unknown(config_bytes.clone())))],
        );
        response.add_answer(Record::from_rdata(question.name.clone(), ttl_secs, RData::HTTPS(HTTPS(binding))));
    }

    response.to_vec().expect("response serializes")
}

fn unsupported_then_supported_configs() -> Vec<u8> {
    let configs = odoh_configs();
    let supported_entry = &configs[2..];
    let mut unsupported_entry = supported_entry.to_vec();
    unsupported_entry[0] = 0xff;
    unsupported_entry[1] = 0xff;

    let mut combined = Vec::new();
    let total_len = unsupported_entry.len() + supported_entry.len();
    combined.extend(u16::try_from(total_len).expect("fixture length fits").to_be_bytes());
    combined.extend(unsupported_entry);
    combined.extend(supported_entry);
    combined
}

fn odoh_configs() -> Vec<u8> {
    hex_bytes(ODOHCONFIGS)
}

fn hex_bytes(value: &str) -> Vec<u8> {
    hex::decode(value).expect("hex fixture decodes")
}
