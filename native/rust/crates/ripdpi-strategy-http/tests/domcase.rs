use ripdpi_strategy_http::apply_domcase;

#[test]
fn domcase_alternates_each_domain_label_from_second_character() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\nbody";

    let modified = apply_domcase(payload);

    assert!(modified.windows(b"Host: eXaMpLe.CoM".len()).any(|window| window == b"Host: eXaMpLe.CoM"));
    assert!(modified.ends_with(b"\r\n\r\nbody"));
}
