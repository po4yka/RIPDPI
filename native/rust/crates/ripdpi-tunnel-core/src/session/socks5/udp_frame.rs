use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use ripdpi_socks5_core::validate_udp_rsv_frag;

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

/// Decode a SOCKS5 UDP response frame.
///
/// Returns `(from_addr, payload)` on success.
pub fn decode_udp_frame(frame: &[u8]) -> io::Result<(SocketAddr, &[u8])> {
    // Minimum: RSV(2) + FRAG(1) + ATYP(1) + IPv4(4) + PORT(2) = 10
    if frame.len() < 10 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5 UDP frame too short"));
    }

    validate_udp_rsv_frag(frame)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, format!("SOCKS5 UDP: {err}")))?;

    let (addr, data_start) = match frame[3] {
        0x01 => {
            let ip = Ipv4Addr::new(frame[4], frame[5], frame[6], frame[7]);
            let port = u16::from_be_bytes([frame[8], frame[9]]);
            (SocketAddr::new(IpAddr::V4(ip), port), 10)
        }
        0x04 => {
            if frame.len() < 22 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5 UDP IPv6 frame too short"));
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&frame[4..20]);
            let ip = Ipv6Addr::from(raw);
            let port = u16::from_be_bytes([frame[20], frame[21]]);
            (SocketAddr::new(IpAddr::V6(ip), port), 22)
        }
        t => {
            return Err(io::Error::new(io::ErrorKind::InvalidData, format!("SOCKS5 UDP: unknown ATYP={t}")));
        }
    };
    Ok((addr, &frame[data_start..]))
}
