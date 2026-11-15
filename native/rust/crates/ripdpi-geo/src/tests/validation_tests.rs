use super::*;

#[test]
fn country_match_distinguishes_query_errors_from_missing_addresses() {
    let dir = temp_dir();
    let paths = paths(&dir);
    fs::write(&paths.geoip_db, sing_geoip_database(&["se", "telegram"])).expect("IPv4-only database");
    let runtime = GeoRuntime::load(paths).expect("load IPv4 database").runtime;
    assert_eq!(Some(true), runtime.country_match("SE", ip("89.160.20.112")));
    assert_eq!(Some(false), runtime.country_match("RU", ip("89.160.20.112")));
    assert_eq!(Some(false), runtime.country_match("SE", ip("203.0.113.1")));
    assert_eq!(None, runtime.country_match("SE", ip("2001:db8::1")));
    assert!(!runtime.country_contains_ip("SE", ip("2001:db8::1")));
    fs::remove_file(dir.join("geoip.db")).expect("remove database");
    runtime.reload().expect("optional missing database");
    assert_eq!(None, runtime.country_match("SE", ip("89.160.20.112")));
    fs::remove_dir_all(dir).expect("cleanup");
}

#[test]
fn corrupt_mmdb_rejects_load_and_keeps_last_good_pair() {
    let dir = temp_dir();
    write_geoip_fixture(&dir);
    fs::write(dir.join("geosite.db"), geosite_catalog("test", &[domain(3, "example.com")])).expect("geosite");
    let runtime = GeoRuntime::load(paths(&dir)).expect("working databases").runtime;
    let versions = runtime.versions();
    let reader = Reader::from_source(GEOIP_COUNTRY_FIXTURE).expect("country");
    let width = usize::from(reader.metadata().record_size) / 4;
    let separator = reader.metadata().node_count as usize * width;
    let offset = reader.lookup(ip("89.160.20.112")).expect("lookup").offset().expect("record");
    let mut tree = GEOIP_COUNTRY_FIXTURE.to_vec();
    tree[..width].fill(255);
    let mut zeroes = GEOIP_COUNTRY_FIXTURE.to_vec();
    zeroes[separator] = 1;
    let mut record = GEOIP_COUNTRY_FIXTURE.to_vec();
    record[separator + 16 + offset..separator + 21 + offset].copy_from_slice(&[0x38, 255, 255, 255, 255]);
    for bytes in [tree, zeroes, record] {
        fs::write(dir.join("geoip.db"), bytes).expect("corrupt MMDB");
        assert!(matches!(runtime.reload(), Err(GeoRuntimeError::GeoipParse { .. })));
        assert!(matches!(GeoRuntime::load(paths(&dir)), Err(GeoRuntimeError::GeoipParse { .. })));
        assert_eq!(versions, runtime.versions());
        assert!(runtime.country_contains_ip("SE", ip("89.160.20.112")));
        assert!(runtime.geosite_contains("test", "example.com"));
    }
    fs::remove_dir_all(dir).expect("cleanup");
}

#[test]
fn empty_description_does_not_hide_invalid_or_cyclic_unknown_pointers() {
    for pointer in [&[0x38, 255, 255, 255, 255][..], &[0x20, 0][..]] {
        let mut map = vec![0xe1, 0x41, b'x'];
        map.extend_from_slice(pointer);
        let mut bytes = sing_geoip_database(&["se", "telegram"]);
        bytes.splice(28..31, map.iter().copied());
        let next = 18 + u32::try_from(map.len()).expect("short record");
        bytes[6..9].copy_from_slice(&next.to_be_bytes()[1..]);
        let reader = Reader::from_source(bytes.as_slice()).expect("metadata/tree");
        assert!(reader.metadata().description.is_empty());
        assert!(reader.lookup(ip("1.1.1.1")).expect("lookup").decode::<geoip2::Country>().is_ok());
        let dir = temp_dir();
        fs::write(dir.join("geoip.db"), bytes).expect("MMDB");
        assert!(matches!(GeoRuntime::load(paths(&dir)), Err(GeoRuntimeError::GeoipParse { .. })));
        fs::remove_dir_all(dir).expect("cleanup");
    }
}

#[test]
fn upstream_mmdbwriter_empty_description_supports_all_record_sizes() {
    for bytes in [
        include_bytes!("../../tests/fixtures/sing-geoip-record-24.mmdb").as_slice(),
        include_bytes!("../../tests/fixtures/sing-geoip-record-28.mmdb").as_slice(),
        include_bytes!("../../tests/fixtures/sing-geoip-record-32.mmdb").as_slice(),
    ] {
        assert!(Reader::from_source(bytes).expect("upstream MMDB").metadata().description.is_empty());
        let dir = temp_dir();
        fs::write(dir.join("geoip.db"), bytes).expect("generated MMDB");
        let runtime = GeoRuntime::load(paths(&dir)).expect("generated database").runtime;
        assert!(runtime.country_contains_ip("SE", ip("89.160.20.112")));
        assert!(runtime.country_contains_ip("se", ip("2001:db8::1")));
        assert!(runtime.country_contains_ip("telegram", ip("149.154.167.50")));
        assert_eq!(None, runtime.ip_metadata(ip("149.154.167.50")));
        fs::remove_dir_all(dir).expect("cleanup");
    }
}

#[test]
fn admission_rejects_missing_empty_wrong_kind_and_undeclared_sing_data() {
    let dir = temp_dir();
    let missing = dir.join("missing.db");
    for kind in [GeoDatabaseKind::Geoip, GeoDatabaseKind::Geosite] {
        assert!(validate_database(&missing, kind).is_err());
        fs::write(&missing, []).expect("empty file");
        assert!(validate_database(&missing, kind).is_err());
        fs::remove_file(&missing).expect("remove empty file");
    }
    write_geoip_fixture(&dir);
    assert!(validate_database(&dir.join("geoip.db"), GeoDatabaseKind::Geoip).is_ok());
    assert!(validate_database(&dir.join("geoip.db"), GeoDatabaseKind::Geosite).is_err());
    fs::write(dir.join("geosite.db"), geosite_catalog("test", &[domain(3, "example.com")])).expect("geosite");
    assert!(validate_database(&dir.join("geosite.db"), GeoDatabaseKind::Geosite).is_ok());
    assert!(validate_database(&dir.join("geosite.db"), GeoDatabaseKind::Geoip).is_err());
    fs::write(dir.join("geosite.db"), [0, 0]).expect("empty catalog");
    assert!(validate_database(&dir.join("geosite.db"), GeoDatabaseKind::Geosite).is_ok());
    fs::write(dir.join("geosite.db"), [0, 0, 1]).expect("trailing data");
    assert!(validate_database(&dir.join("geosite.db"), GeoDatabaseKind::Geosite).is_err());
    fs::remove_dir_all(dir).expect("cleanup");
}
