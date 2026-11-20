use std::io;
use std::net::IpAddr;
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};
use ripdpi_snell::{SnellClientConfig, SnellStream};
use tokio::net::TcpStream;

use crate::shadowsocks::{connect_first, ordered_server_addrs};

/// Snell v4 relay: one protected TCP carrier per relayed stream (no connection reuse, no UDP).
#[derive(Clone)]
pub struct SnellSessionFactory {
    config: Arc<SnellConfig>,
}

pub struct SnellSession {
    config: Arc<SnellConfig>,
}

struct SnellConfig {
    server_host: String,
    server_port: u16,
    client: SnellClientConfig,
    outbound_bind_ip: Option<IpAddr>,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
}

impl SnellSessionFactory {
    pub fn new(
        server_host: String,
        server_port: u16,
        psk: String,
        user_key: String,
        outbound_bind_ip: Option<IpAddr>,
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    ) -> io::Result<Self> {
        let client = SnellClientConfig::new(psk, user_key)?;
        Ok(Self {
            config: Arc::new(SnellConfig { server_host, server_port, client, outbound_bind_ip, socket_protection }),
        })
    }
}

impl RelaySession for SnellSession {
    type Stream = SnellStream<TcpStream>;
    type Datagram = !;
    type Error = io::Error;

    /// # Cancel safety:
    /// cancel-safe: a drop at `resolve_host` leaves at most its detached lookup; a drop at
    /// `connect_first` closes the protected, unconnected fd; a drop at `ripdpi_snell::connect`
    /// closes the carrier after a possibly partial request, and no stream is returned.
    async fn open_stream(&self, target: &str) -> io::Result<Self::Stream> {
        let config = &self.config;
        let addrs = config.socket_protection.resolve_host(&config.server_host, config.server_port).await?;
        // VpnService.protect() invariant: connect_first protects each carrier fd before bind/connect.
        let carrier = connect_first(
            ordered_server_addrs(addrs, config.outbound_bind_ip),
            config.outbound_bind_ip,
            config.socket_protection,
        )
        .await?;
        carrier.set_nodelay(true)?;
        ripdpi_snell::connect(carrier, &config.client, target).await
    }

    /// # Cancel safety:
    /// cancel-safe: this future has no suspension point.
    async fn open_datagram(&self) -> io::Result<Self::Datagram> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "Snell relay does not support UDP ASSOCIATE"))
    }
}

impl RelaySessionFactory for SnellSessionFactory {
    type Session = SnellSession;
    type Error = io::Error;

    /// # Cancel safety:
    /// cancel-safe: no asynchronous resource is owned by the factory.
    async fn shutdown(&self) -> io::Result<()> {
        Ok(())
    }

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    /// # Cancel safety:
    /// cancel-safe: this future has no suspension point.
    async fn create_session(&self) -> io::Result<Arc<Self::Session>> {
        Ok(Arc::new(SnellSession { config: Arc::clone(&self.config) }))
    }
}
