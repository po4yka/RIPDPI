use std::net::SocketAddr;

use etherparse::{TcpHeader, TcpHeaderSlice, TcpOptionElement, ip_number};

use crate::ipv4::build_ipv4_fragment_pair;
use crate::ipv6::build_ipv6_fragment_pair;
use crate::split::resolve_effective_split;
use crate::types::{
    TCP_FLAG_ACK, TCP_FLAG_AE, TCP_FLAG_CWR, TCP_FLAG_ECE, TCP_FLAG_FIN, TCP_FLAG_PSH, TCP_FLAG_R1, TCP_FLAG_R2,
    TCP_FLAG_R3, TCP_FLAG_RST, TCP_FLAG_SYN, TCP_FLAG_URG,
};
use crate::{BuildError, IpFragmentPair, TcpFragmentSpec};

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

pub(crate) fn apply_tcp_flag_overrides_to_transport(
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

pub(crate) fn serialize_tcp_transport(header: &TcpHeader, payload: &[u8]) -> Vec<u8> {
    let mut transport = Vec::with_capacity(header.header_len() + payload.len());
    header.write(&mut transport).expect("Vec<u8> write must not fail");
    transport.extend_from_slice(payload);
    transport
}
