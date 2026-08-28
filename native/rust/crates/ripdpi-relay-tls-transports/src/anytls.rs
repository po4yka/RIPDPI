use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};
use tokio::io::{AsyncRead, AsyncWrite};

use crate::session_registry::{OwnedSession, SessionRegistry};

use crate::util::to_io_error;

pub use ripdpi_anytls::session::AnyTlsClientConfig;

#[derive(Clone)]
pub struct AnyTlsSessionFactory {
    client_config: AnyTlsClientConfig,
    sessions: Arc<SessionRegistry<AnyTlsSession>>,
}

impl AnyTlsSessionFactory {
    pub fn new(client_config: AnyTlsClientConfig) -> Self {
        Self { client_config, sessions: Arc::default() }
    }
}

pub struct AnyTlsSession {
    client: ripdpi_anytls::session::AnyTlsClient,
}

pub struct AnyTlsUdpSession {
    udp: ripdpi_anytls::session::AnyTlsUdpOverTcp,
}

impl OwnedSession for AnyTlsSession {
    fn abort(&self) {
        self.client.cancel();
    }
    async fn close(&self) -> io::Result<()> {
        self.client.close().await.map_err(to_io_error)
    }
}

impl RelaySession for AnyTlsSession {
    type Stream = ripdpi_anytls::session::AnyTlsIo;
    type Datagram = AnyTlsUdpSession;
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        let (addr, port) = target_to_anytls(target)?;
        let stream = self.client.open_tcp(addr, port).await.map_err(to_io_error)?;
        stream.into_io().map_err(to_io_error)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        let udp = self.client.open_udp_over_tcp().await.map_err(to_io_error)?;
        Ok(AnyTlsUdpSession { udp })
    }
}

impl RelaySessionFactory for AnyTlsSessionFactory {
    async fn shutdown(&self) -> Result<(), Self::Error> {
        self.sessions.shutdown().await
    }

    type Session = AnyTlsSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        self.sessions
            .create(async {
                let client =
                    ripdpi_anytls::session::AnyTlsClient::new(self.client_config.clone()).map_err(to_io_error)?;
                Ok(AnyTlsSession { client })
            })
            .await
    }
}

impl AnyTlsUdpSession {
    pub async fn send_to(&mut self, target: &str, payload: &[u8]) -> io::Result<()> {
        let (addr, port) = target_to_anytls(target)?;
        self.udp.send_datagram(addr, port, payload).await.map_err(to_io_error)
    }

    pub async fn recv_from(&mut self) -> io::Result<(String, Vec<u8>)> {
        let datagram = self.udp.recv_datagram().await.map_err(to_io_error)?;
        Ok((anytls_authority(datagram.target, datagram.port), datagram.payload))
    }
}

pub async fn connect_anytls_tcp(
    config: &AnyTlsClientConfig,
    target: &str,
) -> io::Result<impl AsyncRead + AsyncWrite + Unpin + Send + use<>> {
    let client = ripdpi_anytls::session::AnyTlsClient::new(config.clone()).map_err(to_io_error)?;
    let (addr, port) = target_to_anytls(target)?;
    let stream = client.open_tcp(addr, port).await.map_err(to_io_error)?;
    stream.into_io().map_err(to_io_error)
}

pub async fn connect_anytls_tcp_over<S>(
    config: &AnyTlsClientConfig,
    transport: S,
    target: &str,
) -> io::Result<impl AsyncRead + AsyncWrite + Unpin + Send + use<S>>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let (addr, port) = target_to_anytls(target)?;
    let stream = ripdpi_anytls::session::AnyTlsClient::open_tcp_over(config.clone(), transport, addr, port)
        .await
        .map_err(to_io_error)?;
    stream.into_io().map_err(to_io_error)
}

pub fn anytls_proxy_target(config: &AnyTlsClientConfig) -> String {
    format!("{}:{}", config.server_host, config.server_port)
}

fn target_to_anytls(target: &str) -> io::Result<(ripdpi_anytls::session::TargetAddr, u16)> {
    if let Ok(addr) = target.parse::<SocketAddr>() {
        let target = match addr.ip() {
            IpAddr::V4(ip) => ripdpi_anytls::session::TargetAddr::Ipv4(ip),
            IpAddr::V6(ip) => ripdpi_anytls::session::TargetAddr::Ipv6(ip),
        };
        return Ok((target, addr.port()));
    }
    let (host, port) = crate::util::split_target_authority(target)?;
    Ok((ripdpi_anytls::session::TargetAddr::Domain(host.to_string()), port))
}

fn anytls_authority(addr: ripdpi_anytls::session::TargetAddr, port: u16) -> String {
    match addr {
        ripdpi_anytls::session::TargetAddr::Ipv4(ip) => SocketAddr::new(IpAddr::V4(ip), port).to_string(),
        ripdpi_anytls::session::TargetAddr::Ipv6(ip) => SocketAddr::new(IpAddr::V6(ip), port).to_string(),
        ripdpi_anytls::session::TargetAddr::Domain(host) => format!("{host}:{port}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Regression test (audit H4): a bare IPv6 literal must be rejected with
    /// `InvalidInput` instead of being silently split into a corrupted host
    /// (`"2001:db8:"`) and a bogus port (`1`).
    #[test]
    fn target_to_anytls_rejects_bare_ipv6_target() {
        let error = target_to_anytls("2001:db8::1").expect_err("bare IPv6 target must be rejected");
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn target_to_anytls_accepts_domain_target() {
        let (_, port) = target_to_anytls("example.com:443").expect("domain target parses");
        assert_eq!(port, 443);
    }

    #[test]
    fn target_to_anytls_accepts_bracketed_ipv6_target() {
        let (_, port) = target_to_anytls("[2001:db8::1]:443").expect("bracketed IPv6 target parses");
        assert_eq!(port, 443);
    }
}
