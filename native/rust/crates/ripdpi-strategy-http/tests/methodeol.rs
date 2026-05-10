use ripdpi_strategy_http::apply_methodeol;

#[test]
fn methodeol_inserts_extra_cr_after_http_1_request_line() {
    let payload = b"GET / HTTP/1.1\r\nHost: x.com\r\n\r\n";

    let modified = apply_methodeol(payload);

    assert!(modified.starts_with(b"GET / HTTP/1.1\r\r\n"));
}

#[test]
fn methodeol_supports_common_http_methods() {
    for method in ["POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "CONNECT"] {
        let payload = format!("{method} / HTTP/1.1\r\nHost: x.com\r\n\r\n");

        let modified = apply_methodeol(payload.as_bytes());

        assert!(modified.starts_with(format!("{method} / HTTP/1.1\r\r\n").as_bytes()));
    }
}
