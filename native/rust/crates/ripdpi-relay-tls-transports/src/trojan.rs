use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;

use ripdpi_relay_mux::{BoxFuture, RelayCapabilities, RelaySession, RelaySessionFactory};
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};

pub use ripdpi_trojan::TrojanClientConfig;

pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

#[derive(Clone)]
pub struct TrojanSessionFactory {
    pub client_config: ripdpi_trojan::TrojanClientConfig,
}

pub struct TrojanSession {
    pub client_config: ripdpi_trojan::TrojanClientConfig,
}

pub struct TrojanUdpSession {
    stream: Box<dyn AsyncIo>,
}

impl TrojanUdpSession {
    pub async fn send_to(&mut self, target: &str, payload: &[u8]) -> io::Result<()> {
        let (addr, port) = target_to_trojan(target)?;
        let packet = ripdpi_trojan::encode_udp_packet(&addr, port, payload).map_err(to_io_error)?;
        self.stream.write_all(&packet).await
    }

    pub async fn recv_from(&mut self) -> io::Result<(String, Vec<u8>)> {
        let packet = ripdpi_trojan::read_udp_packet(&mut self.stream).await.map_err(to_io_error)?;
        Ok((trojan_authority(packet.addr, packet.port), packet.payload))
    }
}

impl RelaySession for TrojanSession {
    type Stream = Box<dyn AsyncIo>;
    type Datagram = TrojanUdpSession;
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move {
            let (addr, port) = target_to_trojan(target)?;
            let stream = ripdpi_trojan::TrojanClient::connect_tcp(&self.client_config, &addr, port, &[])
                .await
                .map_err(to_io_error)?;
            Ok(Box::new(stream) as Box<dyn AsyncIo>)
        })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            let stream =
                ripdpi_trojan::TrojanClient::connect_udp_associate(&self.client_config).await.map_err(to_io_error)?;
            Ok(TrojanUdpSession { stream: Box::new(stream) })
        })
    }
}

pub async fn connect_trojan_tcp(
    config: &TrojanClientConfig,
    target: &str,
) -> io::Result<impl AsyncRead + AsyncWrite + Unpin + Send> {
    let (addr, port) = target_to_trojan(target)?;
    ripdpi_trojan::TrojanClient::connect_tcp(config, &addr, port, &[]).await.map_err(to_io_error)
}

pub async fn connect_trojan_tcp_over<S>(
    config: &TrojanClientConfig,
    transport: S,
    target: &str,
) -> io::Result<impl AsyncRead + AsyncWrite + Unpin + Send>
where
    S: AsyncRead + AsyncWrite + Unpin + Send,
{
    let (addr, port) = target_to_trojan(target)?;
    ripdpi_trojan::TrojanClient::connect_tcp_over(config, transport, &addr, port, &[]).await.map_err(to_io_error)
}

pub fn trojan_proxy_target(config: &TrojanClientConfig) -> String {
    format!("{}:{}", config.server_host, config.server_port)
}

impl RelaySessionFactory for TrojanSessionFactory {
    type Session = TrojanSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: false }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let client_config = self.client_config.clone();
        Box::pin(async move { Ok(Arc::new(TrojanSession { client_config })) })
    }
}

fn target_to_trojan(target: &str) -> io::Result<(ripdpi_trojan::TrojanAddr, u16)> {
    if let Ok(addr) = target.parse::<SocketAddr>() {
        return Ok((ripdpi_trojan::TrojanAddr::from(addr.ip()), addr.port()));
    }
    let (host, port) = target
        .rsplit_once(':')
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target authority: {target}")))?;
    let port = port.parse::<u16>().map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target port in authority: {target}"))
    })?;
    Ok((ripdpi_trojan::TrojanAddr::Domain(host.to_string()), port))
}

fn trojan_authority(addr: ripdpi_trojan::TrojanAddr, port: u16) -> String {
    match addr {
        ripdpi_trojan::TrojanAddr::Ipv4(ip) => SocketAddr::new(IpAddr::V4(ip), port).to_string(),
        ripdpi_trojan::TrojanAddr::Ipv6(ip) => SocketAddr::new(IpAddr::V6(ip), port).to_string(),
        ripdpi_trojan::TrojanAddr::Domain(host) => format!("{host}:{port}"),
    }
}

fn to_io_error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}
