use super::*;

#[test]
fn modify_udp_matches_independent_upstream_packet_vectors() {
    use super::super::upstream_vectors::*;
    let (_script, mut interceptor) =
        lua_interceptor("modify-udp-vectors", r"desync.dis.payload='\0new\255'; return VERDICT_MODIFY");
    for (original, expected) in [(IPV4_UDP, IPV4_UDP_REWRITE), (IPV6_HOP_UDP, IPV6_HOP_UDP_REWRITE)] {
        assert!(interceptor.handle_packet(&decode(original)));
        assert_eq!(interceptor.injector.packets.last().unwrap(), &decode(expected));
    }
}

#[test]
fn modify_tcp_preserves_sequence_space_and_changes_payload() {
    use super::super::upstream_vectors::*;
    let (_script, mut interceptor) =
        lua_interceptor("modify-tcp-bytes", "desync.dis.payload='ABCDEFG'; return VERDICT_MODIFY");
    for original in [IPV4_TCP, IPV6_TCP, IPV6_HOP_TCP] {
        let original = decode(original);
        assert!(interceptor.handle_packet(&original));
        let output = interceptor.injector.packets.last().unwrap();
        let parsed = etherparse::SlicedPacket::from_ip(output).unwrap();
        let Some(etherparse::TransportSlice::Tcp(tcp)) = parsed.transport else { panic!("TCP output") };
        assert_eq!(tcp.payload(), b"ABCDEFG");
        assert_eq!(tcp.sequence_number(), 0xffff_fffe);
        assert_eq!(tcp.acknowledgment_number(), 0x1234_5678);
        assert_eq!(output.len(), original.len());
    }
}

#[test]
fn modify_rejects_tcp_length_change_before_any_packet_send() {
    use super::super::upstream_vectors::*;
    let (_script, mut interceptor) =
        lua_interceptor("modify-tcp-length", "desync.dis.payload='short'; return VERDICT_MODIFY");
    assert!(!interceptor.handle_packet(&decode(IPV4_TCP)));
    assert!(interceptor.injector.packets.is_empty());
}
