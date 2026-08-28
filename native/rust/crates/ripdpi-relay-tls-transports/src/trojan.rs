use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};

use crate::util::to_io_error;

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

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        let (addr, port) = target_to_trojan(target)?;
        let stream = ripdpi_trojan::TrojanClient::connect_tcp(&self.client_config, &addr, port, &[])
            .await
            .map_err(to_io_error)?;
        Ok(Box::new(stream) as Box<dyn AsyncIo>)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        let stream =
            ripdpi_trojan::TrojanClient::connect_udp_associate(&self.client_config).await.map_err(to_io_error)?;
        Ok(TrojanUdpSession { stream: Box::new(stream) })
    }
}

pub async fn connect_trojan_tcp(
    config: &TrojanClientConfig,
    target: &str,
) -> io::Result<impl AsyncRead + AsyncWrite + Unpin + Send + use<>> {
    let (addr, port) = target_to_trojan(target)?;
    ripdpi_trojan::TrojanClient::connect_tcp(config, &addr, port, &[]).await.map_err(to_io_error)
}

pub async fn connect_trojan_tcp_over<S>(
    config: &TrojanClientConfig,
    transport: S,
    target: &str,
) -> io::Result<impl AsyncRead + AsyncWrite + Unpin + Send + use<S>>
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
    async fn shutdown(&self) -> Result<(), Self::Error> {
        Ok(())
    }

    type Session = TrojanSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: false }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let client_config = self.client_config.clone();
        Ok(Arc::new(TrojanSession { client_config }))
    }
}

fn target_to_trojan(target: &str) -> io::Result<(ripdpi_trojan::TrojanAddr, u16)> {
    if let Ok(addr) = target.parse::<SocketAddr>() {
        return Ok((ripdpi_trojan::TrojanAddr::from(addr.ip()), addr.port()));
    }
    let (host, port) = crate::util::split_target_authority(target)?;
    Ok((ripdpi_trojan::TrojanAddr::Domain(host.to_string()), port))
}

fn trojan_authority(addr: ripdpi_trojan::TrojanAddr, port: u16) -> String {
    match addr {
        ripdpi_trojan::TrojanAddr::Ipv4(ip) => SocketAddr::new(IpAddr::V4(ip), port).to_string(),
        ripdpi_trojan::TrojanAddr::Ipv6(ip) => SocketAddr::new(IpAddr::V6(ip), port).to_string(),
        ripdpi_trojan::TrojanAddr::Domain(host) => format!("{host}:{port}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Regression test (audit H4): a bare IPv6 literal must be rejected with
    /// `InvalidInput` instead of being silently split into a corrupted host
    /// (`"2001:db8:"`) and a bogus port (`1`).
    #[test]
    fn target_to_trojan_rejects_bare_ipv6_target() {
        let error = target_to_trojan("2001:db8::1").expect_err("bare IPv6 target must be rejected");
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn target_to_trojan_accepts_domain_target() {
        let (_, port) = target_to_trojan("example.com:443").expect("domain target parses");
        assert_eq!(port, 443);
    }

    #[test]
    fn target_to_trojan_accepts_bracketed_ipv6_target() {
        let (_, port) = target_to_trojan("[2001:db8::1]:443").expect("bracketed IPv6 target parses");
        assert_eq!(port, 443);
    }
}
