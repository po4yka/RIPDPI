use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use crate::socks::target::RelayTargetAddr;

pub fn encode_udp_frame(target: &RelayTargetAddr, payload: &[u8]) -> io::Result<Vec<u8>> {
    let mut frame = Vec::with_capacity(payload.len() + 32);
    frame.extend_from_slice(&[0x00, 0x00, 0x00]);
    match target {
        RelayTargetAddr::Ip(SocketAddr::V4(addr)) => {
            frame.push(0x01);
            frame.extend_from_slice(&addr.ip().octets());
            frame.extend_from_slice(&addr.port().to_be_bytes());
        }
        RelayTargetAddr::Ip(SocketAddr::V6(addr)) => {
            frame.push(0x04);
            frame.extend_from_slice(&addr.ip().octets());
            frame.extend_from_slice(&addr.port().to_be_bytes());
        }
        RelayTargetAddr::Domain(host, port) => {
            let len = u8::try_from(host.len())
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SOCKS5 domain target exceeds 255 bytes"))?;
            frame.push(0x03);
            frame.push(len);
            frame.extend_from_slice(host.as_bytes());
            frame.extend_from_slice(&port.to_be_bytes());
        }
    }
    frame.extend_from_slice(payload);
    Ok(frame)
}

pub fn decode_udp_frame(frame: &[u8]) -> io::Result<(RelayTargetAddr, &[u8])> {
    if frame.len() < 4 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5 UDP frame too short"));
    }
    if frame[2] != 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "fragmented SOCKS5 UDP frames are not supported"));
    }

    let target = match frame[3] {
        0x01 => {
            if frame.len() < 10 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated SOCKS5 IPv4 UDP frame"));
            }
            let ip = IpAddr::V4(Ipv4Addr::new(frame[4], frame[5], frame[6], frame[7]));
            let port = u16::from_be_bytes([frame[8], frame[9]]);
            (RelayTargetAddr::Ip(SocketAddr::new(ip, port)), 10)
        }
        0x03 => {
            if frame.len() < 5 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated SOCKS5 domain UDP frame"));
            }
            let len = usize::from(frame[4]);
            if frame.len() < 5 + len + 2 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated SOCKS5 domain payload"));
            }
            let host = std::str::from_utf8(&frame[5..5 + len])
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS5 domain payload"))?;
            let port = u16::from_be_bytes([frame[5 + len], frame[5 + len + 1]]);
            (RelayTargetAddr::Domain(host.to_string(), port), 5 + len + 2)
        }
        0x04 => {
            if frame.len() < 22 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated SOCKS5 IPv6 UDP frame"));
            }
            let mut octets = [0u8; 16];
            octets.copy_from_slice(&frame[4..20]);
            let port = u16::from_be_bytes([frame[20], frame[21]]);
            (RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(octets)), port)), 22)
        }
        atyp => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("unsupported SOCKS5 UDP address type {atyp:#x}"),
            ));
        }
    };

    Ok((target.0, &frame[target.1..]))
}

#[cfg(test)]
mod tests {
    use crate::socks::target::RelayTargetAddr;
    use crate::socks::udp_frame::{decode_udp_frame, encode_udp_frame};

    #[test]
    fn socks5_udp_domain_roundtrip() {
        let target = RelayTargetAddr::Domain("example.com".to_string(), 443);
        let payload = b"hello";
        let frame = encode_udp_frame(&target, payload).expect("encode");
        let (decoded_target, decoded_payload) = decode_udp_frame(&frame).expect("decode");
        assert_eq!(decoded_target, target);
        assert_eq!(decoded_payload, payload);
    }
}
