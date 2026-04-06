use std::fmt;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum RelayTargetAddr {
    Ip(SocketAddr),
    Domain(String, u16),
}

impl RelayTargetAddr {
    pub fn to_connect_target(&self) -> String {
        match self {
            Self::Ip(addr) => addr.to_string(),
            Self::Domain(host, port) => format!("{host}:{port}"),
        }
    }

    pub fn from_authority(authority: &str) -> io::Result<Self> {
        if let Ok(addr) = authority.parse::<SocketAddr>() {
            return Ok(Self::Ip(addr));
        }

        let (host, port) = authority.rsplit_once(':').ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target authority: {authority}"))
        })?;
        let port = port.parse::<u16>().map_err(|_| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target port in authority: {authority}"))
        })?;
        Ok(Self::Domain(host.to_string(), port))
    }
}

impl fmt::Display for RelayTargetAddr {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Ip(addr) => addr.fmt(f),
            Self::Domain(host, port) => write!(f, "{host}:{port}"),
        }
    }
}

pub async fn read_target<S>(stream: &mut S, address_type: u8) -> io::Result<RelayTargetAddr>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let target = match address_type {
        0x01 => {
            let mut octets = [0u8; 4];
            stream.read_exact(&mut octets).await?;
            let mut port_bytes = [0u8; 2];
            stream.read_exact(&mut port_bytes).await?;
            RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(octets)), u16::from_be_bytes(port_bytes)))
        }
        0x03 => {
            let len = stream.read_u8().await?;
            let mut host = vec![0u8; usize::from(len)];
            stream.read_exact(&mut host).await?;
            let mut port_bytes = [0u8; 2];
            stream.read_exact(&mut port_bytes).await?;
            let host = String::from_utf8(host)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS5 domain target"))?;
            RelayTargetAddr::Domain(host, u16::from_be_bytes(port_bytes))
        }
        0x04 => {
            let mut octets = [0u8; 16];
            stream.read_exact(&mut octets).await?;
            let mut port_bytes = [0u8; 2];
            stream.read_exact(&mut port_bytes).await?;
            RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(octets)), u16::from_be_bytes(port_bytes)))
        }
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("unsupported SOCKS5 address type {address_type:#x}"),
            ));
        }
    };
    Ok(target)
}

pub async fn write_reply<S>(stream: &mut S, reply_code: u8, bound: SocketAddr) -> io::Result<()>
where
    S: AsyncWrite + Unpin,
{
    match bound {
        SocketAddr::V4(addr) => {
            let mut payload = vec![0x05, reply_code, 0x00, 0x01];
            payload.extend_from_slice(&addr.ip().octets());
            payload.extend_from_slice(&addr.port().to_be_bytes());
            stream.write_all(&payload).await
        }
        SocketAddr::V6(addr) => {
            let mut payload = vec![0x05, reply_code, 0x00, 0x04];
            payload.extend_from_slice(&addr.ip().octets());
            payload.extend_from_slice(&addr.port().to_be_bytes());
            stream.write_all(&payload).await
        }
    }
}

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
    use super::*;

    #[test]
    fn socks5_udp_domain_roundtrip() {
        let target = RelayTargetAddr::Domain("example.com".to_string(), 443);
        let payload = b"hello";
        let frame = encode_udp_frame(&target, payload).expect("encode");
        let (decoded_target, decoded_payload) = decode_udp_frame(&frame).expect("decode");
        assert_eq!(decoded_target, target);
        assert_eq!(decoded_payload, payload);
    }

    #[test]
    fn relay_target_parses_ip_and_domain_authorities() {
        assert_eq!(
            RelayTargetAddr::from_authority("1.1.1.1:53").expect("ipv4"),
            RelayTargetAddr::Ip("1.1.1.1:53".parse().expect("socket addr"))
        );
        assert_eq!(
            RelayTargetAddr::from_authority("example.com:443").expect("domain"),
            RelayTargetAddr::Domain("example.com".to_string(), 443)
        );
    }
}
