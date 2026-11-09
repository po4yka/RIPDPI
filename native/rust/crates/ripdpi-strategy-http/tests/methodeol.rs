use ripdpi_strategy_http::apply_methodeol;

#[test]
fn methodeol_adds_an_empty_line_before_the_request() {
    let payload = b"GET / HTTP/1.1\r\nHost: x.com\r\n\r\n";

    let modified = apply_methodeol(payload);

    assert_eq!(modified, b"\r\nGET / HTTP/1.1\r\nHost: x.com\r\n\r\n");
}

#[test]
fn methodeol_supports_common_http_methods() {
    for method in ["POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "CONNECT", "TRACE"] {
        for version in ["1.0", "1.1"] {
            let payload = format!("{method} / HTTP/{version}\r\nHost: x.com\r\n\r\n");

            let modified = apply_methodeol(payload.as_bytes());

            assert_eq!(modified, format!("\r\n{method} / HTTP/{version}\r\nHost: x.com\r\n\r\n").as_bytes());
        }
    }
}

#[test]
fn methodeol_preserves_framed_binary_body() {
    let payload = b"POST /upload HTTP/1.1\r\nHost: x.com\r\nContent-Length: 6\r\n\r\nA\r\nB\0\xff";

    let modified = apply_methodeol(payload);

    assert_eq!(modified, b"\r\nPOST /upload HTTP/1.1\r\nHost: x.com\r\nContent-Length: 6\r\n\r\nA\r\nB\0\xff");
}

#[test]
fn methodeol_preserves_unsupported_request_versions() {
    for version in ["1.9", "2.0"] {
        let payload = format!("GET / HTTP/{version}\r\nHost: x.com\r\n\r\n");

        assert_eq!(apply_methodeol(payload.as_bytes()), payload.as_bytes());
    }
}
