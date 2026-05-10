mod common;

use ripdpi_strategy_ipv6::{apply_ipv6_ext_header, ipv6_tcp_checksum, Ipv6ExtType};

#[test]
fn destopts_injection_keeps_tcp_checksum_valid() {
    let packet = common::ipv6_tcp_packet();

    let output = apply_ipv6_ext_header(&packet, Ipv6ExtType::DestOpts).expect("inject destopts");
    let checksum = ipv6_tcp_checksum(&output).expect("checksum");

    assert_eq!(u16::from_be_bytes([output[64], output[65]]), checksum);
}
