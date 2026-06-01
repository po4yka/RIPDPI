//! Packet-construction helpers for Linux raw TCP sends.
//!
//! This module keeps byte-level header mutation separate from raw-socket syscalls.

use std::io;
use std::net::SocketAddr;

use etherparse::{Ipv4Header, Ipv6FlowLabel, Ipv6Header, TcpHeader, TcpHeaderSlice, ip_number};
use ripdpi_config::{
    TCP_FLAG_ACK, TCP_FLAG_AE, TCP_FLAG_CWR, TCP_FLAG_ECE, TCP_FLAG_FIN, TCP_FLAG_PSH, TCP_FLAG_R1, TCP_FLAG_R2,
    TCP_FLAG_R3, TCP_FLAG_RST, TCP_FLAG_SYN, TCP_FLAG_URG,
};

use crate::TcpFlagOverrides;
use crate::linux::tcp_repair::TcpTimestampSnapshot;

pub(crate) fn resolve_raw_ttl(default_ttl: u8) -> u8 {
    if default_ttl != 0 { default_ttl } else { 64 }
}

fn apply_tcp_flag_overrides_to_bytes(flags_bytes: &mut [u8], overrides: TcpFlagOverrides) {
    if overrides.is_empty() {
        return;
    }
    let mut upper = flags_bytes[0] & 0xF0;
    let mut reserved = flags_bytes[0] & 0x0F;
    let mut control = flags_bytes[1];

    apply_single_flag(&mut control, overrides, TCP_FLAG_FIN, 0x01);
    apply_single_flag(&mut control, overrides, TCP_FLAG_SYN, 0x02);
    apply_single_flag(&mut control, overrides, TCP_FLAG_RST, 0x04);
    apply_single_flag(&mut control, overrides, TCP_FLAG_PSH, 0x08);
    apply_single_flag(&mut control, overrides, TCP_FLAG_ACK, 0x10);
    apply_single_flag(&mut control, overrides, TCP_FLAG_URG, 0x20);
    apply_single_flag(&mut control, overrides, TCP_FLAG_ECE, 0x40);
    apply_single_flag(&mut control, overrides, TCP_FLAG_CWR, 0x80);
    apply_single_flag(&mut reserved, overrides, TCP_FLAG_AE, 0x01);
    apply_single_flag(&mut reserved, overrides, TCP_FLAG_R1, 0x02);
    apply_single_flag(&mut reserved, overrides, TCP_FLAG_R2, 0x04);
    apply_single_flag(&mut reserved, overrides, TCP_FLAG_R3, 0x08);

    upper |= reserved & 0x0F;
    flags_bytes[0] = upper;
    flags_bytes[1] = control;
}

fn apply_single_flag(byte: &mut u8, overrides: TcpFlagOverrides, flag_mask: u16, wire_bit: u8) {
    if (overrides.set & flag_mask) != 0 {
        *byte |= wire_bit;
    }
    if (overrides.unset & flag_mask) != 0 {
        *byte &= !wire_bit;
    }
}

pub(crate) fn apply_tcp_flag_overrides_to_packet(
    packet: &mut [u8],
    source: SocketAddr,
    target: SocketAddr,
    payload_len: usize,
    overrides: TcpFlagOverrides,
) -> io::Result<()> {
    if overrides.is_empty() {
        return Ok(());
    }
    let tcp_offset = match source {
        SocketAddr::V4(_) => Ipv4Header::MIN_LEN,
        SocketAddr::V6(_) => Ipv6Header::LEN,
    };
    if packet.len() < tcp_offset + 20 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "raw TCP packet too short"));
    }
    let flags = &mut packet[tcp_offset + 12..tcp_offset + 14];
    apply_tcp_flag_overrides_to_bytes(flags, overrides);
    packet[tcp_offset + 16] = 0;
    packet[tcp_offset + 17] = 0;
    let header = TcpHeaderSlice::from_slice(&packet[tcp_offset..]).map_err(io::Error::other)?;
    let payload = &packet[packet.len().saturating_sub(payload_len)..];
    let checksum = match (source, target) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => header
            .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
            .map_err(|_| value_too_large_io("raw TCP payload exceeds IPv4 checksum limits"))?,
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => header
            .calc_checksum_ipv6_raw(src.ip().octets(), dst.ip().octets(), payload)
            .map_err(|_| value_too_large_io("raw TCP payload exceeds IPv6 checksum limits"))?,
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "raw TCP send requires matching source and destination IP families",
            ));
        }
    };
    packet[tcp_offset + 16..tcp_offset + 18].copy_from_slice(&checksum.to_be_bytes());
    Ok(())
}

pub(crate) fn fragment_identification(source: SocketAddr, target: SocketAddr, payload_len: usize) -> u32 {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().subsec_nanos();
    let source_mix = u32::from(source.port()) << 16;
    let target_mix = u32::from(target.port());
    now ^ source_mix ^ target_mix ^ (payload_len as u32)
}

pub(crate) fn build_error_to_io(error: ripdpi_ipfrag::BuildError) -> io::Error {
    match error {
        ripdpi_ipfrag::BuildError::InvalidSplit { .. } => io::Error::new(io::ErrorKind::InvalidInput, error),
        _ => io::Error::new(io::ErrorKind::InvalidData, error),
    }
}

pub(crate) fn value_too_large_io(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn build_tcp_segment_packet(
    source: SocketAddr,
    target: SocketAddr,
    ttl: u8,
    identification: u32,
    sequence_number: u32,
    acknowledgment_number: u32,
    window_size: u16,
    timestamp: Option<TcpTimestampSnapshot>,
    push_flag: bool,
    payload: &[u8],
    inject_md5: bool,
    tcp_flags: TcpFlagOverrides,
) -> io::Result<Vec<u8>> {
    let mut tcp = TcpHeader::new(source.port(), target.port(), sequence_number, window_size);
    tcp.ack = true;
    tcp.psh = push_flag && !payload.is_empty();
    tcp.acknowledgment_number = acknowledgment_number;

    let mut raw_opts: Vec<u8> = Vec::new();
    if let Some(timestamp) = timestamp {
        // Noop, Noop, Timestamp (12 bytes total with padding)
        raw_opts.extend_from_slice(&[1, 1]); // 2x Noop
        raw_opts.push(8); // Kind=Timestamp
        raw_opts.push(10); // Length=10
        raw_opts.extend_from_slice(&timestamp.value.to_be_bytes());
        raw_opts.extend_from_slice(&timestamp.echo_reply.to_be_bytes());
    }
    if inject_md5 {
        // Noop padding before MD5 for 4-byte alignment
        let padding_needed = (4 - (raw_opts.len() % 4)) % 4;
        raw_opts.extend(std::iter::repeat_n(1u8, padding_needed)); // Noop
        raw_opts.push(19); // Kind=MD5 Signature (RFC 2385)
        raw_opts.push(18); // Length=18 (2 header + 16 signature)
        // Random 16-byte signature (deterministic from seq for reproducibility)
        let seed = sequence_number;
        for i in 0u32..4 {
            raw_opts.extend_from_slice(&seed.wrapping_add(i).wrapping_mul(2654435761).to_be_bytes());
        }
    }
    if !raw_opts.is_empty() {
        // Pad to 4-byte boundary
        while !raw_opts.len().is_multiple_of(4) {
            raw_opts.push(0); // End-of-options
        }
        tcp.set_options_raw(&raw_opts)
            .map_err(|_| value_too_large_io("TCP options exceed supported raw packet size"))?;
    }

    match (source, target) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| value_too_large_io("raw TCP payload exceeds IPv4 checksum limits"))?;
            let payload_length = u16::try_from(tcp.header_len() + payload.len())
                .map_err(|_| value_too_large_io("IPv4 packet too large"))?;
            let mut ip = Ipv4Header::new(payload_length, ttl, ip_number::TCP, src.ip().octets(), dst.ip().octets())
                .map_err(|_| value_too_large_io("IPv4 packet too large"))?;
            ip.identification = identification as u16;
            ip.dont_fragment = false;
            ip.more_fragments = false;
            ip.header_checksum = ip.calc_header_checksum();

            let mut bytes = Vec::with_capacity(Ipv4Header::MIN_LEN + tcp.header_len() + payload.len());
            ip.write(&mut bytes).map_err(io::Error::other)?;
            tcp.write(&mut bytes).map_err(io::Error::other)?;
            bytes.extend_from_slice(payload);
            apply_tcp_flag_overrides_to_packet(&mut bytes, source, target, payload.len(), tcp_flags)?;
            Ok(bytes)
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv6_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| value_too_large_io("raw TCP payload exceeds IPv6 checksum limits"))?;
            let payload_length = u16::try_from(tcp.header_len() + payload.len())
                .map_err(|_| value_too_large_io("IPv6 packet too large"))?;
            let ip = Ipv6Header {
                traffic_class: 0,
                flow_label: Ipv6FlowLabel::ZERO,
                payload_length,
                next_header: ip_number::TCP,
                hop_limit: ttl,
                source: src.ip().octets(),
                destination: dst.ip().octets(),
            };

            let mut bytes = Vec::with_capacity(Ipv6Header::LEN + tcp.header_len() + payload.len());
            ip.write(&mut bytes).map_err(io::Error::other)?;
            tcp.write(&mut bytes).map_err(io::Error::other)?;
            bytes.extend_from_slice(payload);
            apply_tcp_flag_overrides_to_packet(&mut bytes, source, target, payload.len(), tcp_flags)?;
            Ok(bytes)
        }
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "multidisorder raw TCP send requires matching source and destination IP families",
        )),
    }
}
