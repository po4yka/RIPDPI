use ripdpi_strategy_http::apply_hostcase;
use ripdpi_strategy_trait::FlowId;

#[test]
fn hostcase_is_deterministic_for_the_same_flow_id() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";

    let first = apply_hostcase(payload, FlowId(0x5eed));
    let second = apply_hostcase(payload, FlowId(0x5eed));

    assert_eq!(first, second);
    assert_ne!(first, payload);
}

#[test]
fn hostcase_changes_an_already_uppercase_single_letter_host() {
    let payload = b"GET / HTTP/1.1\r\nHost: A\r\n\r\n";
    assert_ne!(apply_hostcase(payload, FlowId(0)), payload);
}
