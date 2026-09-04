use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

pub(crate) const IPV4_MIN_HEADER_LEN: usize = 20;
pub(crate) const IPV6_HEADER_LEN: usize = 40;
pub(crate) const TCP_PROTO: u8 = 6;
pub(crate) const UDP_PROTO: u8 = 17;
const IPV6_HOP_BY_HOP: u8 = 0;
const IPV6_ROUTING: u8 = 43;
const IPV6_FRAGMENT: u8 = 44;
const IPV6_DESTINATION_OPTIONS: u8 = 60;
const TCP_SEQUENCE_OFFSET: usize = 4;
const TCP_CHECKSUM_OFFSET: usize = 16;
const UDP_LEN_OFFSET: usize = 4;
const UDP_CHECKSUM_OFFSET: usize = 6;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct PacketMeta {
    pub(crate) transport: Transport,
    pub(crate) is_ipv6: bool,
    pub(crate) src_port: u16,
    pub(crate) dst_port: u16,
    src_ip: IpAddr,
    dst_ip: IpAddr,
    transport_offset: usize,
    payload_offset: usize,
}

impl PacketMeta {
    pub(crate) fn parse(packet: &[u8]) -> Option<Self> {
        transport_endpoint(packet).map(|endpoint| Self {
            transport: endpoint.transport,
            is_ipv6: endpoint.is_ipv6,
            src_ip: endpoint.src_ip,
            dst_ip: endpoint.dst_ip,
            transport_offset: endpoint.transport_offset,
            payload_offset: endpoint.payload_offset,
            src_port: endpoint.src_port,
            dst_port: endpoint.dst_port,
        })
    }

    pub(crate) fn flow_id(self) -> u64 {
        let ports = (u64::from(self.src_port) << 16) | u64::from(self.dst_port);
        ports ^ ip_hash(self.src_ip).rotate_left(13) ^ ip_hash(self.dst_ip).rotate_left(29)
    }

    pub(crate) fn payload(self, packet: &[u8]) -> &[u8] {
        packet.get(self.payload_offset..).unwrap_or_default()
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Transport {
    Tcp,
    Udp,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct TransportEndpoint {
    transport: Transport,
    is_ipv6: bool,
    src_ip: IpAddr,
    dst_ip: IpAddr,
    transport_offset: usize,
    payload_offset: usize,
    src_port: u16,
    dst_port: u16,
}

pub(crate) fn packet_destination(packet: &[u8]) -> Option<SocketAddr> {
    let endpoint = transport_endpoint(packet)?;
    Some(SocketAddr::new(endpoint.dst_ip, endpoint.dst_port))
}

pub(crate) fn packet_with_payload(
    packet: &[u8],
    meta: PacketMeta,
    payload: &[u8],
    sequence_delta: u32,
    ttl: Option<u8>,
) -> Option<Vec<u8>> {
    if packet.len() < meta.payload_offset {
        return None;
    }
    let mut output = Vec::with_capacity(meta.payload_offset + payload.len());
    output.extend_from_slice(&packet[..meta.payload_offset]);
    output.extend_from_slice(payload);
    update_ip_lengths_and_ttl(&mut output, meta, ttl)?;
    update_transport_header(&mut output, meta, sequence_delta)?;
    Some(output)
}

pub(crate) fn set_packet_hop_limit(packet: &mut [u8], ttl: u8) -> Option<()> {
    match packet.first()? >> 4 {
        4 => {
            if packet.len() < IPV4_MIN_HEADER_LEN {
                return None;
            }
            let ihl = usize::from(packet[0] & 0x0f) * 4;
            if ihl < IPV4_MIN_HEADER_LEN || packet.len() < ihl {
                return None;
            }
            packet[8] = ttl.max(1);
            recompute_ipv4_header_checksum(&mut packet[..ihl]);
        }
        6 => {
            if packet.len() < IPV6_HEADER_LEN {
                return None;
            }
            packet[7] = ttl.max(1);
        }
        _ => return None,
    }
    Some(())
}

pub(crate) fn transport_endpoint(packet: &[u8]) -> Option<TransportEndpoint> {
    let version = packet.first()? >> 4;
    match version {
        4 => ipv4_transport_endpoint(packet),
        6 => ipv6_transport_endpoint(packet),
        _ => None,
    }
}

pub(crate) fn low_ttl_tcp_copy(packet: &[u8], ttl: u8) -> Option<Vec<u8>> {
    let version = packet.first()? >> 4;
    match version {
        4 => low_ttl_ipv4_tcp_copy(packet, ttl),
        6 => low_ttl_ipv6_tcp_copy(packet, ttl),
        _ => None,
    }
}

fn ip_hash(ip: IpAddr) -> u64 {
    match ip {
        IpAddr::V4(value) => u64::from(u32::from(value)),
        IpAddr::V6(value) => {
            let octets = value.octets();
            u64::from_be_bytes(octets[..8].try_into().unwrap_or([0; 8]))
                ^ u64::from_be_bytes(octets[8..].try_into().unwrap_or([0; 8]))
        }
    }
}

fn update_ip_lengths_and_ttl(packet: &mut [u8], meta: PacketMeta, ttl: Option<u8>) -> Option<()> {
    if meta.is_ipv6 {
        if packet.len() < IPV6_HEADER_LEN {
            return None;
        }
        let payload_len = packet.len().checked_sub(IPV6_HEADER_LEN)?;
        packet[4..6].copy_from_slice(&(payload_len as u16).to_be_bytes());
        if let Some(ttl) = ttl {
            packet[7] = ttl.max(1);
        }
    } else {
        if packet.len() < IPV4_MIN_HEADER_LEN || packet.len() > usize::from(u16::MAX) {
            return None;
        }
        let packet_len = packet.len() as u16;
        packet[2..4].copy_from_slice(&packet_len.to_be_bytes());
        if let Some(ttl) = ttl {
            packet[8] = ttl.max(1);
        }
        let ihl = usize::from(packet[0] & 0x0f) * 4;
        recompute_ipv4_header_checksum(&mut packet[..ihl]);
    }
    Some(())
}

fn update_transport_header(packet: &mut [u8], meta: PacketMeta, sequence_delta: u32) -> Option<()> {
    match meta.transport {
        Transport::Tcp => {
            if sequence_delta != 0 {
                let sequence_offset = meta.transport_offset.checked_add(TCP_SEQUENCE_OFFSET)?;
                let sequence_end = sequence_offset.checked_add(4)?;
                let sequence = u32::from_be_bytes(packet.get(sequence_offset..sequence_end)?.try_into().ok()?);
                packet[sequence_offset..sequence_end]
                    .copy_from_slice(&sequence.wrapping_add(sequence_delta).to_be_bytes());
            }
            recompute_transport_checksum(packet, meta, TCP_PROTO)
        }
        Transport::Udp => {
            let udp_len = packet.len().checked_sub(meta.transport_offset)?;
            if udp_len > usize::from(u16::MAX) {
                return None;
            }
            let len_offset = meta.transport_offset.checked_add(UDP_LEN_OFFSET)?;
            packet[len_offset..len_offset + 2].copy_from_slice(&(udp_len as u16).to_be_bytes());
            recompute_transport_checksum(packet, meta, UDP_PROTO)
        }
    }
}

fn recompute_transport_checksum(packet: &mut [u8], meta: PacketMeta, next_header: u8) -> Option<()> {
    let checksum_offset = match meta.transport {
        Transport::Tcp => meta.transport_offset.checked_add(TCP_CHECKSUM_OFFSET)?,
        Transport::Udp => meta.transport_offset.checked_add(UDP_CHECKSUM_OFFSET)?,
    };
    let checksum_end = checksum_offset.checked_add(2)?;
    if packet.len() < checksum_end || packet.len() < meta.transport_offset {
        return None;
    }
    packet[checksum_offset..checksum_end].fill(0);
    if !meta.is_ipv6 && meta.transport == Transport::Udp {
        return Some(());
    }

    let transport_len = packet.len().checked_sub(meta.transport_offset)?;
    let mut sum = 0u32;
    match (meta.src_ip, meta.dst_ip) {
        (IpAddr::V4(src), IpAddr::V4(dst)) => {
            sum += checksum_sum(&src.octets());
            sum += checksum_sum(&dst.octets());
            sum += u32::from(next_header);
            sum += checksum_sum(&(transport_len as u16).to_be_bytes());
        }
        (IpAddr::V6(src), IpAddr::V6(dst)) => {
            sum += checksum_sum(&src.octets());
            sum += checksum_sum(&dst.octets());
            sum += checksum_sum(&(transport_len as u32).to_be_bytes());
            sum += u32::from(next_header);
        }
        _ => return None,
    }
    sum += checksum_sum(&packet[meta.transport_offset..]);
    let checksum = finalize_checksum(sum);
    packet[checksum_offset..checksum_end].copy_from_slice(&if checksum == 0 { 0xffff } else { checksum }.to_be_bytes());
    Some(())
}

fn ipv4_transport_endpoint(packet: &[u8]) -> Option<TransportEndpoint> {
    if packet.len() < IPV4_MIN_HEADER_LEN {
        return None;
    }
    let ihl = usize::from(packet[0] & 0x0f) * 4;
    if ihl < IPV4_MIN_HEADER_LEN || packet.len() < ihl + 4 {
        return None;
    }
    let transport = match packet[9] {
        TCP_PROTO => Transport::Tcp,
        UDP_PROTO => Transport::Udp,
        _ => return None,
    };
    let payload_offset = match transport {
        Transport::Tcp => {
            let tcp_header_len = usize::from(packet[ihl + 12] >> 4) * 4;
            if tcp_header_len < 20 || packet.len() < ihl + tcp_header_len {
                return None;
            }
            ihl + tcp_header_len
        }
        Transport::Udp => {
            if packet.len() < ihl + 8 {
                return None;
            }
            ihl + 8
        }
    };
    Some(TransportEndpoint {
        transport,
        is_ipv6: false,
        src_ip: IpAddr::V4(Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15])),
        dst_ip: IpAddr::V4(Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19])),
        transport_offset: ihl,
        payload_offset,
        src_port: u16::from_be_bytes([packet[ihl], packet[ihl + 1]]),
        dst_port: u16::from_be_bytes([packet[ihl + 2], packet[ihl + 3]]),
    })
}

fn ipv6_transport_endpoint(packet: &[u8]) -> Option<TransportEndpoint> {
    if packet.len() < IPV6_HEADER_LEN {
        return None;
    }
    let src = Ipv6Addr::from(<[u8; 16]>::try_from(&packet[8..24]).ok()?);
    let dst = Ipv6Addr::from(<[u8; 16]>::try_from(&packet[24..40]).ok()?);
    let mut next_header = packet[6];
    let mut offset = IPV6_HEADER_LEN;
    for _ in 0..8 {
        let transport = match next_header {
            TCP_PROTO => Transport::Tcp,
            UDP_PROTO => Transport::Udp,
            IPV6_HOP_BY_HOP | IPV6_ROUTING | IPV6_DESTINATION_OPTIONS => {
                if packet.len() < offset + 2 {
                    return None;
                }
                next_header = packet[offset];
                let header_len = (usize::from(packet[offset + 1]) + 1) * 8;
                offset = offset.checked_add(header_len)?;
                continue;
            }
            IPV6_FRAGMENT => {
                if packet.len() < offset + 8 {
                    return None;
                }
                next_header = packet[offset];
                offset = offset.checked_add(8)?;
                continue;
            }
            _ => return None,
        };
        if packet.len() < offset + 4 {
            return None;
        }
        let payload_offset = match transport {
            Transport::Tcp => {
                let tcp_header_len = usize::from(packet[offset + 12] >> 4) * 4;
                if tcp_header_len < 20 || packet.len() < offset + tcp_header_len {
                    return None;
                }
                offset + tcp_header_len
            }
            Transport::Udp => {
                if packet.len() < offset + 8 {
                    return None;
                }
                offset + 8
            }
        };
        return Some(TransportEndpoint {
            transport,
            is_ipv6: true,
            src_ip: IpAddr::V6(src),
            dst_ip: IpAddr::V6(dst),
            transport_offset: offset,
            payload_offset,
            src_port: u16::from_be_bytes([packet[offset], packet[offset + 1]]),
            dst_port: u16::from_be_bytes([packet[offset + 2], packet[offset + 3]]),
        });
    }
    None
}

fn low_ttl_ipv4_tcp_copy(packet: &[u8], ttl: u8) -> Option<Vec<u8>> {
    if packet.len() < IPV4_MIN_HEADER_LEN || packet[9] != TCP_PROTO {
        return None;
    }
    let ihl = usize::from(packet[0] & 0x0f) * 4;
    if ihl < IPV4_MIN_HEADER_LEN || packet.len() < ihl {
        return None;
    }

    let mut modified = packet.to_vec();
    modified[8] = ttl.max(1);
    recompute_ipv4_header_checksum(&mut modified[..ihl]);
    Some(modified)
}

fn low_ttl_ipv6_tcp_copy(packet: &[u8], ttl: u8) -> Option<Vec<u8>> {
    if packet.len() < IPV6_HEADER_LEN || packet[6] != TCP_PROTO {
        return None;
    }

    let mut modified = packet.to_vec();
    modified[7] = ttl.max(1);
    Some(modified)
}

pub(crate) fn recompute_ipv4_header_checksum(header: &mut [u8]) {
    header[10] = 0;
    header[11] = 0;
    let checksum = ipv4_checksum(header);
    header[10..12].copy_from_slice(&checksum.to_be_bytes());
}

fn ipv4_checksum(bytes: &[u8]) -> u16 {
    finalize_checksum(checksum_sum(bytes))
}

fn checksum_sum(bytes: &[u8]) -> u32 {
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

fn finalize_checksum(mut sum: u32) -> u16 {
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}
