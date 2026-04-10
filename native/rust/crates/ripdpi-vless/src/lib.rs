pub mod addons;
pub mod config;
pub mod reality;
pub mod vision;
pub mod wire;

use std::io;
use std::net::{IpAddr, SocketAddr, ToSocketAddrs};

use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};

use crate::addons::VISION_ADDONS;
use crate::config::VlessRealityConfig;
use crate::vision::VisionStream;
use tokio_boring::SslStream;

/// Trait alias for an async bidirectional stream that is `Send`.
pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

/// VLESS+Reality client.
///
/// Provides two connection methods:
/// - `connect()`: opens a fresh TCP connection to the server
/// - `connect_over()`: performs VLESS+Reality over an existing transport (for chain relay)
pub struct VlessRealityClient;

impl VlessRealityClient {
    /// Open `TCP -> Reality TLS -> VLESS handshake -> VisionStream`.
    pub async fn connect(config: &VlessRealityConfig, target: &str) -> io::Result<VisionStream<SslStream<TcpStream>>> {
        Self::connect_with_optional_bind(config, None, target).await
    }

    /// Open `TCP -> Reality TLS -> VLESS handshake -> VisionStream` while binding
    /// the underlying TCP socket to a specific local IP.
    pub async fn connect_with_bind(
        config: &VlessRealityConfig,
        bind_ip: IpAddr,
        target: &str,
    ) -> io::Result<VisionStream<SslStream<TcpStream>>> {
        Self::connect_with_optional_bind(config, Some(bind_ip), target).await
    }

    async fn connect_with_optional_bind(
        config: &VlessRealityConfig,
        bind_ip: Option<IpAddr>,
        target: &str,
    ) -> io::Result<VisionStream<SslStream<TcpStream>>> {
        let addr = format!("{}:{}", config.server, config.port);
        tracing::debug!(server = %addr, target, ?bind_ip, "VLESS+Reality: connecting");

        let tcp = connect_tcp(config, bind_ip).await?;
        let tls = reality::connect_reality_tls(tcp, config).await?;
        Self::vless_handshake_and_wrap(tls, config, target).await
    }

    /// Perform `Reality TLS -> VLESS handshake` over an existing transport.
    ///
    /// Used for chain relay: the `transport` is the output of a previous
    /// `VlessRealityClient::connect()` call (first hop), and we layer a second
    /// VLESS+Reality connection on top of it to reach the final destination.
    pub async fn connect_over<S>(config: &VlessRealityConfig, transport: S, target: &str) -> io::Result<impl AsyncIo>
    where
        S: AsyncIo + 'static,
    {
        tracing::debug!(
            server_name = %config.server_name,
            target,
            "VLESS+Reality (chained): connecting over existing transport"
        );

        let tls = reality::connect_reality_tls_over(transport, config).await?;
        Self::vless_handshake_and_wrap(tls, config, target).await
    }

    /// Send the VLESS request, read the response, and wrap in a VisionStream.
    async fn vless_handshake_and_wrap<S>(
        mut tls: S,
        config: &VlessRealityConfig,
        target: &str,
    ) -> io::Result<VisionStream<S>>
    where
        S: AsyncIo + 'static,
    {
        // Write VLESS request
        let request = wire::encode_request(&config.uuid, VISION_ADDONS, target);
        tls.write_all(&request).await?;

        // Read VLESS response header
        wire::read_response(&mut tls).await?;

        tracing::debug!(target, "VLESS handshake completed");

        // Wrap in VisionStream for TLS-in-TLS detection avoidance
        Ok(VisionStream::new(tls))
    }
}

async fn connect_tcp(config: &VlessRealityConfig, bind_ip: Option<IpAddr>) -> io::Result<TcpStream> {
    let address = resolve_server_addr(config, bind_ip)?;
    let socket = match address {
        SocketAddr::V4(_) => TcpSocket::new_v4()?,
        SocketAddr::V6(_) => TcpSocket::new_v6()?,
    };
    if let Some(bind_ip) = bind_ip {
        let bind_addr = match (bind_ip, address) {
            (IpAddr::V4(ip), SocketAddr::V4(_)) => SocketAddr::new(IpAddr::V4(ip), 0),
            (IpAddr::V6(ip), SocketAddr::V6(_)) => SocketAddr::new(IpAddr::V6(ip), 0),
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "outbound bind IP family does not match relay server address family",
                ));
            }
        };
        socket.bind(bind_addr)?;
    }
    let stream = socket
        .connect(address)
        .await
        .map_err(|e| io::Error::new(e.kind(), format!("VLESS TCP connect to {address}: {e}")))?;
    stream.set_nodelay(true)?;
    Ok(stream)
}

fn resolve_server_addr(config: &VlessRealityConfig, bind_ip: Option<IpAddr>) -> io::Result<SocketAddr> {
    let mut candidates = (config.server.as_str(), config.port)
        .to_socket_addrs()
        .map_err(|e| io::Error::new(e.kind(), format!("resolve {}:{}: {e}", config.server, config.port)))?;
    if let Some(bind_ip) = bind_ip {
        candidates.find(|address| address.is_ipv4() == bind_ip.is_ipv4()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "relay server has no address matching outbound bind IP family")
        })
    } else {
        candidates
            .next()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "relay server resolved to no addresses"))
    }
}
