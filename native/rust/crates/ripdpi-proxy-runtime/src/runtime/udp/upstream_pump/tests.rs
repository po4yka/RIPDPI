use super::validated_socks5_udp_payload;
use std::net::SocketAddr;

#[test]
fn replies_match_the_selected_ipv4_or_ipv6_peer() {
    let ipv4: SocketAddr = "203.0.113.7:443".parse().expect("IPv4 target");
    let ipv6: SocketAddr = "[2001:db8::7]:443".parse().expect("IPv6 target");
    let reply4 = b"\0\0\0\x01\xcb\0\x71\x07\x01\xbbpayload";
    let reply6 = b"\0\0\0\x04\x20\x01\x0d\xb8\0\0\0\0\0\0\0\0\0\0\0\x07\x01\xbbpayload";
    assert_eq!(validated_socks5_udp_payload(reply4, ipv4, None), Some(b"payload".as_slice()));
    assert_eq!(validated_socks5_udp_payload(reply6, ipv6, None), Some(b"payload".as_slice()));
    assert_eq!(validated_socks5_udp_payload(reply4, ipv6, None), None);
    assert_eq!(validated_socks5_udp_payload(reply6, ipv4, None), None);
    assert_eq!(validated_socks5_udp_payload(reply4, "203.0.113.7:444".parse().unwrap(), None), None);
    assert_eq!(validated_socks5_udp_payload(reply4, "203.0.113.8:443".parse().unwrap(), None), None);
}

#[test]
fn ipv4_mapped_ipv6_replies_use_the_same_peer_identity() {
    let ipv4: SocketAddr = "203.0.113.7:443".parse().expect("IPv4 target");
    let mapped: SocketAddr = "[::ffff:203.0.113.7]:443".parse().expect("mapped target");
    let reply4 = b"\0\0\0\x01\xcb\0\x71\x07\x01\xbbpayload";
    let reply6 = b"\0\0\0\x04\0\0\0\0\0\0\0\0\0\0\xff\xff\xcb\0\x71\x07\x01\xbbpayload";
    assert_eq!(validated_socks5_udp_payload(reply6, ipv4, None), Some(b"payload".as_slice()));
    assert_eq!(validated_socks5_udp_payload(reply4, mapped, None), Some(b"payload".as_slice()));
}

#[test]
fn reply_domains_use_only_the_known_authority_or_a_matching_numeric_ip() {
    let target: SocketAddr = "203.0.113.7:443".parse().expect("target");
    let known = b"\0\0\0\x03\x0eKNOWN.EXAMPLE.\x01\xbbpayload";
    let unknown = b"\0\0\0\x03\x0funknown.invalid\x01\xbbpayload";
    let numeric = b"\0\0\0\x03\x0b203.0.113.7\x01\xbbpayload";
    assert_eq!(validated_socks5_udp_payload(known, target, Some("known.example")), Some(b"payload".as_slice()));
    assert_eq!(validated_socks5_udp_payload(known, target, Some("Known.Example.")), Some(b"payload".as_slice()));
    assert_eq!(validated_socks5_udp_payload(known, target, None), None);
    assert_eq!(validated_socks5_udp_payload(unknown, target, Some("known.example")), None);
    assert_eq!(validated_socks5_udp_payload(numeric, target, None), Some(b"payload".as_slice()));
    assert_eq!(validated_socks5_udp_payload(numeric, "203.0.113.8:443".parse().unwrap(), None), None);
}

#[test]
fn invalid_reply_headers_and_truncation_are_dropped() {
    let target: SocketAddr = "203.0.113.7:443".parse().expect("target");
    let valid = b"\0\0\0\x01\xcb\0\x71\x07\x01\xbb";
    for end in 0..valid.len() {
        assert_eq!(validated_socks5_udp_payload(&valid[..end], target, None), None);
    }
    assert_eq!(validated_socks5_udp_payload(valid, target, None), Some(b"".as_slice()));
    for field in 0..3 {
        for value in 1..=255 {
            let mut packet = *valid;
            packet[field] = value;
            assert_eq!(validated_socks5_udp_payload(&packet, target, None), None);
        }
    }
    for packet in [
        b"\0\0\0\xff".as_slice(),
        b"\0\0\0\x03\0\x01\xbb",
        b"\0\0\0\x03\x01\xff\x01\xbb",
        b"\0\0\0\x05\x01a\x01\xcb\0\x71\x07\x01\xbb",
    ] {
        assert_eq!(validated_socks5_udp_payload(packet, target, None), None);
    }
    let ipv6 = b"\0\0\0\x04\x20\x01\x0d\xb8\0\0\0\0\0\0\0\0\0\0\0\x07\x01\xbb";
    for end in 0..ipv6.len() {
        assert_eq!(validated_socks5_udp_payload(&ipv6[..end], "[2001:db8::7]:443".parse().unwrap(), None), None);
    }
}

#[test]
fn only_one_unmodified_datagram_is_eligible_for_remote_error_correlation() {
    use super::{UdpDesyncAction as Action, feedback_safe_actions};
    let write = || Action::Write(b"payload".to_vec());
    assert!(feedback_safe_actions(&[write()], b"payload"));
    assert!(feedback_safe_actions(&[Action::SetTtl(10), write(), Action::Delay(0)], b"payload"));
    assert!(!feedback_safe_actions(&[], b"payload"));
    assert!(!feedback_safe_actions(&[write(), write()], b"payload"));
    assert!(!feedback_safe_actions(&[Action::Write(b"fake".to_vec()), write()], b"payload"));
    assert!(!feedback_safe_actions(&[Action::Write(b"replacement".to_vec())], b"payload"));
}
