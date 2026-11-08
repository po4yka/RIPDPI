use std::net::{IpAddr, SocketAddr};

use etherparse::{Ipv6ExtensionSlice, LaxNetSlice, LaxSlicedPacket, NetSlice, SlicedPacket, TransportSlice};

pub(crate) const IPV4_MIN_HEADER_LEN: usize = 20;
pub(crate) const IPV6_HEADER_LEN: usize = 40;
pub(crate) const TCP_PROTO: u8 = 6;
pub(crate) const UDP_PROTO: u8 = 17;
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
    payload_end: usize,
    pub(super) packet_end: usize,
}

impl PacketMeta {
    pub(crate) fn parse(packet: &[u8]) -> Option<Self> {
        transport_endpoint(packet).filter(|endpoint| endpoint.valid_checksums).map(|endpoint| Self {
            transport: endpoint.transport,
            is_ipv6: endpoint.is_ipv6,
            src_ip: endpoint.src_ip,
            dst_ip: endpoint.dst_ip,
            transport_offset: endpoint.transport_offset,
            payload_offset: endpoint.payload_offset,
            payload_end: endpoint.payload_end,
            packet_end: endpoint.packet_end,
            src_port: endpoint.src_port,
            dst_port: endpoint.dst_port,
        })
    }

    pub(crate) fn flow_id(self) -> u64 {
        flow_id(self.transport, self.src_addr(), self.dst_addr())
    }

    pub(crate) fn src_addr(self) -> SocketAddr {
        SocketAddr::new(self.src_ip, self.src_port)
    }

    pub(crate) fn dst_addr(self) -> SocketAddr {
        SocketAddr::new(self.dst_ip, self.dst_port)
    }

    pub(crate) fn is_tcp_terminal(self, packet: &[u8]) -> bool {
        self.transport == Transport::Tcp
            && packet.get(self.transport_offset + 13).is_some_and(|flags| flags & 0x05 != 0)
    }

    pub(crate) fn payload(self, packet: &[u8]) -> &[u8] {
        packet.get(self.payload_offset..self.payload_end).unwrap_or_default()
    }

    /// Whether `candidate` is a whole IP packet of this packet's flow: same transport, addresses and
    /// ports, and an IP length field that covers exactly `candidate`. Bytes that only parse as a
    /// packet header (an app payload slice, a crafted string) do not qualify.
    pub(crate) fn is_complete_packet_of_flow(self, candidate: &[u8]) -> bool {
        let Some((transport, source, destination, declared_len)) = output_packet_flow(candidate) else {
            return false;
        };
        declared_len == candidate.len()
            && transport == self.transport
            && source == self.src_addr()
            && destination == self.dst_addr()
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Transport {
    Tcp,
    Udp,
}

pub(crate) fn flow_id(transport: Transport, src: SocketAddr, dst: SocketAddr) -> u64 {
    let ports = (u64::from(src.port()) << 16) | u64::from(dst.port());
    let protocol = match transport {
        Transport::Tcp => u64::from(TCP_PROTO),
        Transport::Udp => u64::from(UDP_PROTO),
    };
    ports ^ ip_hash(src.ip()).rotate_left(13) ^ ip_hash(dst.ip()).rotate_left(29) ^ protocol.rotate_left(47)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct TransportEndpoint {
    valid_checksums: bool,
    transport: Transport,
    is_ipv6: bool,
    src_ip: IpAddr,
    dst_ip: IpAddr,
    transport_offset: usize,
    payload_offset: usize,
    payload_end: usize,
    packet_end: usize,
    src_port: u16,
    dst_port: u16,
}

pub(crate) fn packet_destination(packet: &[u8]) -> Option<SocketAddr> {
    output_packet_flow(packet).map(|(_, _, destination, _)| destination)
}

fn output_packet_flow(packet: &[u8]) -> Option<(Transport, SocketAddr, SocketAddr, usize)> {
    // Trusted strategy outputs can deliberately alter transport lengths or checksums.
    let parsed = LaxSlicedPacket::from_ip(packet).ok()?;
    let (source, destination, declared_len) = match parsed.net? {
        LaxNetSlice::Ipv4(ip) => (
            IpAddr::V4(ip.header().source_addr()),
            IpAddr::V4(ip.header().destination_addr()),
            usize::from(ip.header().total_len()),
        ),
        LaxNetSlice::Ipv6(ip) => (
            IpAddr::V6(ip.header().source_addr()),
            IpAddr::V6(ip.header().destination_addr()),
            IPV6_HEADER_LEN + usize::from(ip.header().payload_length()),
        ),
        LaxNetSlice::Arp(_) => return None,
    };
    let (transport, source_port, destination_port) = match parsed.transport? {
        TransportSlice::Tcp(tcp) => (Transport::Tcp, tcp.source_port(), tcp.destination_port()),
        TransportSlice::Udp(udp) => (Transport::Udp, udp.source_port(), udp.destination_port()),
        _ => return None,
    };
    Some((
        transport,
        SocketAddr::new(source, source_port),
        SocketAddr::new(destination, destination_port),
        declared_len,
    ))
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
    let output_len = meta.payload_offset.checked_add(payload.len())?;
    let ip_length = if meta.is_ipv6 { output_len.checked_sub(IPV6_HEADER_LEN)? } else { output_len };
    u16::try_from(ip_length).ok()?;
    let mut output = Vec::with_capacity(output_len);
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
    let parsed = SlicedPacket::from_ip(packet).ok()?;
    let net = parsed.net?;
    let (src_ip, dst_ip, packet_end, valid_ip_checksum) = match &net {
        NetSlice::Ipv4(ip) => {
            if ip.header().is_fragmenting_payload() || ![TCP_PROTO, UDP_PROTO].contains(&packet[9]) {
                return None;
            }
            (
                IpAddr::V4(ip.header().source_addr()),
                IpAddr::V4(ip.header().destination_addr()),
                usize::from(ip.header().total_len()),
                checksum_matches(ip.header().to_header().calc_header_checksum(), ip.header().header_checksum()),
            )
        }
        NetSlice::Ipv6(ip) => {
            // Jumbograms are outside the TUN packet contract; do not use the parser's fallback.
            if ip.header().payload_length() == 0 {
                return None;
            }
            for extension in ip.extensions().clone() {
                match extension {
                    Ipv6ExtensionSlice::HopByHop(options) | Ipv6ExtensionSlice::DestinationOptions(options) => {
                        if !options_are_forwardable(&options.slice()[2..]) {
                            return None;
                        }
                    }
                    Ipv6ExtensionSlice::Routing(routing) if routing.slice()[3] != 0 => return None,
                    Ipv6ExtensionSlice::Fragment(fragment) if fragment.is_fragmenting_payload() => return None,
                    Ipv6ExtensionSlice::Authentication(_) => return None,
                    _ => {}
                }
            }
            (
                IpAddr::V6(ip.header().source_addr()),
                IpAddr::V6(ip.header().destination_addr()),
                IPV6_HEADER_LEN + usize::from(ip.header().payload_length()),
                true,
            )
        }
        NetSlice::Arp(_) => return None,
    };
    let (transport, bytes, header_len, src_port, dst_port, valid_transport_checksum) = match parsed.transport? {
        TransportSlice::Tcp(tcp) => {
            let header = tcp.to_header();
            let expected = match &net {
                NetSlice::Ipv4(ip) => {
                    header.calc_checksum_ipv4_raw(ip.header().source(), ip.header().destination(), tcp.payload())
                }
                NetSlice::Ipv6(ip) => {
                    header.calc_checksum_ipv6_raw(ip.header().source(), ip.header().destination(), tcp.payload())
                }
                NetSlice::Arp(_) => return None,
            }
            .ok()?;
            (
                Transport::Tcp,
                tcp.slice(),
                tcp.header_slice().len(),
                tcp.source_port(),
                tcp.destination_port(),
                checksum_matches(expected, tcp.checksum()),
            )
        }
        TransportSlice::Udp(udp) => {
            if usize::from(udp.length()) < etherparse::UdpHeader::LEN {
                return None;
            }
            let header = udp.to_header();
            let valid = match &net {
                NetSlice::Ipv4(ip) => {
                    udp.checksum() == 0
                        || header
                            .calc_checksum_ipv4_raw(ip.header().source(), ip.header().destination(), udp.payload())
                            .is_ok_and(|checksum| checksum == udp.checksum())
                }
                NetSlice::Ipv6(ip) => {
                    udp.checksum() != 0
                        && header
                            .calc_checksum_ipv6_raw(ip.header().source(), ip.header().destination(), udp.payload())
                            .is_ok_and(|checksum| checksum == udp.checksum())
                }
                NetSlice::Arp(_) => false,
            };
            (Transport::Udp, udp.slice(), etherparse::UdpHeader::LEN, udp.source_port(), udp.destination_port(), valid)
        }
        _ => return None,
    };
    let transport_offset = (bytes.as_ptr() as usize).checked_sub(packet.as_ptr() as usize)?;
    let payload_offset = transport_offset.checked_add(header_len)?;
    let payload_end = transport_offset.checked_add(bytes.len())?;
    if payload_end > packet_end || packet_end > packet.len() {
        return None;
    }
    Some(TransportEndpoint {
        transport,
        is_ipv6: src_ip.is_ipv6(),
        src_ip,
        dst_ip,
        transport_offset,
        payload_offset,
        payload_end,
        packet_end,
        src_port,
        dst_port,
        valid_checksums: valid_ip_checksum && valid_transport_checksum,
    })
}

fn checksum_matches(expected: u16, actual: u16) -> bool {
    // A computed zero can use either one's-complement zero encoding on the wire.
    expected == actual || (expected == 0 && actual == u16::MAX)
}

fn options_are_forwardable(mut options: &[u8]) -> bool {
    while let Some(&kind) = options.first() {
        if kind == 0 {
            options = &options[1..];
            continue;
        }
        let Some(&length) = options.get(1) else { return false };
        let Some(rest) = options.get(usize::from(length) + 2..) else { return false };
        if kind & 0xc0 != 0 {
            return false;
        }
        options = rest;
    }
    true
}

pub(crate) fn low_ttl_tcp_copy(packet: &[u8], ttl: u8) -> Option<Vec<u8>> {
    let meta = PacketMeta::parse(packet)?;
    if meta.transport != Transport::Tcp {
        return None;
    }
    let mut modified = packet[..meta.packet_end].to_vec();
    set_packet_hop_limit(&mut modified, ttl)?;
    Some(modified)
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
        let payload_len = u16::try_from(packet.len().checked_sub(IPV6_HEADER_LEN)?).ok()?;
        packet[4..6].copy_from_slice(&payload_len.to_be_bytes());
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

#[cfg(test)]
mod tests {
    use super::{PacketMeta, low_ttl_tcp_copy, packet_with_payload};
    use crate::egress::upstream_vectors::*;

    #[test]
    fn truncated_tcp_headers_are_rejected() {
        let mut ipv4 = vec![0; 24];
        ipv4[0] = 0x45;
        ipv4[9] = 6;
        assert!(PacketMeta::parse(&ipv4).is_none());

        let mut ipv6 = vec![0; 44];
        ipv6[0] = 0x60;
        ipv6[6] = 6;
        assert!(PacketMeta::parse(&ipv6).is_none());
    }

    #[test]
    fn fragmented_packets_are_rejected() {
        let mut ipv4 = vec![0; 40];
        ipv4[0] = 0x45;
        ipv4[2..4].copy_from_slice(&40u16.to_be_bytes());
        ipv4[6..8].copy_from_slice(&1u16.to_be_bytes());
        ipv4[9] = 6;
        ipv4[20 + 12] = 0x50;
        assert!(PacketMeta::parse(&ipv4).is_none());

        let mut ipv6 = vec![0; 68];
        ipv6[0] = 0x60;
        ipv6[4..6].copy_from_slice(&28u16.to_be_bytes());
        ipv6[6] = 44;
        ipv6[40] = 6;
        ipv6[42..44].copy_from_slice(&8u16.to_be_bytes());
        ipv6[48 + 12] = 0x50;
        assert!(PacketMeta::parse(&ipv6).is_none());

        ipv4[6..8].copy_from_slice(&0x2000u16.to_be_bytes());
        assert!(PacketMeta::parse(&ipv4).is_none());
        ipv6[42..44].copy_from_slice(&1u16.to_be_bytes());
        assert!(PacketMeta::parse(&ipv6).is_none());

        ipv4[6..8].copy_from_slice(&0x4000u16.to_be_bytes());
        assert!(super::transport_endpoint(&ipv4).is_some());
        ipv6[42..44].copy_from_slice(&0u16.to_be_bytes());
        assert!(super::transport_endpoint(&ipv6).is_some());
    }
    #[test]
    fn upstream_option_packets_preserve_headers_and_recompute_checksums() {
        for (original, rewrite) in [
            (IPV4_TCP, IPV4_TCP_REWRITE),
            (IPV6_TCP, IPV6_TCP_REWRITE),
            (IPV6_HOP_TCP, IPV6_HOP_TCP_REWRITE),
            (IPV4_UDP, IPV4_UDP_REWRITE),
            (IPV6_HOP_UDP, IPV6_HOP_UDP_REWRITE),
        ] {
            let packet = decode(original);
            let meta = PacketMeta::parse(&packet).unwrap();
            assert_eq!(meta.src_port, 49152);
            assert_eq!(meta.dst_port, 443);
            assert_eq!(meta.payload(&packet), b"abcdefg");
            let expected_src = if meta.is_ipv6 { "[2001:db8::10]:49152" } else { "192.0.2.10:49152" };
            assert_eq!(meta.src_addr().to_string(), expected_src);
            let output = packet_with_payload(&packet, meta, b"\0new\xff", 0, None).unwrap();
            assert_eq!(output, decode(rewrite));
            // Input checksum bytes must not be trusted when producing a new packet.
            let mut corrupt = packet.clone();
            corrupt[meta.transport_offset + if meta.transport == super::Transport::Tcp { 16 } else { 6 }] ^= 1;
            assert_eq!(packet_with_payload(&corrupt, meta, b"\0new\xff", 0, None).unwrap(), output);
        }
        // The generator's keepsum variant is the same packet with an omitted IPv4 UDP checksum, which
        // the kernel never emits; rewritten packets carry the computed one, as in zapret2.
        let mut upstream = decode(IPV4_UDP_REWRITE);
        upstream[30..32].fill(0);
        assert_eq!(upstream, decode(IPV4_UDP_REWRITE_ZERO_SUM));
    }

    #[test]
    fn upstream_tcp_split_checksums_include_options_and_sequence_wrap() {
        for (original, first, second) in [
            (IPV4_TCP, IPV4_TCP_FIRST, IPV4_TCP_SECOND),
            (IPV6_TCP, IPV6_TCP_FIRST, IPV6_TCP_SECOND),
            (IPV6_HOP_TCP, IPV6_HOP_TCP_FIRST, IPV6_HOP_TCP_SECOND),
        ] {
            let packet = decode(original);
            let meta = PacketMeta::parse(&packet).unwrap();
            assert_eq!(packet_with_payload(&packet, meta, b"ab", 0, None).unwrap(), decode(first));
            let output = packet_with_payload(&packet, meta, b"cdefg", 2, None).unwrap();
            assert_eq!(output, decode(second));
            assert_eq!(&output[meta.transport_offset + 4..meta.transport_offset + 8], &[0; 4]);
        }
    }

    #[test]
    fn upstream_low_ttl_preserves_transport_checksum_and_original() {
        for (original, expected) in [(IPV4_TCP, IPV4_TCP_LOW_TTL), (IPV6_TCP, IPV6_TCP_LOW_TTL)] {
            let packet = decode(original);
            assert_eq!(low_ttl_tcp_copy(&packet, 3).unwrap(), decode(expected));
            assert_eq!(packet, decode(original));
        }
    }

    #[test]
    fn upstream_unsupported_and_truncated_headers_are_rejected() {
        assert!(PacketMeta::parse(&decode(IPV6_AH_UDP)).is_none());
        for original in [IPV4_TCP, IPV6_TCP, IPV6_HOP_TCP, IPV4_UDP, IPV6_HOP_UDP] {
            let packet = decode(original);
            let meta = PacketMeta::parse(&packet).unwrap();
            for length in 0..meta.payload_offset {
                assert!(PacketMeta::parse(&packet[..length]).is_none(), "header prefix {length}");
            }
        }
    }
}

#[cfg(test)]
mod boundary_tests;

#[cfg(test)]
mod output_size_tests;
