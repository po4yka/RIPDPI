use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use ripdpi_socks5_core::validate_udp_rsv_frag;

use super::TargetAddr;

/// Encode a SOCKS5 UDP request frame (RFC 1928 section 7).
///
/// ```text
/// +----+------+------+----------+----------+----------+
/// |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
/// +----+------+------+----------+----------+----------+
/// |  2 |   1  |   1  | variable |    2     | variable |
/// +----+------+------+----------+----------+----------+
/// ```
pub fn encode_udp_frame(dst: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(payload.len() + 22);
    frame.extend_from_slice(&[0x00, 0x00, 0x00]); // RSV RSV FRAG=0
    match dst {
        SocketAddr::V4(a) => {
            frame.push(0x01);
            frame.extend_from_slice(&a.ip().octets());
            frame.extend_from_slice(&a.port().to_be_bytes());
        }
        SocketAddr::V6(a) => {
            frame.push(0x04);
            frame.extend_from_slice(&a.ip().octets());
            frame.extend_from_slice(&a.port().to_be_bytes());
        }
    }
    frame.extend_from_slice(payload);
    frame
}

pub fn encode_udp_target_frame(dst: &TargetAddr, payload: &[u8]) -> io::Result<Vec<u8>> {
    match dst {
        TargetAddr::Ip(addr) => Ok(encode_udp_frame(*addr, payload)),
        TargetAddr::Domain(domain, port) => {
            let domain = domain.as_bytes();
            let length = u8::try_from(domain.len())
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SOCKS5 UDP domain exceeds 255 bytes"))?;
            if length == 0 {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "SOCKS5 UDP domain is empty"));
            }
            let mut frame = Vec::with_capacity(payload.len() + domain.len() + 7);
            frame.extend_from_slice(&[0x00, 0x00, 0x00, 0x03, length]);
            frame.extend_from_slice(domain);
            frame.extend_from_slice(&port.to_be_bytes());
            frame.extend_from_slice(payload);
            Ok(frame)
        }
        TargetAddr::ResolvedDomain(domain, addr) => encode_resolved_domain_udp_frame(domain, *addr, payload),
    }
}

fn encode_resolved_domain_udp_frame(domain: &str, addr: SocketAddr, payload: &[u8]) -> io::Result<Vec<u8>> {
    let domain = domain.as_bytes();
    let length = u8::try_from(domain.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SOCKS5 UDP resolved domain exceeds 255 bytes"))?;
    if length == 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "SOCKS5 UDP resolved domain is empty"));
    }
    let mut frame = Vec::with_capacity(payload.len() + domain.len() + 24);
    frame.extend_from_slice(&[0, 0, 0, 0x05, length]);
    frame.extend_from_slice(domain);
    match addr {
        SocketAddr::V4(addr) => {
            frame.push(0x01);
            frame.extend_from_slice(&addr.ip().octets());
        }
        SocketAddr::V6(addr) => {
            frame.push(0x04);
            frame.extend_from_slice(&addr.ip().octets());
        }
    }
    frame.extend_from_slice(&addr.port().to_be_bytes());
    frame.extend_from_slice(payload);
    Ok(frame)
}

/// Decode a SOCKS5 UDP response frame.
///
/// Returns `(from_addr, payload)` on success.
pub fn decode_udp_frame(frame: &[u8]) -> io::Result<(SocketAddr, &[u8])> {
    let (target, payload) = decode_udp_target_frame(frame)?;
    match target {
        TargetAddr::Ip(addr) => Ok((addr, payload)),
        TargetAddr::Domain(_, _) | TargetAddr::ResolvedDomain(_, _) => {
            Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5 UDP response uses domain authority"))
        }
    }
}

pub(crate) fn decode_udp_target_frame(frame: &[u8]) -> io::Result<(TargetAddr, &[u8])> {
    // Minimum: RSV(2) + FRAG(1) + ATYP(1) + IPv4(4) + PORT(2) = 10
    if frame.len() < 10 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5 UDP frame too short"));
    }

    validate_udp_rsv_frag(frame)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, format!("SOCKS5 UDP: {err}")))?;

    let (target, data_start) = match frame[3] {
        0x01 => {
            let ip = Ipv4Addr::new(frame[4], frame[5], frame[6], frame[7]);
            let port = u16::from_be_bytes([frame[8], frame[9]]);
            (TargetAddr::Ip(SocketAddr::new(IpAddr::V4(ip), port)), 10)
        }
        0x04 => {
            if frame.len() < 22 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5 UDP IPv6 frame too short"));
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&frame[4..20]);
            let ip = Ipv6Addr::from(raw);
            let port = u16::from_be_bytes([frame[20], frame[21]]);
            (TargetAddr::Ip(SocketAddr::new(IpAddr::V6(ip), port)), 22)
        }
        0x03 => {
            let len =
                *frame.get(4).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing domain length"))?
                    as usize;
            let end = 5 + len;
            let domain = std::str::from_utf8(
                frame.get(5..end).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "truncated domain"))?,
            )
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid domain"))?;
            let port_bytes = frame
                .get(end..end + 2)
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "truncated domain port"))?;
            let port = u16::from_be_bytes([port_bytes[0], port_bytes[1]]);
            (TargetAddr::Domain(domain.to_ascii_lowercase(), port), end + 2)
        }
        0x05 => decode_resolved_domain_target(frame)?,
        t => {
            return Err(io::Error::new(io::ErrorKind::InvalidData, format!("SOCKS5 UDP: unknown ATYP={t}")));
        }
    };
    Ok((target, &frame[data_start..]))
}

fn decode_resolved_domain_target(frame: &[u8]) -> io::Result<(TargetAddr, usize)> {
    let len =
        *frame.get(4).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing domain length"))? as usize;
    let host_end = 5 + len;
    let host = std::str::from_utf8(
        frame.get(5..host_end).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "truncated domain"))?,
    )
    .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid domain"))?
    .to_ascii_lowercase();
    let ip_type = *frame.get(host_end).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing IP type"))?;
    let (ip, port_offset) = match ip_type {
        0x01 => {
            let raw = frame
                .get(host_end + 1..host_end + 5)
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "truncated IPv4 address"))?;
            (IpAddr::V4(Ipv4Addr::new(raw[0], raw[1], raw[2], raw[3])), host_end + 5)
        }
        0x04 => {
            let raw = frame
                .get(host_end + 1..host_end + 17)
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "truncated IPv6 address"))?;
            let mut octets = [0; 16];
            octets.copy_from_slice(raw);
            (IpAddr::V6(Ipv6Addr::from(octets)), host_end + 17)
        }
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid resolved-domain IP type")),
    };
    let port = frame
        .get(port_offset..port_offset + 2)
        .map(|bytes| u16::from_be_bytes([bytes[0], bytes[1]]))
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "truncated resolved-domain port"))?;
    Ok((TargetAddr::ResolvedDomain(host, SocketAddr::new(ip, port)), port_offset + 2))
}
