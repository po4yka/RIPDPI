use ripdpi_strategy_http::{apply_domcase, apply_hostcase, apply_methodeol, apply_unixeol};
use ripdpi_strategy_trait::FlowId;

#[test]
fn missing_host_header_is_left_unchanged() {
    let payload = b"GET / HTTP/1.1\r\nUser-Agent: a\r\n\r\n";

    assert_eq!(apply_domcase(payload), payload);
    assert_eq!(apply_hostcase(payload, FlowId(7)), payload);
    assert_eq!(apply_methodeol(payload), payload);
    assert_eq!(apply_unixeol(payload), payload);
}
