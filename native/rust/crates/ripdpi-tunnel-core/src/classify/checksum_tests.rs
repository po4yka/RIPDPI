use super::{IpClass, classify_ip_packet_with_parse_status};

// Independent RFC768/8200 vectors with odd-length payloads.
const IPV4: &str = "4500001f00000000401134c30a000002cb0071099c4001bb000b17cf010203";
const IPV6: &str =
    "60000000000b1140fd00000000000000000000000000000120010db80000000000000000000000019c4001bb000b331f010203";

#[test]
fn valid_odd_length_udp_checksums_preserve_payload() {
    for input in [IPV4, IPV6] {
        let packet = hex::decode(input).expect("literal packet");
        let (class, valid) = classify_ip_packet_with_parse_status(&packet, None);
        assert!(valid);
        assert!(matches!(class, IpClass::Udp { payload: [1, 2, 3], .. }));
    }
}

#[test]
fn ipv6_zero_udp_checksum_is_rejected() {
    let mut packet = hex::decode(IPV6).expect("literal packet");
    packet[46..48].fill(0);
    let (_, valid) = classify_ip_packet_with_parse_status(&packet, None);
    assert!(!valid, "IPv6 UDP checksum zero must fail validation");
}

#[test]
fn incorrect_nonzero_udp_checksums_are_rejected() {
    for input in [IPV4, IPV6] {
        let mut packet = hex::decode(input).expect("literal packet");
        *packet.last_mut().expect("payload") ^= 1;
        let (_, valid) = classify_ip_packet_with_parse_status(&packet, None);
        assert!(!valid, "a corrupted UDP payload must fail validation");
    }
}

#[test]
fn ipv4_zero_udp_checksum_remains_permitted() {
    let mut packet = hex::decode(IPV4).expect("literal packet");
    packet[26..28].fill(0);
    let (class, valid) = classify_ip_packet_with_parse_status(&packet, None);
    assert!(valid);
    assert!(matches!(class, IpClass::Udp { .. }));
}
