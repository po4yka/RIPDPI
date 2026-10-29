use std::io;
use std::net::SocketAddr;

use crate::config::NaiveProxyConfig;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SocksTarget {
    Domain(String, u16),
    Ip(SocketAddr),
}

impl SocksTarget {
    pub(crate) fn authority(&self) -> String {
        match self {
            Self::Domain(host, port) => format!("{host}:{port}"),
            Self::Ip(address) => address.to_string(),
        }
    }
}

/// # Cancel safety:
/// NOT cancel-safe: a partial handshake consumes bytes. The caller closes the client on timeout.
pub(crate) async fn negotiate_socks5(client: &mut TcpStream, config: &NaiveProxyConfig) -> io::Result<()> {
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS version"));
    }

    let method_count = usize::from(greeting[1]);
    let mut methods = vec![0u8; method_count];
    client.read_exact(&mut methods).await?;
    if !methods.contains(&0x02) {
        client.write_all(&[0x05, 0xff]).await?;
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "SOCKS5 authentication is required"));
    }
    client.write_all(&[0x05, 0x02]).await?;
    if client.read_u8().await? != 0x01 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS5 authentication version"));
    }
    let mut username = vec![0; usize::from(client.read_u8().await?)];
    client.read_exact(&mut username).await?;
    let mut password = vec![0; usize::from(client.read_u8().await?)];
    client.read_exact(&mut password).await?;
    if !config.accepts_local_credentials(&username, &password) {
        client.write_all(&[1, 1]).await?;
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "invalid local SOCKS5 credentials"));
    }
    client.write_all(&[1, 0]).await
}

pub(crate) async fn read_socks5_request(client: &mut TcpStream) -> io::Result<SocksTarget> {
    let mut header = [0u8; 4];
    client.read_exact(&mut header).await?;
    if header[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS request version"));
    }
    if header[1] != 0x01 {
        return Err(io::Error::new(io::ErrorKind::Unsupported, format!("unsupported SOCKS command {:#x}", header[1])));
    }
    if header[2] != 0x00 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS reserved byte"));
    }

    match header[3] {
        0x01 => {
            let mut address = [0u8; 4];
            client.read_exact(&mut address).await?;
            let port = read_port(client).await?;
            Ok(SocksTarget::Ip(SocketAddr::from((address, port))))
        }
        0x03 => {
            let mut len = [0u8; 1];
            client.read_exact(&mut len).await?;
            let mut host = vec![0u8; usize::from(len[0])];
            client.read_exact(&mut host).await?;
            let port = read_port(client).await?;
            let host = String::from_utf8(host)
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, format!("invalid SOCKS host: {error}")))?;
            Ok(SocksTarget::Domain(host, port))
        }
        0x04 => {
            let mut address = [0u8; 16];
            client.read_exact(&mut address).await?;
            let port = read_port(client).await?;
            Ok(SocksTarget::Ip(SocketAddr::from((address, port))))
        }
        atyp => Err(io::Error::new(io::ErrorKind::Unsupported, format!("unsupported SOCKS address type {atyp:#x}"))),
    }
}

pub(crate) async fn write_socks_reply(client: &mut TcpStream, reply: u8) -> io::Result<()> {
    let response = [0x05, reply, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
    client.write_all(&response).await
}

async fn read_port(client: &mut TcpStream) -> io::Result<u16> {
    let mut bytes = [0u8; 2];
    client.read_exact(&mut bytes).await?;
    Ok(u16::from_be_bytes(bytes))
}
