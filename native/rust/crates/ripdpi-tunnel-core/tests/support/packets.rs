//! Raw IPv4 packet construction helpers for TUN E2E tests.
//!
//! These build valid IPv4/TCP and IPv4/UDP packets with correct checksums,
//! suitable for injection into a TUN file descriptor or socketpair.

// ── Checksum helpers ─────────────────────────────────────────────────────────

fn checksum_sum(bytes: &[u8]) -> u32 {
    let mut sum = 0u32;
    let mut chunks = bytes.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u32::from(u16::from_be_bytes([chunk[0], chunk[1]]));
    }
    if let Some(last) = chunks.remainder().first() {
        sum += u32::from(*last) << 8;
    }
    sum
}

fn finalize_checksum(mut sum: u32) -> u16 {
    while sum >> 16 != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

fn tcp_checksum_ipv4(src_ip: [u8; 4], dst_ip: [u8; 4], tcp_segment: &[u8]) -> u16 {
    let mut sum = checksum_sum(&src_ip);
    sum += checksum_sum(&dst_ip);
    sum += u32::from(6u16); // protocol TCP
    sum += u32::from(tcp_segment.len() as u16);
    sum += checksum_sum(tcp_segment);
    finalize_checksum(sum)
}

fn udp_checksum_ipv4(src_ip: [u8; 4], dst_ip: [u8; 4], udp_segment: &[u8]) -> u16 {
    let mut sum = checksum_sum(&src_ip);
    sum += checksum_sum(&dst_ip);
    sum += u32::from(17u16); // protocol UDP
    sum += u32::from(udp_segment.len() as u16);
    sum += checksum_sum(udp_segment);
    let result = finalize_checksum(sum);
    // UDP checksum of 0 means "no checksum"; use 0xFFFF instead.
    if result == 0 { 0xFFFF } else { result }
}

fn set_ip_header_checksum(pkt: &mut [u8]) {
    pkt[10] = 0;
    pkt[11] = 0;
    let checksum = finalize_checksum(checksum_sum(&pkt[..20]));
    pkt[10..12].copy_from_slice(&checksum.to_be_bytes());
}

// ── IPv4 header builder ──────────────────────────────────────────────────────

fn build_ipv4_header(
    buf: &mut [u8],
    total_len: u16,
    protocol: u8,
    src_ip: [u8; 4],
    dst_ip: [u8; 4],
) {
    buf[0] = 0x45; // version=4, IHL=5
    buf[2..4].copy_from_slice(&total_len.to_be_bytes());
    buf[4] = 0x00;
    buf[5] = 0x01; // ID
    buf[6] = 0x40; // DF flag
    buf[7] = 0x00;
    buf[8] = 64; // TTL
    buf[9] = protocol;
    buf[12..16].copy_from_slice(&src_ip);
    buf[16..20].copy_from_slice(&dst_ip);
}

// ── TCP packet builders ──────────────────────────────────────────────────────

/// Build a raw IPv4/TCP SYN packet (no payload, with valid checksums).
pub fn build_tcp_syn(
    src_ip: [u8; 4],
    dst_ip: [u8; 4],
    src_port: u16,
    dst_port: u16,
) -> Vec<u8> {
    let mut pkt = vec![0u8; 40]; // 20-byte IPv4 + 20-byte TCP
    build_ipv4_header(&mut pkt, 40, 6, src_ip, dst_ip);
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    // seq = 0 (implicit from zeroed buffer)
    pkt[32] = 0x50; // data offset = 5 (20 bytes)
    pkt[33] = 0x02; // SYN flag
    pkt[34] = 0xFF;
    pkt[35] = 0xFF; // window = 65535
    set_ip_header_checksum(&mut pkt);
    let tcp_cksum = tcp_checksum_ipv4(src_ip, dst_ip, &pkt[20..]);
    pkt[36..38].copy_from_slice(&tcp_cksum.to_be_bytes());
    pkt
}

/// Build an IPv4/TCP ACK packet.
pub fn build_tcp_ack(
    src_ip: [u8; 4],
    dst_ip: [u8; 4],
    src_port: u16,
    dst_port: u16,
    seq: u32,
    ack: u32,
) -> Vec<u8> {
    let mut pkt = vec![0u8; 40];
    build_ipv4_header(&mut pkt, 40, 6, src_ip, dst_ip);
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    pkt[24..28].copy_from_slice(&seq.to_be_bytes());
    pkt[28..32].copy_from_slice(&ack.to_be_bytes());
    pkt[32] = 0x50; // data offset
    pkt[33] = 0x10; // ACK flag
    pkt[34] = 0xFF;
    pkt[35] = 0xFF; // window
    set_ip_header_checksum(&mut pkt);
    let tcp_cksum = tcp_checksum_ipv4(src_ip, dst_ip, &pkt[20..]);
    pkt[36..38].copy_from_slice(&tcp_cksum.to_be_bytes());
    pkt
}

/// Build an IPv4/TCP PSH+ACK packet carrying `payload`.
pub fn build_tcp_psh(
    src_ip: [u8; 4],
    dst_ip: [u8; 4],
    src_port: u16,
    dst_port: u16,
    seq: u32,
    ack: u32,
    payload: &[u8],
) -> Vec<u8> {
    let total_len = 40 + payload.len();
    let mut pkt = vec![0u8; total_len];
    build_ipv4_header(&mut pkt, total_len as u16, 6, src_ip, dst_ip);
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    pkt[24..28].copy_from_slice(&seq.to_be_bytes());
    pkt[28..32].copy_from_slice(&ack.to_be_bytes());
    pkt[32] = 0x50; // data offset = 5
    pkt[33] = 0x18; // PSH + ACK
    pkt[34] = 0xFF;
    pkt[35] = 0xFF; // window
    pkt[40..].copy_from_slice(payload);
    set_ip_header_checksum(&mut pkt);
    let tcp_cksum = tcp_checksum_ipv4(src_ip, dst_ip, &pkt[20..]);
    pkt[36..38].copy_from_slice(&tcp_cksum.to_be_bytes());
    pkt
}

/// Extract (seq, ack) from a raw IPv4/TCP packet.
pub fn tcp_seq_ack(pkt: &[u8]) -> (u32, u32) {
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    let seq = u32::from_be_bytes([pkt[ihl + 4], pkt[ihl + 5], pkt[ihl + 6], pkt[ihl + 7]]);
    let ack = u32::from_be_bytes([pkt[ihl + 8], pkt[ihl + 9], pkt[ihl + 10], pkt[ihl + 11]]);
    (seq, ack)
}

/// Extract TCP flags byte from a raw IPv4/TCP packet.
pub fn tcp_flags(pkt: &[u8]) -> u8 {
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    pkt[ihl + 13]
}

/// Extract TCP payload from a raw IPv4/TCP packet.
pub fn tcp_payload(pkt: &[u8]) -> &[u8] {
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    let data_offset = ((pkt[ihl + 12] >> 4) as usize) * 4;
    &pkt[ihl + data_offset..]
}

/// TCP flag constants.
pub const TCP_SYN: u8 = 0x02;
pub const TCP_ACK: u8 = 0x10;
pub const TCP_PSH: u8 = 0x08;
pub const TCP_FIN: u8 = 0x01;
pub const TCP_RST: u8 = 0x04;

/// Build a raw IPv4/UDP packet.
pub fn build_udp_packet(
    src_ip: [u8; 4],
    dst_ip: [u8; 4],
    src_port: u16,
    dst_port: u16,
    payload: &[u8],
) -> Vec<u8> {
    let udp_len = 8 + payload.len();
    let total_len = 20 + udp_len;
    let mut pkt = vec![0u8; total_len];
    build_ipv4_header(&mut pkt, total_len as u16, 17, src_ip, dst_ip);
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    pkt[24..26].copy_from_slice(&(udp_len as u16).to_be_bytes());
    // UDP checksum at [26..28]
    pkt[28..28 + payload.len()].copy_from_slice(payload);
    set_ip_header_checksum(&mut pkt);
    let udp_cksum = udp_checksum_ipv4(src_ip, dst_ip, &pkt[20..]);
    pkt[26..28].copy_from_slice(&udp_cksum.to_be_bytes());
    pkt
}
