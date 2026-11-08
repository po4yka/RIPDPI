use super::*;
use crate::egress::upstream_vectors::*;

#[test]
fn ipv6_output_bound_includes_extension_and_transport_headers() {
    for input in [IPV6_TCP, IPV6_HOP_TCP, IPV6_HOP_UDP] {
        let packet = decode(input);
        let meta = PacketMeta::parse(&packet).unwrap();
        let maximum = usize::from(u16::MAX) - (meta.payload_offset - IPV6_HEADER_LEN);
        let output = packet_with_payload(&packet, meta, &vec![0x55; maximum], 0, None).unwrap();
        assert_eq!(output.len() - IPV6_HEADER_LEN, usize::from(u16::MAX));
        assert_eq!(&output[4..6], &[0xff, 0xff]);
        assert!(packet_with_payload(&packet, meta, &vec![0x55; maximum + 1], 0, None).is_none());
    }
}
