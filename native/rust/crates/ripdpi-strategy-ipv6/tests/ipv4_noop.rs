use ripdpi_strategy_ipv6::{Ipv6ExtType, apply_ipv6_ext_header};

#[test]
fn ipv4_packets_are_not_modified() {
    let packet = [0x45, 0, 0, 20, 0, 0, 0, 0, 64, 6, 0, 0, 127, 0, 0, 1, 127, 0, 0, 1];

    assert_eq!(apply_ipv6_ext_header(&packet, Ipv6ExtType::DestOpts), None);
}
