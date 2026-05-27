use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;

use ripdpi_relay_mux::{BoxFuture, RelayCapabilities, RelaySession, RelaySessionFactory};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

pub use ripdpi_anytls::session::AnyTlsClientConfig;

pub trait AnyTlsAsyncIo: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send {}
impl<T> AnyTlsAsyncIo for T where T: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send {}

#[derive(Clone)]
pub struct AnyTlsSessionFactory {
    pub client_config: ripdpi_anytls::session::AnyTlsClientConfig,
}

pub struct AnyTlsSession {
    client: ripdpi_anytls::session::AnyTlsClient,
}

pub struct AnyTlsUdpSession {
    udp: ripdpi_anytls::session::AnyTlsUdpOverTcp,
}

impl RelaySession for AnyTlsSession {
    type Stream = Box<dyn AnyTlsAsyncIo>;
    type Datagram = AnyTlsUdpSession;
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move {
            let (addr, port) = target_to_anytls(target)?;
            let stream = self.client.open_tcp(addr, port).await.map_err(to_io_error)?;
            Ok(Box::new(bridge_stream(stream)) as Box<dyn AnyTlsAsyncIo>)
        })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            let udp = self.client.open_udp_over_tcp().await.map_err(to_io_error)?;
            Ok(AnyTlsUdpSession { udp })
        })
    }
}

impl RelaySessionFactory for AnyTlsSessionFactory {
    type Session = AnyTlsSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let config = self.client_config.clone();
        Box::pin(async move {
            let client = ripdpi_anytls::session::AnyTlsClient::new(config).map_err(to_io_error)?;
            Ok(Arc::new(AnyTlsSession { client }))
        })
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

fn bridge_stream(mut anytls: ripdpi_anytls::session::AnyTlsStream) -> tokio::io::DuplexStream {
    let (app_stream, relay_stream) = tokio::io::duplex(65_536);
    let (mut app_read, mut app_write) = tokio::io::split(relay_stream);
    tokio::spawn(async move {
        let mut buffer = [0_u8; 16 * 1024];
        loop {
            tokio::select! {
                read = app_read.read(&mut buffer) => {
                    let Ok(read) = read else {
                        return;
                    };
                    if read == 0 {
                        return;
                    }
                    if anytls.write_all(&buffer[..read]).await.is_err() {
                        return;
                    }
                }
                chunk = anytls.read_chunk() => {
                    let Ok(chunk) = chunk else {
                        return;
                    };
                    if app_write.write_all(&chunk).await.is_err() {
                        return;
                    }
                }
            }
        }
    });
    app_stream
}

fn target_to_anytls(target: &str) -> io::Result<(ripdpi_anytls::session::TargetAddr, u16)> {
    if let Ok(addr) = target.parse::<SocketAddr>() {
        let target = match addr.ip() {
            IpAddr::V4(ip) => ripdpi_anytls::session::TargetAddr::Ipv4(ip),
            IpAddr::V6(ip) => ripdpi_anytls::session::TargetAddr::Ipv6(ip),
        };
        return Ok((target, addr.port()));
    }
    let (host, port) = target
        .rsplit_once(':')
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target authority: {target}")))?;
    let port = port.parse::<u16>().map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target port in authority: {target}"))
    })?;
    Ok((ripdpi_anytls::session::TargetAddr::Domain(host.to_string()), port))
}

fn anytls_authority(addr: ripdpi_anytls::session::TargetAddr, port: u16) -> String {
    match addr {
        ripdpi_anytls::session::TargetAddr::Ipv4(ip) => SocketAddr::new(IpAddr::V4(ip), port).to_string(),
        ripdpi_anytls::session::TargetAddr::Ipv6(ip) => SocketAddr::new(IpAddr::V6(ip), port).to_string(),
        ripdpi_anytls::session::TargetAddr::Domain(host) => format!("{host}:{port}"),
    }
}

fn to_io_error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}
