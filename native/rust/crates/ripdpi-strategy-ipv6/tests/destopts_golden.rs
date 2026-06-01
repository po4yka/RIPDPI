mod common;

use ripdpi_strategy_ipv6::{Ipv6ExtType, apply_ipv6_ext_header};

#[test]
fn destopts_injection_updates_outer_header_and_preserves_tcp() {
    let packet = common::ipv6_tcp_packet();

    let output = apply_ipv6_ext_header(&packet, Ipv6ExtType::DestOpts).expect("inject destopts");

    assert_eq!(output[6], 60);
    assert_eq!(u16::from_be_bytes([output[4], output[5]]), 28);
    assert_eq!(&output[40..48], &[6, 0, 1, 4, 0, 0, 0, 0]);
    assert_eq!(&output[48..], &packet[40..]);
}
