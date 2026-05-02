use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::backend::RelayBackend;
use crate::socks::connect::handle_connect;
use crate::socks::reply::write_reply;
use crate::socks::target::RelayTargetAddr;
use crate::socks::telemetry::{SocksSessionConfig, SocksTelemetry};
use crate::socks::udp::handle_udp_associate;

pub(crate) async fn handle_client<T>(
    mut client: TcpStream,
    backend: Arc<RelayBackend>,
    config: SocksSessionConfig,
    telemetry: &T,
) -> io::Result<()>
where
    T: SocksTelemetry + ?Sized,
{
    read_no_auth_greeting(&mut client).await?;
    client.write_all(&[0x05, 0x00]).await?;

    let mut request_header = [0u8; 4];
    client.read_exact(&mut request_header).await?;
    if request_header[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 request"));
    }

    let command = request_header[1];
    let target = read_target(&mut client, request_header[3]).await?;
    telemetry.record_target(target.to_string());

    match command {
        0x01 => handle_connect(client, backend, target, telemetry).await,
        0x03 => handle_udp_associate(client, backend, config, telemetry).await,
        _ => {
            write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            Err(io::Error::new(io::ErrorKind::Unsupported, format!("SOCKS5 command {command:#x} is not supported")))
        }
    }
}

async fn read_no_auth_greeting<S>(stream: &mut S) -> io::Result<()>
where
    S: AsyncRead + Unpin,
{
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 version"));
    }

    let method_count = usize::from(greeting[1]);
    let mut methods = vec![0u8; method_count];
    stream.read_exact(&mut methods).await?;
    Ok(())
}

pub(crate) async fn read_target<S>(stream: &mut S, address_type: u8) -> io::Result<RelayTargetAddr>
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
