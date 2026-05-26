use std::io;
use std::net::SocketAddr;

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

pub(crate) async fn negotiate_socks5(client: &mut TcpStream) -> io::Result<()> {
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS version"));
    }

    let method_count = usize::from(greeting[1]);
    let mut methods = vec![0u8; method_count];
    client.read_exact(&mut methods).await?;
    if methods.contains(&0x00) {
        client.write_all(&[0x05, 0x00]).await?;
        Ok(())
    } else {
        client.write_all(&[0x05, 0xff]).await?;
        Err(io::Error::new(io::ErrorKind::Unsupported, "client does not support unauthenticated SOCKS5"))
    }
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
