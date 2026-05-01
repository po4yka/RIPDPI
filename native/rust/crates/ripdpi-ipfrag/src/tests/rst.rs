use super::support::*;

#[test]
fn build_fake_rst_packet_ipv4_has_rst_flag_and_correct_seq() {
    let spec = TcpFragmentSpec {
        src: "1.2.3.4:12345".parse().unwrap(),
        dst: "5.6.7.8:443".parse().unwrap(),
        ttl: 3,
        identification: 0x1234,
        sequence_number: 1000,
        acknowledgment_number: 2000,
        window_size: 0,
        timestamp: None,
        tcp_flags_set: 0,
        tcp_flags_unset: 0,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    let packet = build_fake_rst_packet(&spec).expect("build fake rst");
    let (ip_header, remaining) = Ipv4Header::from_slice(&packet).expect("parse ipv4");
    assert_eq!(ip_header.time_to_live, 3);
    assert!(ip_header.dont_fragment);
    let (tcp_header, payload) = TcpHeader::from_slice(remaining).expect("parse tcp");
    assert!(tcp_header.rst);
    assert!(tcp_header.ack);
    assert_eq!(tcp_header.sequence_number, 1000);
    assert_eq!(tcp_header.acknowledgment_number, 2000);
    assert_eq!(tcp_header.window_size, 0);
    assert!(payload.is_empty());
}

#[test]
fn build_fake_rst_packet_ipv6_has_rst_flag() {
    let spec = TcpFragmentSpec {
        src: "[::1]:12345".parse().unwrap(),
        dst: "[::2]:443".parse().unwrap(),
        ttl: 5,
        identification: 0,
        sequence_number: 3000,
        acknowledgment_number: 4000,
        window_size: 0,
        timestamp: None,
        tcp_flags_set: 0,
        tcp_flags_unset: 0,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    let packet = build_fake_rst_packet(&spec).expect("build fake rst v6");
    let (ip_header, _) = Ipv6Header::from_slice(&packet).expect("parse ipv6");
    assert_eq!(ip_header.hop_limit, 5);
    let tcp_offset = Ipv6Header::LEN;
    let (tcp_header, payload) = TcpHeader::from_slice(&packet[tcp_offset..]).expect("parse tcp");
    assert!(tcp_header.rst);
    assert!(tcp_header.ack);
    assert_eq!(tcp_header.sequence_number, 3000);
    assert_eq!(tcp_header.acknowledgment_number, 4000);
    assert!(payload.is_empty());
}

#[test]
fn build_fake_rst_packet_rejects_mixed_address_families() {
    let spec = TcpFragmentSpec {
        src: "1.2.3.4:12345".parse().unwrap(),
        dst: "[::2]:443".parse().unwrap(),
        ttl: 3,
        identification: 0,
        sequence_number: 0,
        acknowledgment_number: 0,
        window_size: 0,
        timestamp: None,
        tcp_flags_set: 0,
        tcp_flags_unset: 0,
        ipv6_ext: Ipv6ExtHeaders::default(),
    };
    assert_eq!(build_fake_rst_packet(&spec).unwrap_err(), BuildError::AddressFamilyMismatch);
}
