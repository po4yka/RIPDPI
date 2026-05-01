use std::net::SocketAddr;

use super::test_fixtures::{checksum_sum, finalize_checksum};
use super::udp_response::build_udp_response;

pub(crate) fn build_udp_port_unreachable(src: SocketAddr, dst: SocketAddr, payload: &[u8]) -> Vec<u8> {
    const QUOTED_UDP_PAYLOAD_LEN: usize = 8;

    let original = build_udp_response(src, dst, payload);
    if original.is_empty() {
        return Vec::new();
    }

    match (src, dst) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            let quoted_len = original.len().min(20 + 8 + QUOTED_UDP_PAYLOAD_LEN);
            let icmp_len = 8usize + quoted_len;
            let total_len = 20usize + icmp_len;
            let Ok(total_len_u16) = u16::try_from(total_len) else {
                return Vec::new();
            };
            let mut pkt = vec![0u8; total_len];
            let outer_src = dst.ip().octets();
            let outer_dst = src.ip().octets();

            pkt[0] = 0x45;
            pkt[2..4].copy_from_slice(&total_len_u16.to_be_bytes());
            pkt[8] = 64;
            pkt[9] = 1;
            pkt[12..16].copy_from_slice(&outer_src);
            pkt[16..20].copy_from_slice(&outer_dst);

            pkt[20] = 3;
            pkt[21] = 3;
            pkt[28..28 + quoted_len].copy_from_slice(&original[..quoted_len]);

            let icmp_checksum = finalize_checksum(checksum_sum(&pkt[20..]));
            pkt[22..24].copy_from_slice(&icmp_checksum.to_be_bytes());

            let header_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
            pkt[10..12].copy_from_slice(&header_checksum.to_be_bytes());

            pkt
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            let quoted_len = original.len().min(40 + 8 + QUOTED_UDP_PAYLOAD_LEN);
            let icmp_len = 8usize + quoted_len;
            let Ok(icmp_len_u16) = u16::try_from(icmp_len) else {
                return Vec::new();
            };
            let mut pkt = vec![0u8; 40 + icmp_len];
            let outer_src = dst.ip().octets();
            let outer_dst = src.ip().octets();

            pkt[0] = 0x60;
            pkt[4..6].copy_from_slice(&icmp_len_u16.to_be_bytes());
            pkt[6] = 58;
            pkt[7] = 64;
            pkt[8..24].copy_from_slice(&outer_src);
            pkt[24..40].copy_from_slice(&outer_dst);

            pkt[40] = 1;
            pkt[41] = 4;
            pkt[48..48 + quoted_len].copy_from_slice(&original[..quoted_len]);

            let icmp_checksum = icmpv6_checksum(outer_src, outer_dst, &pkt[40..]);
            pkt[42..44].copy_from_slice(&icmp_checksum.to_be_bytes());

            pkt
        }
        _ => Vec::new(),
    }
}

fn icmpv6_checksum(src_ip: [u8; 16], dst_ip: [u8; 16], payload: &[u8]) -> u16 {
    let payload_len = u32::try_from(payload.len()).unwrap_or(u32::MAX);
    let mut sum = checksum_sum(&src_ip);
    sum += checksum_sum(&dst_ip);
    sum += (payload_len >> 16) + (payload_len & 0xFFFF);
    sum += u32::from(58u16);
    sum += checksum_sum(payload);
    finalize_checksum(sum)
}
