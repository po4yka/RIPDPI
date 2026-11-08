use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::time::Instant;

use ripdpi_network_time::{NetworkTimeProvider, TimeSource};
use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};
use ripdpi_shadowsocks::UdpAssociation;
use shadowsocks::{
    config::ServerConfig,
    context::SharedContext,
    net::UdpSocket as ProxyUdpSocket,
    relay::{
        socks5::Address,
        tcprelay::ProxyClientStream,
        udprelay::{
            ProxySocket,
            proxy_socket::{ProxySocketError, UdpSocketType},
        },
    },
};
use socket2::{Domain, Protocol, Socket, Type};
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream, UdpSocket};

const BUFFER_SIZE: usize = 65_536;

/// The upstream stream owns its carrier; shutdown preserves the response direction.
pub type ShadowsocksStream = ProxyClientStream<Box<dyn crate::trojan::AsyncIo>>;

#[derive(Clone)]
pub struct ShadowsocksSessionFactory {
    config: Arc<ShadowsocksClientConfig>,
}

pub struct ShadowsocksSession {
    config: Arc<ShadowsocksClientConfig>,
}

pub struct ShadowsocksUdpSession {
    socket: ProxySocket<ProxyUdpSocket>,
    config: Arc<ShadowsocksClientConfig>,
    association: Box<UdpAssociation>,
    receive_buffer: Vec<u8>,
    calibrated: bool,
}

struct ShadowsocksClientConfig {
    server_host: String,
    server_port: u16,
    server: ServerConfig,
    context: SharedContext,
    outbound_bind_ip: Option<IpAddr>,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
}

impl ShadowsocksSessionFactory {
    pub fn supports_udp(method: &str) -> bool {
        ripdpi_shadowsocks::method(method).is_ok_and(|cipher| cipher.is_aead_2022())
    }

    pub fn new(
        server_host: String,
        server_port: u16,
        method: String,
        password: String,
        outbound_bind_ip: Option<IpAddr>,
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    ) -> io::Result<Self> {
        let server = ripdpi_shadowsocks::server_config(server_host.clone(), server_port, &method, password)?;
        Ok(Self {
            config: Arc::new(ShadowsocksClientConfig {
                server_host,
                server_port,
                server,
                context: ripdpi_shadowsocks::client_context(),
                outbound_bind_ip,
                socket_protection,
            }),
        })
    }
}

impl RelaySession for ShadowsocksSession {
    type Stream = ShadowsocksStream;
    type Datagram = ShadowsocksUdpSession;
    type Error = io::Error;

    /// # Cancel safety:
    /// cancel-safe: cancellation drops the owned transport and stream state.
    async fn open_stream(&self, target: &str) -> io::Result<Self::Stream> {
        connect_tcp(Arc::clone(&self.config), target).await
    }

    /// # Cancel safety:
    /// cancel-safe: cancellation drops the owned socket before it is returned.
    async fn open_datagram(&self) -> io::Result<Self::Datagram> {
        let config = Arc::clone(&self.config);
        if !config.server.method().is_aead_2022() {
            return Err(io::Error::new(io::ErrorKind::Unsupported, "Shadowsocks UDP requires SS2022"));
        }
        let addrs = config.socket_protection.resolve_host(&config.server_host, config.server_port).await?;
        let server_addr = match config.outbound_bind_ip {
            Some(ip) => addrs.into_iter().find(|addr| addr.is_ipv4() == ip.is_ipv4()),
            None => addrs.iter().copied().find(SocketAddr::is_ipv4).or_else(|| addrs.into_iter().next()),
        }
        .ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "no UDP server address matches socket family")
        })?;
        let socket = bind_udp(config.outbound_bind_ip, server_addr, config.socket_protection)?;
        socket.connect(server_addr).await?;
        let socket = ProxySocket::from_socket(
            UdpSocketType::Client,
            Arc::clone(&config.context),
            &config.server,
            ProxyUdpSocket::from(socket),
        );
        Ok(ShadowsocksUdpSession {
            socket,
            config,
            association: Box::default(),
            receive_buffer: vec![0; BUFFER_SIZE],
            calibrated: false,
        })
    }
}

impl RelaySessionFactory for ShadowsocksSessionFactory {
    type Session = ShadowsocksSession;
    type Error = io::Error;

    /// # Cancel safety:
    /// cancel-safe: no asynchronous resource is owned by the factory.
    async fn shutdown(&self) -> io::Result<()> {
        Ok(())
    }

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: self.config.server.method().is_aead_2022(), reusable: false }
    }

    /// # Cancel safety:
    /// cancel-safe: this future has no suspension point.
    async fn create_session(&self) -> io::Result<Arc<Self::Session>> {
        Ok(Arc::new(ShadowsocksSession { config: Arc::clone(&self.config) }))
    }
}

impl ShadowsocksUdpSession {
    /// # Cancel safety:
    /// cancel-safe: cancellation can skip a packet ID but never reuse it.
    pub async fn send_to(&mut self, target: &str, payload: &[u8]) -> io::Result<()> {
        let address = target_address(target)?;
        let control = self.association.next_control()?;
        self.socket.send_with_ctrl(&address, &control, payload).await.map_err(|error| {
            let error = io::Error::from(error);
            // Encryption includes address and identity overhead; the OS decides what fits.
            if error.raw_os_error() == Some(libc::EMSGSIZE) {
                io::Error::new(io::ErrorKind::InvalidInput, error)
            } else {
                error
            }
        })?;
        Ok(())
    }

    /// Drops invalid datagrams without ending the association.
    /// # Cancel safety:
    /// cancel-safe: receive is cancellation safe; validation and delivery have no await point.
    pub async fn recv_from(&mut self) -> io::Result<(String, Vec<u8>)> {
        loop {
            let (read, address, _, control) = match self.socket.recv_with_ctrl(&mut self.receive_buffer).await {
                Ok(packet) => packet,
                Err(ProxySocketError::IoError(error)) if error.kind() != io::ErrorKind::ConnectionRefused => {
                    return Err(error);
                }
                Err(_) => continue,
            };
            let Some(timestamp) = control.and_then(|control| self.association.accept(&control, Instant::now())) else {
                continue;
            };
            if !self.calibrated {
                let source = TimeSource::new("shadowsocks", &self.config.server_host, self.config.server_port, 1);
                NetworkTimeProvider::shared().calibrate(i64::try_from(timestamp).unwrap_or(i64::MAX), source);
                self.calibrated = true;
            }
            return Ok((address.to_string(), self.receive_buffer[..read].to_vec()));
        }
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops owned transport and stream state.
pub async fn connect_shadowsocks_tcp(
    factory: &ShadowsocksSessionFactory,
    target: &str,
) -> io::Result<ShadowsocksStream> {
    connect_tcp(Arc::clone(&factory.config), target).await
}

/// # Cancel safety:
/// cancel-safe: cancellation drops owned transport and stream state.
pub async fn connect_shadowsocks_tcp_over<S>(
    factory: &ShadowsocksSessionFactory,
    transport: S,
    target: &str,
) -> io::Result<ShadowsocksStream>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    connect_tcp_over_transport(Arc::clone(&factory.config), transport, target).await
}

pub fn shadowsocks_proxy_target(factory: &ShadowsocksSessionFactory) -> String {
    factory.config.server.addr().to_string()
}

/// # Cancel safety:
/// cancel-safe: cancellation drops owned transport and stream state.
async fn connect_tcp(config: Arc<ShadowsocksClientConfig>, target: &str) -> io::Result<ShadowsocksStream> {
    target_address(target)?;
    let socket = connect_server(&config).await?;
    connect_tcp_over_transport(config, socket, target).await
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the upstream stream and its carrier together.
async fn connect_tcp_over_transport<S>(
    config: Arc<ShadowsocksClientConfig>,
    transport: S,
    target: &str,
) -> io::Result<ShadowsocksStream>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let transport: Box<dyn crate::trojan::AsyncIo> = Box::new(transport);
    let mut stream =
        ProxyClientStream::from_stream(Arc::clone(&config.context), transport, &config.server, target_address(target)?);
    // write_all([]) does not poll the writer. Send the address now for server-first protocols.
    let _written = stream.write(&[]).await?;
    stream.flush().await?;
    Ok(stream)
}

fn target_address(target: &str) -> io::Result<Address> {
    if let Ok(address) = target.parse::<SocketAddr>() {
        return Ok(Address::SocketAddress(address));
    }
    let (host, port) = crate::util::split_target_authority(target)?;
    if host.is_empty() || host.len() > 255 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid Shadowsocks target host"));
    }
    Ok(Address::DomainNameAddress(host.to_owned(), port))
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the protected socket.
async fn connect_server(config: &ShadowsocksClientConfig) -> io::Result<TcpStream> {
    let bind_ip = config.outbound_bind_ip;
    let addrs = config.socket_protection.resolve_host(&config.server_host, config.server_port).await?;
    let server_addr = match bind_ip {
        Some(ip) => addrs.into_iter().find(|addr| addr.is_ipv4() == ip.is_ipv4()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "no server address matches outbound bind IP family")
        })?,
        None => addrs.into_iter().next().ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "no address resolved for shadowsocks server")
        })?,
    };
    let socket = match server_addr {
        SocketAddr::V4(_) => TcpSocket::new_v4()?,
        SocketAddr::V6(_) => TcpSocket::new_v6()?,
    };
    // VpnService.protect() invariant: protect the carrier fd BEFORE bind/connect
    // so this non-loopback socket bypasses the app's own TUN route (otherwise it
    // loops back into the tunnel the VPN owns). Loopback-skip and fail-closed are
    // handled by the shared helper. REL-1.
    crate::protect::protect_carrier_socket(&socket, server_addr, config.socket_protection)?;
    if let Some(ip) = bind_ip {
        socket.bind(SocketAddr::new(ip, 0))?;
    }
    socket.connect(server_addr).await
}

fn bind_udp(
    bind_ip: Option<IpAddr>,
    server_addr: SocketAddr,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<UdpSocket> {
    let ip = bind_ip.unwrap_or(match server_addr {
        SocketAddr::V4(_) => IpAddr::V4(Ipv4Addr::UNSPECIFIED),
        SocketAddr::V6(_) => IpAddr::V6(Ipv6Addr::UNSPECIFIED),
    });
    let bind_addr = SocketAddr::new(ip, 0);
    let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    crate::protect::protect_carrier_socket(&socket, server_addr, socket_protection)?;
    socket.bind(&bind_addr.into())?;
    socket.set_nonblocking(true)?;
    UdpSocket::from_std(socket.into())
}

#[cfg(test)]
mod tests;
