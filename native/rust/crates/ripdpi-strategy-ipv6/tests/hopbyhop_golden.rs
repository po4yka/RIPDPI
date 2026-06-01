mod common;

use ripdpi_strategy_ipv6::{Ipv6ExtType, apply_ipv6_ext_header};

#[test]
fn hopbyhop_injection_uses_hopbyhop_outer_header() {
    let packet = common::ipv6_tcp_packet();

    let output = apply_ipv6_ext_header(&packet, Ipv6ExtType::HopByHop).expect("inject hopbyhop");

    assert_eq!(output[6], 0);
    assert_eq!(&output[40..48], &[6, 0, 1, 4, 0, 0, 0, 0]);
}
