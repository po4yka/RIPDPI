use ripdpi_strategy_http::{apply_domcase, apply_hostcase, apply_unixeol};
use ripdpi_strategy_trait::FlowId;

#[test]
fn lf_head_keeps_crlf_bytes_in_body() {
    let input = b"POST / HTTP/1.1\nHost: example.com\nContent-Length: 14\n\nAlpha\r\n\r\nOmega";
    assert_eq!(apply_unixeol(input), input);
}

#[test]
fn lf_head_without_host_does_not_modify_body_host() {
    let input = b"POST / HTTP/1.0\nContent-Length: 26\n\n\nHost: Body.Example\r\n\r\nend";
    assert_eq!(apply_domcase(input), input);
    assert_eq!(apply_hostcase(input, FlowId(1)), input);
}
