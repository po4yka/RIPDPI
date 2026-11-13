use super::sample_tcp_repair_snapshot;
use crate::linux::fragmentation::build_multi_disorder_packets;
use crate::{TcpFlagOverrides, TcpPayloadSegment};

#[test]
fn original_flags_cover_every_region_across_sequence_wrap() {
    let payload = b"abcdefgh";
    let segments = [
        TcpPayloadSegment { start: 0, end: 2 },
        TcpPayloadSegment { start: 2, end: 5 },
        TcpPayloadSegment { start: 5, end: 8 },
    ];
    let mut snapshot = sample_tcp_repair_snapshot();
    snapshot.sequence_number = 0xffff_fffe;
    for (source, target) in [("127.0.0.1:50000", "127.0.0.1:443"), ("[::1]:50000", "[::1]:443")] {
        let packets = build_multi_disorder_packets(
            source.parse().unwrap(),
            target.parse().unwrap(),
            37,
            payload,
            &segments,
            &snapshot,
            false,
            TcpFlagOverrides { set: 0x80, unset: 0x08 },
            &[],
        )
        .expect("build genuine regions with explicit flags");
        assert_eq!(packets.len(), 3);
        for (packet, (sequence, expected)) in
            packets.iter().zip([(0xffff_fffe, b"ab".as_slice()), (0, b"cde".as_slice()), (3, b"fgh".as_slice())])
        {
            let ip_len = if packet[0] >> 4 == 4 { usize::from(packet[0] & 15) * 4 } else { 40 };
            let tcp = &packet[ip_len..];
            let data_offset = usize::from(tcp[12] >> 4) * 4;
            assert_eq!(u32::from_be_bytes(tcp[4..8].try_into().unwrap()), sequence);
            assert_eq!(tcp[13], 0x90, "all genuine regions must retain CWR and clear PSH");
            assert_eq!(&tcp[data_offset..], expected);
        }
    }
}
