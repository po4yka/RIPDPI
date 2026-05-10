use ripdpi_strategy_http::apply_unixeol;

#[test]
fn unixeol_rewrites_header_delimiters_without_touching_body() {
    let payload = b"GET / HTTP/1.1\r\nHost: x.com\r\nUser-Agent: a\r\n\r\nline\r\nbody";

    let modified = apply_unixeol(payload);

    assert_eq!(modified, b"GET / HTTP/1.1\nHost: x.com\nUser-Agent: a\n\nline\r\nbody");
}
