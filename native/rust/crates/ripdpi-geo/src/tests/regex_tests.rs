use super::*;

#[test]
fn quoted_go_regex_matches_literal_domains_in_both_formats() {
    // Go regexp.Compile(`^\Qexample.com\E$`) matches the literal dot.
    for catalog in [
        geosite_catalog("quoted", &[domain(1, r"^\Qexample.com\E$")]),
        sing_geosite_catalog(&[("quoted", &[(3, r"^\Qexample.com\E$")])]),
    ] {
        let dir = temp_dir();
        fs::write(dir.join("geosite.db"), catalog).expect("write quoted regex database");
        let runtime = GeoRuntime::load(paths(&dir)).expect("load quoted regex database").runtime;
        assert_eq!(Some(true), runtime.geosite_match("quoted", "example.com"));
        assert_eq!(Some(false), runtime.geosite_match("quoted", "exampleXcom"));
        fs::remove_dir_all(dir).expect("remove fixture");
    }
}

#[test]
fn invalid_regex_rejects_load_and_preserves_reload_snapshot() {
    let dir = temp_dir();
    fs::write(dir.join("geosite.db"), geosite_catalog("test", &[domain(3, "example.com")]))
        .expect("write working database");
    let runtime = GeoRuntime::load(paths(&dir)).expect("load working database").runtime;
    let versions = runtime.versions();
    fs::write(dir.join("geosite.db"), geosite_catalog("test", &[domain(1, "(")]))
        .expect("write invalid regex database");

    assert!(matches!(
        runtime.reload(),
        Err(GeoRuntimeError::GeositeRegex { category, rule_index: 0, .. }) if category == "test"
    ));
    assert_eq!(versions, runtime.versions());
    assert_eq!(Some(true), runtime.geosite_match("test", "example.com"));
    assert!(GeoRuntime::load(paths(&dir)).is_err());
    fs::remove_dir_all(dir).expect("remove fixture");
}
