use std::net::{Ipv4Addr, Ipv6Addr};

pub(crate) fn checksum_sum(bytes: &[u8]) -> u32 {
    let mut sum = 0u32;
    let (chunks, remainder) = bytes.as_chunks::<2>();
    for chunk in chunks {
        sum += u32::from(u16::from_be_bytes([chunk[0], chunk[1]]));
    }
    if let Some(last) = remainder.first() {
        sum += u32::from(*last) << 8;
    }
    sum
}

pub(crate) fn finalize_checksum(mut sum: u32) -> u16 {
    while sum > 0xFFFF {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

pub(crate) fn build_ipv4_tcp_ack_packet(
    src_ip: Ipv4Addr,
    dst_ip: Ipv4Addr,
    src_port: u16,
    dst_port: u16,
    seq: u32,
    ack: u32,
) -> Vec<u8> {
    let mut pkt = vec![0u8; 40];
    pkt[0] = 0x45;
    pkt[3] = 40;
    pkt[9] = 6;
    pkt[12..16].copy_from_slice(&src_ip.octets());
    pkt[16..20].copy_from_slice(&dst_ip.octets());
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    pkt[24..28].copy_from_slice(&seq.to_be_bytes());
    pkt[28..32].copy_from_slice(&ack.to_be_bytes());
    pkt[32] = 0x50;
    pkt[33] = 0x10;
    let ip_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
    pkt[10..12].copy_from_slice(&ip_checksum.to_be_bytes());
    let mut sum = checksum_sum(&src_ip.octets());
    sum += checksum_sum(&dst_ip.octets());
    sum += u32::from(6u16);
    sum += u32::from((pkt.len() - 20) as u16);
    sum += checksum_sum(&pkt[20..]);
    let tcp_checksum = finalize_checksum(sum);
    pkt[36..38].copy_from_slice(&tcp_checksum.to_be_bytes());
    pkt
}

pub(crate) fn tcp_seq_ack(pkt: &[u8]) -> (u32, u32) {
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    let seq = u32::from_be_bytes([pkt[ihl + 4], pkt[ihl + 5], pkt[ihl + 6], pkt[ihl + 7]]);
    let ack = u32::from_be_bytes([pkt[ihl + 8], pkt[ihl + 9], pkt[ihl + 10], pkt[ihl + 11]]);
    (seq, ack)
}

pub(crate) fn build_ipv4_tcp_syn_packet(src_ip: Ipv4Addr, dst_ip: Ipv4Addr, src_port: u16, dst_port: u16) -> Vec<u8> {
    let mut pkt = vec![0u8; 40];
    pkt[0] = 0x45;
    pkt[3] = 40;
    pkt[9] = 6;
    pkt[12..16].copy_from_slice(&src_ip.octets());
    pkt[16..20].copy_from_slice(&dst_ip.octets());
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    pkt[32] = 0x50;
    pkt[33] = 0x02; // SYN
    let ip_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
    pkt[10..12].copy_from_slice(&ip_checksum.to_be_bytes());
    let tcp_checksum = {
        let mut sum = checksum_sum(&src_ip.octets());
        sum += checksum_sum(&dst_ip.octets());
        sum += u32::from(6u16);
        sum += u32::from((pkt.len() - 20) as u16);
        sum += checksum_sum(&pkt[20..]);
        finalize_checksum(sum)
    };
    pkt[36..38].copy_from_slice(&tcp_checksum.to_be_bytes());
    pkt
}

pub(crate) fn build_ipv6_tcp_syn_packet(src_ip: Ipv6Addr, dst_ip: Ipv6Addr, src_port: u16, dst_port: u16) -> Vec<u8> {
    let mut pkt = vec![0u8; 60];
    pkt[0] = 0x60;
    pkt[4..6].copy_from_slice(&20u16.to_be_bytes());
    pkt[6] = 6;
    pkt[7] = 64;
    pkt[8..24].copy_from_slice(&src_ip.octets());
    pkt[24..40].copy_from_slice(&dst_ip.octets());
    pkt[40..42].copy_from_slice(&src_port.to_be_bytes());
    pkt[42..44].copy_from_slice(&dst_port.to_be_bytes());
    pkt[52] = 0x50;
    pkt[53] = 0x02;
    let tcp_len = u32::try_from(pkt.len() - 40).expect("tcp length");
    let mut sum = checksum_sum(&src_ip.octets());
    sum += checksum_sum(&dst_ip.octets());
    sum += (tcp_len >> 16) + (tcp_len & 0xFFFF);
    sum += u32::from(6u16);
    sum += checksum_sum(&pkt[40..]);
    let tcp_checksum = finalize_checksum(sum);
    pkt[56..58].copy_from_slice(&tcp_checksum.to_be_bytes());
    pkt
}
