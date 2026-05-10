mod common;

use ripdpi_strategy_ipv6::{apply_ipv6_ext_header, Ipv6ExtType};

#[test]
fn routing_injection_uses_routing_outer_header() {
    let packet = common::ipv6_tcp_packet();

    let output = apply_ipv6_ext_header(&packet, Ipv6ExtType::Routing).expect("inject routing");

    assert_eq!(output[6], 43);
    assert_eq!(&output[40..48], &[6, 0, 4, 0, 0, 0, 0, 0]);
    assert_eq!(&output[48..], &packet[40..]);
}

#[test]
fn ext_type_parser_accepts_yaml_names() {
    assert_eq!(Ipv6ExtType::parse("hopbyhop"), Some(Ipv6ExtType::HopByHop));
    assert_eq!(Ipv6ExtType::parse("destopts"), Some(Ipv6ExtType::DestOpts));
    assert_eq!(Ipv6ExtType::parse("routing"), Some(Ipv6ExtType::Routing));
    assert_eq!(Ipv6ExtType::parse("unknown"), None);
}
