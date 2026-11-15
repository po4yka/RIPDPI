use super::*;

#[test]
fn sing_suffix_keeps_leading_dot_apex_exclusion() {
    let dir = temp_dir();
    fs::write(
        dir.join("geosite.db"),
        sing_geosite_catalog(&[
            ("dotted", &[(1, ".example.com")]),
            ("plain", &[(1, "example.com")]),
            ("full", &[(0, "example.com")]),
            ("dot", &[(1, ".")]),
            ("trailing", &[(1, ".example.com.")]),
        ]),
    )
    .expect("write suffix database");
    let runtime = GeoRuntime::load(paths(&dir)).expect("load suffix database").runtime;
    // Pinned sing/domain.NewMatcher(nil, []string{".example.com"}, false) excludes the apex.
    assert_eq!(Some(false), runtime.geosite_match("dotted", "example.com"));
    assert_eq!(Some(true), runtime.geosite_match("dotted", "a.EXAMPLE.com."));
    assert_eq!(Some(false), runtime.geosite_match("dotted", "notexample.com"));
    assert_eq!(Some(true), runtime.geosite_match("plain", "example.com"));
    assert_eq!(Some(true), runtime.geosite_match("plain", "a.example.com"));
    assert_eq!(Some(true), runtime.geosite_match("full", "example.com"));
    assert_eq!(Some(false), runtime.geosite_match("full", "a.example.com"));
    for host in ["example.com", "a.example.com"] {
        assert_eq!(Some(false), runtime.geosite_match("dot", host));
        assert_eq!(Some(false), runtime.geosite_match("trailing", host));
    }

    fs::write(dir.join("geosite.db"), geosite_catalog("root", &[domain(2, "example.com")]))
        .expect("write protobuf root database");
    runtime.reload().expect("load protobuf root database");
    assert_eq!(Some(true), runtime.geosite_match("root", "example.com"));
    assert_eq!(Some(true), runtime.geosite_match("root", "a.example.com"));
    fs::remove_dir_all(dir).expect("remove fixture");
}
