use super::*;

// Field 1 contains category US and a FULL domain rule for x.
const CATALOG: &[u8] = b"\x0a\x0b\x0a\x02US\x12\x05\x08\x03\x12\x01x";
const UNKNOWN_FIELDS: &[u8] = b"\x18\x01\x21abcdefgh\x2a\x01z\x35abcd\x3b\x40\x01\x3c";

fn assert_full_rule(bytes: &[u8]) {
    let catalog = parse_geosite_catalog(bytes).expect("valid extended catalog");
    assert_eq!(catalog["us"], vec![GeositeDomainRule { kind: GeositeDomainKind::Full, value: "x".into() }]);
}

#[test]
fn protobuf_catalog_accepts_unknown_fields_before_known_entries() {
    let mut bytes = UNKNOWN_FIELDS.to_vec();
    bytes.extend_from_slice(CATALOG);
    assert_full_rule(&bytes);
}

#[test]
fn protobuf_catalog_accepts_unknown_fields_at_every_level() {
    let mut domain = b"\x08\x03\x12\x01x".to_vec();
    domain.extend_from_slice(UNKNOWN_FIELDS);
    let mut entry = b"\x0a\x02US".to_vec();
    entry.extend_from_slice(&[0x12, u8::try_from(domain.len()).expect("short fixture")]);
    entry.extend_from_slice(&domain);
    entry.extend_from_slice(UNKNOWN_FIELDS);
    let mut catalog = vec![0x0a, u8::try_from(entry.len()).expect("short fixture")];
    catalog.extend_from_slice(&entry);
    catalog.extend_from_slice(UNKNOWN_FIELDS);
    assert_full_rule(&catalog);
}

#[test]
fn protobuf_catalog_rejects_unknown_domain_kind() {
    let bytes = b"\x0a\x0b\x0a\x02US\x12\x05\x08\x63\x12\x01x";
    assert!(parse_geosite_catalog(bytes).is_err(), "unknown type must not become a broad Plain rule");
}

#[test]
fn protobuf_catalog_rejects_overflowing_unknown_varint() {
    let mut bytes = CATALOG.to_vec();
    bytes.extend_from_slice(b"\x18\xff\xff\xff\xff\xff\xff\xff\xff\xff\x02");
    assert!(parse_geosite_catalog(&bytes).is_err());
}

#[test]
fn protobuf_catalog_accepts_defaults_and_last_scalar_values() {
    assert!(parse_geosite_catalog(&[]).expect("empty catalog").is_empty());
    let catalog = parse_geosite_catalog(b"\x0a\x02\x12\x00").expect("default domain");
    assert_eq!(catalog[""], vec![GeositeDomainRule { kind: GeositeDomainKind::Plain, value: String::new() }]);
    // Domain value precedes type; repeated scalar fields use their last value.
    let bytes = b"\x0a\x14\x0a\x02ZZ\x12\x0a\x12\x01y\x08\x00\x12\x01x\x08\x03\x0a\x02US";
    assert_full_rule(bytes);
}

#[test]
fn protobuf_catalog_rejects_malformed_messages_and_utf8() {
    for bytes in
        [&b"\x0a"[..], &b"\x0a\x02\x0a\xff"[..], &b"\x0a\x03\x0a\x01\xff"[..], &b"\x0f"[..], &b"\x0a\x01\x00"[..]]
    {
        assert!(parse_geosite_catalog(bytes).is_err(), "invalid catalog {bytes:?}");
    }
}
