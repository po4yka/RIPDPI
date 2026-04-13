#![forbid(unsafe_code)]

use std::net::SocketAddr;

use etherparse::{
    ip_number, IpFragOffset, IpNumber, Ipv4Header, Ipv6FlowLabel, Ipv6FragmentHeader, Ipv6Header, TcpHeader,
    TcpHeaderSlice, TcpOptionElement, UdpHeader,
};
use thiserror::Error;

const IP_FRAGMENT_ALIGNMENT_BYTES: usize = 8;
const TCP_FLAG_FIN: u16 = 0x001;
const TCP_FLAG_SYN: u16 = 0x002;
const TCP_FLAG_RST: u16 = 0x004;
const TCP_FLAG_PSH: u16 = 0x008;
const TCP_FLAG_ACK: u16 = 0x010;
const TCP_FLAG_URG: u16 = 0x020;
const TCP_FLAG_ECE: u16 = 0x040;
const TCP_FLAG_CWR: u16 = 0x080;
const TCP_FLAG_AE: u16 = 0x100;
const TCP_FLAG_R1: u16 = 0x200;
const TCP_FLAG_R2: u16 = 0x400;
const TCP_FLAG_R3: u16 = 0x800;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IpFragmentPair {
    pub first: Vec<u8>,
    pub second: Vec<u8>,
    pub effective_transport_split: usize,
}

/// IPv6 extension headers to inject into fragment packets.
///
/// Headers in the unfragmentable part are placed before the Fragment Header
/// in this order: Hop-by-Hop -> Destination Options -> Routing -> Fragment.
/// The fragmentable Destination Options header is placed after the Fragment
/// Header and before the transport payload.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Ipv6ExtHeaders {
    /// Insert a Hop-by-Hop Options header (next_header=0) with pad bytes.
    pub hop_by_hop: bool,
    /// Insert Destination Options header in unfragmentable part (before Fragment Header).
    pub dest_opt: bool,
    /// Insert Destination Options header in fragmentable part (after Fragment Header).
    pub dest_opt_fragmentable: bool,
    /// Insert Routing header (type 0, segments_left=0) in unfragmentable part.
    pub routing: bool,
    /// Override the second fragment's Fragment Header `next_header` field.
    /// Per RFC 8200, only the first fragment's value is used for reassembly.
    /// Setting this confuses DPI that checks per-fragment protocol types.
    pub second_frag_next_override: Option<u8>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UdpFragmentSpec {
    pub src: SocketAddr,
    pub dst: SocketAddr,
    pub ttl: u8,
    pub identification: u32,
    pub ipv6_ext: Ipv6ExtHeaders,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpFragmentSpec {
    pub src: SocketAddr,
    pub dst: SocketAddr,
    pub ttl: u8,
    pub identification: u32,
    pub sequence_number: u32,
    pub acknowledgment_number: u32,
    pub window_size: u16,
    pub timestamp: Option<TcpTimestampOption>,
    pub tcp_flags_set: u16,
    pub tcp_flags_unset: u16,
    pub ipv6_ext: Ipv6ExtHeaders,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpTimestampOption {
    pub value: u32,
    pub echo_reply: u32,
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum BuildError {
    #[error("source and destination socket addresses must use the same IP family")]
    AddressFamilyMismatch,
    #[error("minimum split {requested} rounds to {effective}, which does not leave two non-empty IP fragments for transport length {transport_len}")]
    InvalidSplit { requested: usize, effective: usize, transport_len: usize },
    #[error("fragment payload exceeds protocol limits")]
    ValueTooLarge,
}

pub fn build_udp_fragment_pair(
    spec: UdpFragmentSpec,
    payload: &[u8],
    minimum_transport_split: usize,
) -> Result<IpFragmentPair, BuildError> {
    match (spec.src, spec.dst) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            let mut udp = UdpHeader::without_ipv4_checksum(src.port(), dst.port(), payload.len())
                .map_err(|_| BuildError::ValueTooLarge)?;
            udp.checksum = udp
                .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| BuildError::ValueTooLarge)?;
            let transport = serialize_udp_transport(udp, payload);
            let split = resolve_effective_split(minimum_transport_split, transport.len())?;
            build_ipv4_fragment_pair(
                src.ip().octets(),
                dst.ip().octets(),
                spec.ttl,
                spec.identification as u16,
                ip_number::UDP,
                &transport,
                split,
            )
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            let ip = Ipv6Header {
                traffic_class: 0,
                flow_label: Ipv6FlowLabel::ZERO,
                payload_length: u16::try_from(UdpHeader::LEN + payload.len()).map_err(|_| BuildError::ValueTooLarge)?,
                next_header: ip_number::UDP,
                hop_limit: spec.ttl,
                source: src.ip().octets(),
                destination: dst.ip().octets(),
            };
            let udp = UdpHeader::with_ipv6_checksum(src.port(), dst.port(), &ip, payload)
                .map_err(|_| BuildError::ValueTooLarge)?;
            let transport = serialize_udp_transport(udp, payload);
            let split = resolve_effective_split(minimum_transport_split, transport.len())?;
            build_ipv6_fragment_pair(
                src.ip().octets(),
                dst.ip().octets(),
                spec.ttl,
                spec.identification,
                ip_number::UDP,
                &transport,
                split,
                spec.ipv6_ext,
            )
        }
        _ => Err(BuildError::AddressFamilyMismatch),
    }
}

pub fn build_tcp_fragment_pair(
    spec: TcpFragmentSpec,
    payload: &[u8],
    minimum_payload_split: usize,
) -> Result<IpFragmentPair, BuildError> {
    let mut tcp = TcpHeader::new(spec.src.port(), spec.dst.port(), spec.sequence_number, spec.window_size);
    tcp.ack = true;
    tcp.psh = !payload.is_empty();
    tcp.acknowledgment_number = spec.acknowledgment_number;
    if let Some(timestamp) = spec.timestamp {
        tcp.set_options(&[
            TcpOptionElement::Noop,
            TcpOptionElement::Noop,
            TcpOptionElement::Timestamp(timestamp.value, timestamp.echo_reply),
        ])
        .map_err(|_| BuildError::ValueTooLarge)?;
    }

    let minimum_transport_split =
        tcp.header_len().checked_add(minimum_payload_split).ok_or(BuildError::ValueTooLarge)?;

    match (spec.src, spec.dst) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| BuildError::ValueTooLarge)?;
            let mut transport = serialize_tcp_transport(&tcp, payload);
            apply_tcp_flag_overrides_to_transport(
                &mut transport,
                spec.src,
                spec.dst,
                payload,
                spec.tcp_flags_set,
                spec.tcp_flags_unset,
            )?;
            let split = resolve_effective_split(minimum_transport_split, transport.len())?;
            build_ipv4_fragment_pair(
                src.ip().octets(),
                dst.ip().octets(),
                spec.ttl,
                spec.identification as u16,
                ip_number::TCP,
                &transport,
                split,
            )
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv6_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| BuildError::ValueTooLarge)?;
            let mut transport = serialize_tcp_transport(&tcp, payload);
            apply_tcp_flag_overrides_to_transport(
                &mut transport,
                spec.src,
                spec.dst,
                payload,
                spec.tcp_flags_set,
                spec.tcp_flags_unset,
            )?;
            let split = resolve_effective_split(minimum_transport_split, transport.len())?;
            build_ipv6_fragment_pair(
                src.ip().octets(),
                dst.ip().octets(),
                spec.ttl,
                spec.identification,
                ip_number::TCP,
                &transport,
                split,
                spec.ipv6_ext,
            )
        }
        _ => Err(BuildError::AddressFamilyMismatch),
    }
}

/// Build a single TCP RST+ACK packet for fake RST injection.
///
/// The packet has the correct seq/ack from the live connection but a low TTL
/// so it expires before reaching the server.  DPI processes the RST and clears
/// its connection-tracking state while the real connection continues normally.
pub fn build_fake_rst_packet(spec: &TcpFragmentSpec) -> Result<Vec<u8>, BuildError> {
    let mut tcp = TcpHeader::new(spec.src.port(), spec.dst.port(), spec.sequence_number, 0);
    tcp.rst = true;
    tcp.ack = true;
    tcp.acknowledgment_number = spec.acknowledgment_number;
    // RST packets conventionally carry window=0.
    let payload: &[u8] = &[];

    match (spec.src, spec.dst) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| BuildError::ValueTooLarge)?;
            let mut transport = serialize_tcp_transport(&tcp, payload);
            apply_tcp_flag_overrides_to_transport(
                &mut transport,
                spec.src,
                spec.dst,
                payload,
                spec.tcp_flags_set,
                spec.tcp_flags_unset,
            )?;
            let mut ip = Ipv4Header::new(
                u16::try_from(transport.len()).map_err(|_| BuildError::ValueTooLarge)?,
                spec.ttl,
                ip_number::TCP,
                src.ip().octets(),
                dst.ip().octets(),
            )
            .map_err(|_| BuildError::ValueTooLarge)?;
            ip.identification = spec.identification as u16;
            ip.dont_fragment = true;
            ip.header_checksum = ip.calc_header_checksum();
            Ok(serialize_ipv4_fragment(&ip, &transport))
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv6_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| BuildError::ValueTooLarge)?;
            let mut transport = serialize_tcp_transport(&tcp, payload);
            apply_tcp_flag_overrides_to_transport(
                &mut transport,
                spec.src,
                spec.dst,
                payload,
                spec.tcp_flags_set,
                spec.tcp_flags_unset,
            )?;
            let ip = Ipv6Header {
                traffic_class: 0,
                flow_label: Ipv6FlowLabel::ZERO,
                payload_length: u16::try_from(transport.len()).map_err(|_| BuildError::ValueTooLarge)?,
                next_header: ip_number::TCP,
                hop_limit: spec.ttl,
                source: src.ip().octets(),
                destination: dst.ip().octets(),
            };
            let mut buf = Vec::with_capacity(Ipv6Header::LEN + transport.len());
            ip.write(&mut buf).expect("Vec<u8> write must not fail");
            buf.extend_from_slice(&transport);
            Ok(buf)
        }
        _ => Err(BuildError::AddressFamilyMismatch),
    }
}

fn apply_tcp_flag_overrides_to_transport(
    transport: &mut [u8],
    source: SocketAddr,
    target: SocketAddr,
    payload: &[u8],
    tcp_flags_set: u16,
    tcp_flags_unset: u16,
) -> Result<(), BuildError> {
    if tcp_flags_set == 0 && tcp_flags_unset == 0 {
        return Ok(());
    }
    let data_offset = transport[12] & 0xF0;
    let mut reserved = transport[12] & 0x0F;
    let mut control = transport[13];
    apply_single_flag(&mut control, tcp_flags_set, tcp_flags_unset, TCP_FLAG_FIN, 0x01);
    apply_single_flag(&mut control, tcp_flags_set, tcp_flags_unset, TCP_FLAG_SYN, 0x02);
    apply_single_flag(&mut control, tcp_flags_set, tcp_flags_unset, TCP_FLAG_RST, 0x04);
    apply_single_flag(&mut control, tcp_flags_set, tcp_flags_unset, TCP_FLAG_PSH, 0x08);
    apply_single_flag(&mut control, tcp_flags_set, tcp_flags_unset, TCP_FLAG_ACK, 0x10);
    apply_single_flag(&mut control, tcp_flags_set, tcp_flags_unset, TCP_FLAG_URG, 0x20);
    apply_single_flag(&mut control, tcp_flags_set, tcp_flags_unset, TCP_FLAG_ECE, 0x40);
    apply_single_flag(&mut control, tcp_flags_set, tcp_flags_unset, TCP_FLAG_CWR, 0x80);
    apply_single_flag(&mut reserved, tcp_flags_set, tcp_flags_unset, TCP_FLAG_AE, 0x01);
    apply_single_flag(&mut reserved, tcp_flags_set, tcp_flags_unset, TCP_FLAG_R1, 0x02);
    apply_single_flag(&mut reserved, tcp_flags_set, tcp_flags_unset, TCP_FLAG_R2, 0x04);
    apply_single_flag(&mut reserved, tcp_flags_set, tcp_flags_unset, TCP_FLAG_R3, 0x08);
    transport[12] = data_offset | (reserved & 0x0F);
    transport[13] = control;
    transport[16] = 0;
    transport[17] = 0;
    let header = TcpHeaderSlice::from_slice(transport).map_err(|_| BuildError::ValueTooLarge)?;
    let checksum = match (source, target) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => header
            .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
            .map_err(|_| BuildError::ValueTooLarge)?,
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => header
            .calc_checksum_ipv6_raw(src.ip().octets(), dst.ip().octets(), payload)
            .map_err(|_| BuildError::ValueTooLarge)?,
        _ => return Err(BuildError::AddressFamilyMismatch),
    };
    transport[16..18].copy_from_slice(&checksum.to_be_bytes());
    Ok(())
}

fn apply_single_flag(byte: &mut u8, set_mask: u16, unset_mask: u16, logical_mask: u16, wire_bit: u8) {
    if (set_mask & logical_mask) != 0 {
        *byte |= wire_bit;
    }
    if (unset_mask & logical_mask) != 0 {
        *byte &= !wire_bit;
    }
}

fn serialize_udp_transport(header: UdpHeader, payload: &[u8]) -> Vec<u8> {
    let mut transport = Vec::with_capacity(UdpHeader::LEN + payload.len());
    header.write(&mut transport).expect("Vec<u8> write must not fail");
    transport.extend_from_slice(payload);
    transport
}

fn serialize_tcp_transport(header: &TcpHeader, payload: &[u8]) -> Vec<u8> {
    let mut transport = Vec::with_capacity(header.header_len() + payload.len());
    header.write(&mut transport).expect("Vec<u8> write must not fail");
    transport.extend_from_slice(payload);
    transport
}

fn resolve_effective_split(requested: usize, transport_len: usize) -> Result<usize, BuildError> {
    let effective = requested
        .checked_add(IP_FRAGMENT_ALIGNMENT_BYTES - 1)
        .map(|value| (value / IP_FRAGMENT_ALIGNMENT_BYTES) * IP_FRAGMENT_ALIGNMENT_BYTES)
        .ok_or(BuildError::ValueTooLarge)?;
    if effective == 0 || effective >= transport_len {
        return Err(BuildError::InvalidSplit { requested, effective, transport_len });
    }
    Ok(effective)
}

fn build_ipv4_fragment_pair(
    src: [u8; 4],
    dst: [u8; 4],
    ttl: u8,
    identification: u16,
    protocol: IpNumber,
    transport: &[u8],
    split: usize,
) -> Result<IpFragmentPair, BuildError> {
    let first = &transport[..split];
    let second = &transport[split..];

    let mut first_header =
        Ipv4Header::new(u16::try_from(first.len()).map_err(|_| BuildError::ValueTooLarge)?, ttl, protocol, src, dst)
            .map_err(|_| BuildError::ValueTooLarge)?;
    first_header.identification = identification;
    first_header.dont_fragment = false;
    first_header.more_fragments = true;
    first_header.header_checksum = first_header.calc_header_checksum();

    let mut second_header =
        Ipv4Header::new(u16::try_from(second.len()).map_err(|_| BuildError::ValueTooLarge)?, ttl, protocol, src, dst)
            .map_err(|_| BuildError::ValueTooLarge)?;
    second_header.identification = identification;
    second_header.dont_fragment = false;
    second_header.more_fragments = false;
    second_header.fragment_offset = IpFragOffset::try_new(
        u16::try_from(split / IP_FRAGMENT_ALIGNMENT_BYTES).map_err(|_| BuildError::ValueTooLarge)?,
    )
    .map_err(|_| BuildError::ValueTooLarge)?;
    second_header.header_checksum = second_header.calc_header_checksum();

    let first_bytes = serialize_ipv4_fragment(&first_header, first);
    let second_bytes = serialize_ipv4_fragment(&second_header, second);
    Ok(IpFragmentPair { first: first_bytes, second: second_bytes, effective_transport_split: split })
}

fn serialize_ipv4_fragment(header: &Ipv4Header, payload: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(Ipv4Header::MIN_LEN + payload.len());
    header.write(&mut bytes).expect("Vec<u8> write must not fail");
    bytes.extend_from_slice(payload);
    bytes
}

/// Minimum size of an IPv6 extension header (next_header + hdr_ext_len + 6 pad bytes).
const IPV6_EXT_HDR_MIN_LEN: usize = 8;

/// Serialize a PadN-filled IPv6 extension header.
///
/// Layout: `[next_header, hdr_ext_len, PadN_type=1, PadN_len, zero_padding...]`
/// Total size is always `IPV6_EXT_HDR_MIN_LEN` (8 bytes), which is the minimum
/// extension header size (hdr_ext_len=0 means 8 bytes including the 2 fixed bytes).
fn serialize_pad_extension(next_header: u8, buf: &mut Vec<u8>) {
    buf.push(next_header);
    buf.push(0); // hdr_ext_len = 0 -> total 8 bytes
                 // PadN option: type=1, length=4 (fills remaining 6 bytes: type + len + 4 zero bytes)
    buf.push(1); // PadN option type
    buf.push(4); // PadN data length
    buf.extend_from_slice(&[0u8; 4]); // 4 zero padding bytes
}

/// Serialize an IPv6 Routing extension header (type 0, segments_left=0).
///
/// Layout: `[next_header, hdr_ext_len=0, routing_type=0, segments_left=0, reserved...]`
fn serialize_routing_extension(next_header: u8, buf: &mut Vec<u8>) {
    buf.push(next_header);
    buf.push(0); // hdr_ext_len = 0 -> total 8 bytes
    buf.push(0); // routing_type = 0
    buf.push(0); // segments_left = 0
    buf.extend_from_slice(&[0u8; 4]); // reserved
}

#[allow(clippy::too_many_arguments)]
fn build_ipv6_fragment_pair(
    src: [u8; 16],
    dst: [u8; 16],
    ttl: u8,
    identification: u32,
    next_header: IpNumber,
    transport: &[u8],
    split: usize,
    ext: Ipv6ExtHeaders,
) -> Result<IpFragmentPair, BuildError> {
    let first_transport = &transport[..split];
    let second_transport = &transport[split..];

    // Build the unfragmentable extension header chain.
    // Order: HopByHop -> DestOpt -> Routing -> (Fragment Header follows)
    let mut unfrag_ext = Vec::new();
    // Each header's next_header points to the next extension or to the Fragment Header.
    // We build in reverse logical order to know what next_header each should carry.
    // Determine which unfragmentable extensions are present.
    let unfrag_chain: Vec<u8> = {
        let mut chain = Vec::new();
        if ext.hop_by_hop {
            chain.push(0); // IPPROTO_HOPOPTS
        }
        if ext.dest_opt {
            chain.push(60); // IPPROTO_DSTOPTS
        }
        if ext.routing {
            chain.push(43); // IPPROTO_ROUTING
        }
        chain
    };

    // Build headers with correct next_header chaining.
    // The last unfragmentable extension's next_header = IPV6_FRAG (44).
    for (i, &proto) in unfrag_chain.iter().enumerate() {
        let next = if i + 1 < unfrag_chain.len() {
            unfrag_chain[i + 1]
        } else {
            44 // IPV6_FRAG
        };
        match proto {
            43 => serialize_routing_extension(next, &mut unfrag_ext),
            _ => serialize_pad_extension(next, &mut unfrag_ext),
        }
    }

    // The fragmentable destination options header goes after the Fragment Header
    // and before the transport payload. It becomes part of the fragment payload.
    let frag_dest_opt = if ext.dest_opt_fragmentable {
        let mut buf = Vec::with_capacity(IPV6_EXT_HDR_MIN_LEN);
        serialize_pad_extension(next_header.0, &mut buf);
        Some(buf)
    } else {
        None
    };

    // The Fragment Header's next_header field:
    // - If fragmentable dest_opt exists: 60 (DSTOPTS), since dest_opt2 wraps transport
    // - Otherwise: the actual transport protocol (TCP/UDP)
    let frag_hdr_next = if frag_dest_opt.is_some() { IpNumber(60) } else { next_header };

    // IPv6 base header's next_header:
    // - If unfragmentable extensions exist: first extension's protocol number
    // - Otherwise: IPV6_FRAG (44)
    let ipv6_next_header =
        if let Some(&first_proto) = unfrag_chain.first() { IpNumber(first_proto) } else { ip_number::IPV6_FRAG };

    // Calculate payload lengths including all extension headers.
    let frag_dest_opt_len = frag_dest_opt.as_ref().map_or(0, Vec::len);
    let first_payload_len = unfrag_ext.len() + Ipv6FragmentHeader::LEN + frag_dest_opt_len + first_transport.len();
    let second_payload_len = unfrag_ext.len() + Ipv6FragmentHeader::LEN + frag_dest_opt_len + second_transport.len();

    let base_first = Ipv6Header {
        traffic_class: 0,
        flow_label: Ipv6FlowLabel::ZERO,
        payload_length: u16::try_from(first_payload_len).map_err(|_| BuildError::ValueTooLarge)?,
        next_header: ipv6_next_header,
        hop_limit: ttl,
        source: src,
        destination: dst,
    };
    let base_second = Ipv6Header {
        traffic_class: 0,
        flow_label: Ipv6FlowLabel::ZERO,
        payload_length: u16::try_from(second_payload_len).map_err(|_| BuildError::ValueTooLarge)?,
        next_header: ipv6_next_header,
        hop_limit: ttl,
        source: src,
        destination: dst,
    };

    let first_fragment = Ipv6FragmentHeader::new(frag_hdr_next, IpFragOffset::ZERO, true, identification);

    // Phase 3: next-header forgery on second fragment
    let second_frag_next = ext.second_frag_next_override.map_or(frag_hdr_next, IpNumber);
    let second_fragment = Ipv6FragmentHeader::new(
        second_frag_next,
        IpFragOffset::try_new(
            u16::try_from((frag_dest_opt_len + split) / IP_FRAGMENT_ALIGNMENT_BYTES)
                .map_err(|_| BuildError::ValueTooLarge)?,
        )
        .map_err(|_| BuildError::ValueTooLarge)?,
        false,
        identification,
    );

    let first_bytes = serialize_ipv6_fragment_ext(
        &base_first,
        &unfrag_ext,
        &first_fragment,
        frag_dest_opt.as_deref(),
        first_transport,
    );
    let second_bytes = serialize_ipv6_fragment_ext(
        &base_second,
        &unfrag_ext,
        &second_fragment,
        frag_dest_opt.as_deref(),
        second_transport,
    );
    Ok(IpFragmentPair { first: first_bytes, second: second_bytes, effective_transport_split: split })
}

fn serialize_ipv6_fragment_ext(
    base: &Ipv6Header,
    unfrag_ext: &[u8],
    fragment: &Ipv6FragmentHeader,
    frag_dest_opt: Option<&[u8]>,
    transport: &[u8],
) -> Vec<u8> {
    let frag_dest_len = frag_dest_opt.map_or(0, <[u8]>::len);
    let mut bytes = Vec::with_capacity(
        Ipv6Header::LEN + unfrag_ext.len() + Ipv6FragmentHeader::LEN + frag_dest_len + transport.len(),
    );
    base.write(&mut bytes).expect("Vec<u8> write must not fail");
    bytes.extend_from_slice(unfrag_ext);
    fragment.write(&mut bytes).expect("Vec<u8> write must not fail");
    if let Some(dest_opt) = frag_dest_opt {
        bytes.extend_from_slice(dest_opt);
    }
    bytes.extend_from_slice(transport);
    bytes
}

#[cfg(test)]
mod tests {
    use super::*;

    use etherparse::{Ipv4Header, Ipv4HeaderSlice, Ipv6FragmentHeader, Ipv6Header, TcpHeader, UdpHeader};

    fn reassemble_ipv4_transport(first: &[u8], second: &[u8]) -> Vec<u8> {
        let first_header = Ipv4HeaderSlice::from_slice(first).expect("parse first ipv4 header");
        let second_header = Ipv4HeaderSlice::from_slice(second).expect("parse second ipv4 header");
        let mut transport = Vec::new();
        transport.extend_from_slice(&first[first_header.slice().len()..]);
        transport.extend_from_slice(&second[second_header.slice().len()..]);
        transport
    }

    /// Skip over any extension headers between the IPv6 base header and the Fragment Header.
    fn skip_to_fragment_header(mut data: &[u8], mut next_header: IpNumber) -> (&[u8], IpNumber) {
        // Walk extension headers until we find the Fragment Header (44).
        while next_header != ip_number::IPV6_FRAG {
            // All extension headers have: next_header(1) + hdr_ext_len(1) + data
            assert!(data.len() >= 2, "extension header too short");
            let nh = IpNumber(data[0]);
            let hdr_len = (usize::from(data[1]) + 1) * 8;
            assert!(data.len() >= hdr_len, "extension header length exceeds data");
            data = &data[hdr_len..];
            next_header = nh;
        }
        (data, next_header)
    }

    fn reassemble_ipv6_transport(first: &[u8], second: &[u8]) -> Vec<u8> {
        let (first_base, first_rest) = Ipv6Header::from_slice(first).expect("parse first ipv6 header");
        let (first_rest, _) = skip_to_fragment_header(first_rest, first_base.next_header);
        let (_first_frag, first_payload) =
            Ipv6FragmentHeader::from_slice(first_rest).expect("parse first fragment header");

        let (second_base, second_rest) = Ipv6Header::from_slice(second).expect("parse second ipv6 header");
        let (second_rest, _) = skip_to_fragment_header(second_rest, second_base.next_header);
        let (_second_frag, second_payload) =
            Ipv6FragmentHeader::from_slice(second_rest).expect("parse second fragment header");
        assert_eq!(first_base.destination, second_base.destination);

        let mut transport = Vec::new();
        transport.extend_from_slice(first_payload);
        transport.extend_from_slice(second_payload);
        transport
    }

    #[test]
    fn udp_ipv4_fragment_pair_clears_df_and_preserves_udp_checksum() {
        let spec = UdpFragmentSpec {
            src: SocketAddr::from(([192, 0, 2, 10], 40000)),
            dst: SocketAddr::from(([198, 51, 100, 20], 443)),
            ttl: 64,
            identification: 0x1234,
            ipv6_ext: Ipv6ExtHeaders::default(),
        };
        let payload = b"quic initial payload";

        let pair = build_udp_fragment_pair(spec, payload, 8).expect("build udp ipv4 fragments");
        assert_eq!(pair.effective_transport_split, 8);

        let (first_header, _) = Ipv4Header::from_slice(&pair.first).expect("parse first ipv4 header");
        let (second_header, _) = Ipv4Header::from_slice(&pair.second).expect("parse second ipv4 header");
        assert!(!first_header.dont_fragment);
        assert!(first_header.more_fragments);
        assert_eq!(u16::from(first_header.fragment_offset), 0);
        assert!(!second_header.more_fragments);
        assert_eq!(second_header.fragment_offset.byte_offset() as usize, 8);
        assert_eq!(first_header.header_checksum, first_header.calc_header_checksum());
        assert_eq!(second_header.header_checksum, second_header.calc_header_checksum());

        let transport = reassemble_ipv4_transport(&pair.first, &pair.second);
        let (udp, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp transport");
        assert_eq!(udp.source_port, 40000);
        assert_eq!(udp.destination_port, 443);
        assert_eq!(udp_payload, payload);
        assert_eq!(
            udp.checksum,
            udp.calc_checksum_ipv4_raw([192, 0, 2, 10], [198, 51, 100, 20], udp_payload)
                .expect("recalculate udp checksum")
        );
    }

    #[test]
    fn udp_ipv6_fragment_pair_adds_fragment_header() {
        let spec = UdpFragmentSpec {
            src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 40000)),
            dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
            ttl: 48,
            identification: 0x1020_3040,
            ipv6_ext: Ipv6ExtHeaders::default(),
        };
        let payload = b"hello over fragmented udp";

        let pair = build_udp_fragment_pair(spec, payload, 8).expect("build udp ipv6 fragments");
        let (first_base, first_rest) = Ipv6Header::from_slice(&pair.first).expect("parse first ipv6 header");
        let (first_fragment, first_payload) =
            Ipv6FragmentHeader::from_slice(first_rest).expect("parse first fragment header");
        let (second_base, second_rest) = Ipv6Header::from_slice(&pair.second).expect("parse second ipv6 header");
        let (second_fragment, second_payload) =
            Ipv6FragmentHeader::from_slice(second_rest).expect("parse second fragment header");

        assert_eq!(first_base.next_header, ip_number::IPV6_FRAG);
        assert_eq!(second_base.next_header, ip_number::IPV6_FRAG);
        assert_eq!(first_fragment.next_header, ip_number::UDP);
        assert!(first_fragment.more_fragments);
        assert_eq!(u16::from(first_fragment.fragment_offset), 0);
        assert_eq!(second_fragment.fragment_offset.byte_offset() as usize, pair.effective_transport_split);
        assert!(!second_fragment.more_fragments);
        assert_eq!(first_payload.len(), pair.effective_transport_split);
        assert!(!second_payload.is_empty());

        let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
        let (udp, udp_payload) = UdpHeader::from_slice(&transport).expect("parse reassembled udp transport");
        assert_eq!(udp_payload, payload);
        assert_eq!(
            udp.checksum,
            udp.calc_checksum_ipv6_raw(
                [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1],
                [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2],
                udp_payload
            )
            .expect("recalculate ipv6 udp checksum")
        );
    }

    #[test]
    fn tcp_ipv4_fragment_pair_preserves_sequence_ack_and_checksum() {
        let spec = TcpFragmentSpec {
            src: SocketAddr::from(([203, 0, 113, 10], 50000)),
            dst: SocketAddr::from(([198, 51, 100, 20], 443)),
            ttl: 64,
            identification: 0x9988,
            sequence_number: 0x0102_0304,
            acknowledgment_number: 0x0506_0708,
            window_size: 4096,
            timestamp: None,
            ipv6_ext: Ipv6ExtHeaders::default(),
        };
        let payload = b"fragmented tls client hello";

        let pair = build_tcp_fragment_pair(spec, payload, 5).expect("build tcp ipv4 fragments");
        assert_eq!(pair.effective_transport_split % IP_FRAGMENT_ALIGNMENT_BYTES, 0);
        assert!(pair.effective_transport_split > TcpHeader::MIN_LEN);

        let transport = reassemble_ipv4_transport(&pair.first, &pair.second);
        let (tcp, tcp_payload) = TcpHeader::from_slice(&transport).expect("parse tcp transport");
        assert_eq!(tcp.sequence_number, spec.sequence_number);
        assert_eq!(tcp.acknowledgment_number, spec.acknowledgment_number);
        assert!(tcp.ack);
        assert!(tcp.psh);
        assert_eq!(tcp_payload, payload);
        assert_eq!(
            tcp.checksum,
            tcp.calc_checksum_ipv4_raw([203, 0, 113, 10], [198, 51, 100, 20], tcp_payload)
                .expect("recalculate tcp checksum")
        );
    }

    #[test]
    fn tcp_ipv6_fragment_pair_preserves_sequence_ack_and_checksum() {
        let spec = TcpFragmentSpec {
            src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 50000)),
            dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
            ttl: 48,
            identification: 0x1020_3040,
            sequence_number: 0x0102_0304,
            acknowledgment_number: 0x0506_0708,
            window_size: 4096,
            timestamp: None,
            ipv6_ext: Ipv6ExtHeaders::default(),
        };
        let payload = b"fragmented tls client hello over ipv6";

        let pair = build_tcp_fragment_pair(spec, payload, 5).expect("build tcp ipv6 fragments");
        let (first_base, first_rest) = Ipv6Header::from_slice(&pair.first).expect("parse first ipv6 header");
        let (first_fragment, _) = Ipv6FragmentHeader::from_slice(first_rest).expect("parse first fragment header");

        assert_eq!(first_base.next_header, ip_number::IPV6_FRAG);
        assert_eq!(first_fragment.next_header, ip_number::TCP);
        assert_eq!(pair.effective_transport_split % IP_FRAGMENT_ALIGNMENT_BYTES, 0);

        let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
        let (tcp, tcp_payload) = TcpHeader::from_slice(&transport).expect("parse reassembled tcp transport");
        assert_eq!(tcp.sequence_number, spec.sequence_number);
        assert_eq!(tcp.acknowledgment_number, spec.acknowledgment_number);
        assert!(tcp.ack);
        assert!(tcp.psh);
        assert_eq!(tcp_payload, payload);
        assert_eq!(
            tcp.checksum,
            tcp.calc_checksum_ipv6_raw(
                [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1],
                [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2],
                tcp_payload
            )
            .expect("recalculate ipv6 tcp checksum")
        );
    }

    #[test]
    fn tcp_fragment_pair_rounds_payload_split_up_to_next_ip_boundary() {
        let spec = TcpFragmentSpec {
            src: SocketAddr::from(([203, 0, 113, 10], 50000)),
            dst: SocketAddr::from(([198, 51, 100, 20], 443)),
            ttl: 64,
            identification: 0x9988,
            sequence_number: 0x0102_0304,
            acknowledgment_number: 0x0506_0708,
            window_size: 4096,
            timestamp: None,
            ipv6_ext: Ipv6ExtHeaders::default(),
        };
        let payload = b"fragmented tls client hello";

        let pair = build_tcp_fragment_pair(spec, payload, 1).expect("build aligned tcp fragment pair");
        let (second_header, _) = Ipv4Header::from_slice(&pair.second).expect("parse second ipv4 header");

        assert_eq!(pair.effective_transport_split, 24);
        assert_eq!(second_header.fragment_offset.byte_offset() as usize, 24);
    }

    #[test]
    fn degenerate_fragment_pair_is_rejected_after_alignment() {
        let spec = UdpFragmentSpec {
            src: SocketAddr::from(([192, 0, 2, 10], 40000)),
            dst: SocketAddr::from(([198, 51, 100, 20], 443)),
            ttl: 64,
            identification: 7,
            ipv6_ext: Ipv6ExtHeaders::default(),
        };
        let payload = b"tiny";

        let err = build_udp_fragment_pair(spec, payload, 16).expect_err("reject degenerate udp fragment pair");
        assert!(matches!(err, BuildError::InvalidSplit { .. }));
    }

    #[test]
    fn tcp_ipv4_fragment_pair_serializes_timestamp_option_when_requested() {
        let spec = TcpFragmentSpec {
            src: SocketAddr::from(([203, 0, 113, 10], 50000)),
            dst: SocketAddr::from(([198, 51, 100, 20], 443)),
            ttl: 64,
            identification: 0x1111,
            sequence_number: 0x0102_0304,
            acknowledgment_number: 0x0506_0708,
            window_size: 4096,
            timestamp: Some(TcpTimestampOption { value: 0x1122_3344, echo_reply: 0 }),
            ipv6_ext: Ipv6ExtHeaders::default(),
        };
        let payload = b"timestamped payload";

        let pair = build_tcp_fragment_pair(spec, payload, 5).expect("build tcp ipv4 fragments with timestamp");
        assert_eq!(pair.effective_transport_split, 40);

        let transport = reassemble_ipv4_transport(&pair.first, &pair.second);
        let (tcp, tcp_payload) = TcpHeader::from_slice(&transport).expect("parse tcp transport");
        let options = tcp.options_iterator().collect::<Vec<_>>();

        assert_eq!(tcp.header_len(), 32);
        assert_eq!(
            options,
            vec![
                Ok(TcpOptionElement::Noop),
                Ok(TcpOptionElement::Noop),
                Ok(TcpOptionElement::Timestamp(0x1122_3344, 0)),
            ]
        );
        assert_eq!(tcp_payload, payload);
    }

    #[test]
    fn tcp_ipv6_fragment_pair_serializes_timestamp_option_when_requested() {
        let spec = TcpFragmentSpec {
            src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 50000)),
            dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
            ttl: 48,
            identification: 0x1020_3040,
            sequence_number: 0x0102_0304,
            acknowledgment_number: 0x0506_0708,
            window_size: 4096,
            timestamp: Some(TcpTimestampOption { value: 0x5566_7788, echo_reply: 0 }),
            ipv6_ext: Ipv6ExtHeaders::default(),
        };
        let payload = b"ipv6 timestamped payload";

        let pair = build_tcp_fragment_pair(spec, payload, 3).expect("build tcp ipv6 fragments with timestamp");
        let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
        let (tcp, tcp_payload) = TcpHeader::from_slice(&transport).expect("parse tcp transport");
        let options = tcp.options_iterator().collect::<Vec<_>>();

        assert_eq!(tcp.header_len(), 32);
        assert_eq!(
            options,
            vec![
                Ok(TcpOptionElement::Noop),
                Ok(TcpOptionElement::Noop),
                Ok(TcpOptionElement::Timestamp(0x5566_7788, 0)),
            ]
        );
        assert_eq!(pair.effective_transport_split % IP_FRAGMENT_ALIGNMENT_BYTES, 0);
        assert_eq!(tcp_payload, payload);
    }

    #[test]
    fn ipv6_hop_by_hop_extension_header_is_inserted_before_fragment() {
        let spec = UdpFragmentSpec {
            src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 40000)),
            dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
            ttl: 48,
            identification: 0xAABB,
            ipv6_ext: Ipv6ExtHeaders { hop_by_hop: true, ..Ipv6ExtHeaders::default() },
        };
        let payload = b"hello over fragmented udp with hbh";

        let pair = build_udp_fragment_pair(spec, payload, 8).expect("build with hop-by-hop");
        let (first_base, first_rest) = Ipv6Header::from_slice(&pair.first).expect("parse ipv6");

        // IPv6 next_header should be HOPOPTS (0), not IPV6_FRAG (44)
        assert_eq!(first_base.next_header, IpNumber(0));

        // HBH header's next_header should be IPV6_FRAG (44)
        assert_eq!(first_rest[0], 44);
        assert_eq!(first_rest[1], 0); // hdr_ext_len = 0 -> 8 bytes

        // Fragment header follows at offset 8
        let (frag, _) = Ipv6FragmentHeader::from_slice(&first_rest[8..]).expect("parse fragment header");
        assert_eq!(frag.next_header, ip_number::UDP);

        // Reassembly still works
        let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
        let (_, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp");
        assert_eq!(udp_payload, payload);
    }

    #[test]
    fn ipv6_dest_opt_unfragmentable_is_inserted_before_fragment() {
        let spec = UdpFragmentSpec {
            src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 40000)),
            dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
            ttl: 48,
            identification: 0xCCDD,
            ipv6_ext: Ipv6ExtHeaders { dest_opt: true, ..Ipv6ExtHeaders::default() },
        };
        let payload = b"hello with dest opt unfrag";

        let pair = build_udp_fragment_pair(spec, payload, 8).expect("build with dest_opt");
        let (first_base, _) = Ipv6Header::from_slice(&pair.first).expect("parse ipv6");
        assert_eq!(first_base.next_header, IpNumber(60)); // DSTOPTS

        let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
        let (_, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp");
        assert_eq!(udp_payload, payload);
    }

    #[test]
    fn ipv6_multiple_extension_headers_chain_correctly() {
        let spec = UdpFragmentSpec {
            src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 40000)),
            dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
            ttl: 48,
            identification: 0xEEFF,
            ipv6_ext: Ipv6ExtHeaders { hop_by_hop: true, dest_opt: true, routing: true, ..Ipv6ExtHeaders::default() },
        };
        let payload = b"hello with all unfrag extensions";

        let pair = build_udp_fragment_pair(spec, payload, 8).expect("build with all extensions");
        let (first_base, rest) = Ipv6Header::from_slice(&pair.first).expect("parse ipv6");

        // Chain: IPv6(next=0) -> HBH(next=60) -> DestOpt(next=43) -> Routing(next=44) -> Frag(next=17)
        assert_eq!(first_base.next_header, IpNumber(0)); // HOPOPTS
        assert_eq!(rest[0], 60); // HBH -> DSTOPTS
        assert_eq!(rest[8], 43); // DSTOPTS -> ROUTING
        assert_eq!(rest[16], 44); // ROUTING -> IPV6_FRAG

        let (frag, _) = Ipv6FragmentHeader::from_slice(&rest[24..]).expect("parse frag header");
        assert_eq!(frag.next_header, ip_number::UDP);

        let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
        let (_, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp");
        assert_eq!(udp_payload, payload);
    }

    #[test]
    fn ipv6_second_frag_next_override_forges_protocol() {
        let spec = UdpFragmentSpec {
            src: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 40000)),
            dst: SocketAddr::from(([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 443)),
            ttl: 48,
            identification: 0x5678,
            ipv6_ext: Ipv6ExtHeaders { second_frag_next_override: Some(6), ..Ipv6ExtHeaders::default() }, // forge as TCP
        };
        let payload = b"hello with forged next header";

        let pair = build_udp_fragment_pair(spec, payload, 8).expect("build with forged next");

        // First fragment's Fragment Header should have correct next_header (UDP=17)
        let (_, first_rest) = Ipv6Header::from_slice(&pair.first).expect("parse first ipv6");
        let (first_frag, _) = Ipv6FragmentHeader::from_slice(first_rest).expect("parse first frag");
        assert_eq!(first_frag.next_header, ip_number::UDP);

        // Second fragment's Fragment Header should have forged next_header (TCP=6)
        let (_, second_rest) = Ipv6Header::from_slice(&pair.second).expect("parse second ipv6");
        let (second_frag, _) = Ipv6FragmentHeader::from_slice(second_rest).expect("parse second frag");
        assert_eq!(second_frag.next_header, IpNumber(6)); // Forged as TCP

        // Reassembly still produces valid UDP (OS uses first frag's next_header)
        let transport = reassemble_ipv6_transport(&pair.first, &pair.second);
        let (_, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp");
        assert_eq!(udp_payload, payload);
    }

    #[test]
    fn ipv4_ignores_ipv6_ext_headers() {
        let spec = UdpFragmentSpec {
            src: SocketAddr::from(([192, 0, 2, 10], 40000)),
            dst: SocketAddr::from(([198, 51, 100, 20], 443)),
            ttl: 64,
            identification: 0x4321,
            ipv6_ext: Ipv6ExtHeaders {
                hop_by_hop: true,
                dest_opt: true,
                second_frag_next_override: Some(6),
                ..Ipv6ExtHeaders::default()
            },
        };
        let payload = b"ipv4 ignores ipv6 extensions";

        // Should succeed and produce standard IPv4 fragments
        let pair = build_udp_fragment_pair(spec, payload, 8).expect("build ipv4 ignoring v6 ext");
        let (first_header, _) = Ipv4Header::from_slice(&pair.first).expect("parse ipv4");
        assert!(!first_header.dont_fragment);
        assert!(first_header.more_fragments);

        let transport = reassemble_ipv4_transport(&pair.first, &pair.second);
        let (_, udp_payload) = UdpHeader::from_slice(&transport).expect("parse udp");
        assert_eq!(udp_payload, payload);
    }

    #[test]
    fn build_fake_rst_packet_ipv4_has_rst_flag_and_correct_seq() {
        let spec = TcpFragmentSpec {
            src: "1.2.3.4:12345".parse().unwrap(),
            dst: "5.6.7.8:443".parse().unwrap(),
            ttl: 3,
            identification: 0x1234,
            sequence_number: 1000,
            acknowledgment_number: 2000,
            window_size: 0,
            timestamp: None,
            ipv6_ext: Ipv6ExtHeaders::default(),
        };
        let packet = build_fake_rst_packet(&spec).expect("build fake rst");
        let (ip_header, remaining) = Ipv4Header::from_slice(&packet).expect("parse ipv4");
        assert_eq!(ip_header.time_to_live, 3);
        assert!(ip_header.dont_fragment);
        let (tcp_header, payload) = TcpHeader::from_slice(remaining).expect("parse tcp");
        assert!(tcp_header.rst);
        assert!(tcp_header.ack);
        assert_eq!(tcp_header.sequence_number, 1000);
        assert_eq!(tcp_header.acknowledgment_number, 2000);
        assert_eq!(tcp_header.window_size, 0);
        assert!(payload.is_empty());
    }

    #[test]
    fn build_fake_rst_packet_ipv6_has_rst_flag() {
        let spec = TcpFragmentSpec {
            src: "[::1]:12345".parse().unwrap(),
            dst: "[::2]:443".parse().unwrap(),
            ttl: 5,
            identification: 0,
            sequence_number: 3000,
            acknowledgment_number: 4000,
            window_size: 0,
            timestamp: None,
            ipv6_ext: Ipv6ExtHeaders::default(),
        };
        let packet = build_fake_rst_packet(&spec).expect("build fake rst v6");
        let (ip_header, _) = Ipv6Header::from_slice(&packet).expect("parse ipv6");
        assert_eq!(ip_header.hop_limit, 5);
        let tcp_offset = Ipv6Header::LEN;
        let (tcp_header, payload) = TcpHeader::from_slice(&packet[tcp_offset..]).expect("parse tcp");
        assert!(tcp_header.rst);
        assert!(tcp_header.ack);
        assert_eq!(tcp_header.sequence_number, 3000);
        assert_eq!(tcp_header.acknowledgment_number, 4000);
        assert!(payload.is_empty());
    }

    #[test]
    fn build_fake_rst_packet_rejects_mixed_address_families() {
        let spec = TcpFragmentSpec {
            src: "1.2.3.4:12345".parse().unwrap(),
            dst: "[::2]:443".parse().unwrap(),
            ttl: 3,
            identification: 0,
            sequence_number: 0,
            acknowledgment_number: 0,
            window_size: 0,
            timestamp: None,
            ipv6_ext: Ipv6ExtHeaders::default(),
        };
        assert_eq!(build_fake_rst_packet(&spec).unwrap_err(), BuildError::AddressFamilyMismatch);
    }
}
