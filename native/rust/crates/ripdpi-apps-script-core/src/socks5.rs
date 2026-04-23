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
    pub fn host(&self) -> String {
        match self {
            Self::Ip(address) => address.ip().to_string(),
            Self::Domain(host, _) => host.clone(),
        }
    }

    pub fn port(&self) -> u16 {
        match self {
            Self::Ip(address) => address.port(),
            Self::Domain(_, port) => *port,
        }
    }
}

impl fmt::Display for RelayTargetAddr {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Ip(address) => address.fmt(formatter),
            Self::Domain(host, port) => write!(formatter, "{host}:{port}"),
        }
    }
}

pub async fn read_target<S>(stream: &mut S, address_type: u8) -> io::Result<RelayTargetAddr>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    match address_type {
        0x01 => {
            let mut octets = [0u8; 4];
            stream.read_exact(&mut octets).await?;
            let mut port = [0u8; 2];
            stream.read_exact(&mut port).await?;
            Ok(RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(octets)), u16::from_be_bytes(port))))
        }
        0x03 => {
            let length = usize::from(stream.read_u8().await?);
            let mut host = vec![0u8; length];
            stream.read_exact(&mut host).await?;
            let mut port = [0u8; 2];
            stream.read_exact(&mut port).await?;
            let host = String::from_utf8(host)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS5 domain"))?;
            Ok(RelayTargetAddr::Domain(host, u16::from_be_bytes(port)))
        }
        0x04 => {
            let mut octets = [0u8; 16];
            stream.read_exact(&mut octets).await?;
            let mut port = [0u8; 2];
            stream.read_exact(&mut port).await?;
            Ok(RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(octets)), u16::from_be_bytes(port))))
        }
        value => {
            Err(io::Error::new(io::ErrorKind::InvalidInput, format!("unsupported SOCKS5 address type {value:#x}")))
        }
    }
}

pub async fn write_reply<S>(stream: &mut S, reply_code: u8, bound: SocketAddr) -> io::Result<()>
where
    S: AsyncWrite + Unpin,
{
    match bound {
        SocketAddr::V4(address) => {
            let mut payload = vec![0x05, reply_code, 0x00, 0x01];
            payload.extend_from_slice(&address.ip().octets());
            payload.extend_from_slice(&address.port().to_be_bytes());
            stream.write_all(&payload).await
        }
        SocketAddr::V6(address) => {
            let mut payload = vec![0x05, reply_code, 0x00, 0x04];
            payload.extend_from_slice(&address.ip().octets());
            payload.extend_from_slice(&address.port().to_be_bytes());
            stream.write_all(&payload).await
        }
    }
}
