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

#[test]
fn regex_preserves_pattern_flags_and_normalizes_candidate_case() {
    for catalog in [
        [
            geosite_catalog("sensitive", &[domain(1, r"^API\.example$")]),
            geosite_catalog("insensitive", &[domain(1, r"(?i)^API\.example$")]),
            geosite_catalog("lower", &[domain(1, r"^api\.example$")]),
        ]
        .concat(),
        sing_geosite_catalog(&[
            ("sensitive", &[(3, r"^API\.example$")]),
            ("insensitive", &[(3, r"(?i)^API\.example$")]),
            ("lower", &[(3, r"^api\.example$")]),
        ]),
    ] {
        let dir = temp_dir();
        fs::write(dir.join("geosite.db"), catalog).expect("write regex case database");
        let runtime = GeoRuntime::load(paths(&dir)).expect("load regex case database").runtime;
        for host in ["api.example", "API.EXAMPLE."] {
            assert_eq!(Some(false), runtime.geosite_match("sensitive", host));
            assert_eq!(Some(true), runtime.geosite_match("insensitive", host));
            assert_eq!(Some(true), runtime.geosite_match("lower", host));
        }
        fs::remove_dir_all(dir).expect("remove fixture");
    }
}

#[test]
fn regex_character_classes_keep_go_literal_and_range_semantics() {
    // Expected sets come from Go regexp.Compile, not Rust class-set operators.
    for (pattern, matching, absent) in [
        (r"^[a&&b]\.example$", "a.example", "c.example"),
        (r"^[a&&b]\.example$", "b.example", "c.example"),
        (r"^[a-z~~b]\.example$", "b.example", "0.example"),
        (r"^[[a-c]\.example$", "a.example", "d.example"),
        (r"^[[:alpha:]]\.example$", "a.example", "0.example"),
        (r"^[\&a]\.example$", "a.example", "b.example"),
        (r"^[]a]\.example$", "a.example", "b.example"),
        (r"^[^]a]\.example$", "b.example", "a.example"),
        (r"^x[a-z\-\-]x\.example$", "x-x.example", "x0x.example"),
        (r"^\Qx--x.example\E$", "x--x.example", "x-x.example"),
    ] {
        for catalog in
            [geosite_catalog("test", &[domain(1, pattern)]), sing_geosite_catalog(&[("test", &[(3, pattern)])])]
        {
            let dir = temp_dir();
            fs::write(dir.join("geosite.db"), catalog).expect("write Go class database");
            let runtime = GeoRuntime::load(paths(&dir)).expect("load Go class database").runtime;
            assert_eq!(Some(true), runtime.geosite_match("test", matching), "{pattern}");
            assert_eq!(Some(false), runtime.geosite_match("test", absent), "{pattern}");
            fs::remove_dir_all(dir).expect("remove fixture");
        }
    }
}

#[test]
fn unsupported_hyphen_class_rejects_both_formats_and_preserves_snapshot() {
    let dir = temp_dir();
    fs::write(dir.join("geosite.db"), geosite_catalog("test", &[domain(3, "one.example")])).expect("working database");
    let runtime = GeoRuntime::load(paths(&dir)).expect("load working database").runtime;
    let versions = runtime.versions();
    for pattern in [r"^x[a-z--]x\.example$", r"^x[--0]x\.example$"] {
        for catalog in
            [geosite_catalog("test", &[domain(1, pattern)]), sing_geosite_catalog(&[("test", &[(3, pattern)])])]
        {
            fs::write(dir.join("geosite.db"), catalog).expect("write unsupported class");
            assert!(matches!(
                runtime.reload(),
                Err(GeoRuntimeError::GeositeRegex { source: regex::Error::Syntax(_), .. })
            ));
            assert_eq!(versions, runtime.versions());
            assert_eq!(Some(true), runtime.geosite_match("test", "one.example"));
            assert!(GeoRuntime::load(paths(&dir)).is_err());
        }
    }
    fs::remove_dir_all(dir).expect("remove fixture");
}
