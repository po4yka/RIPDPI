use std::io;
use std::sync::Arc;

use ripdpi_network_time::NetworkTimeProvider;
use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};
use tokio::sync::Mutex;

use crate::util::to_io_error;

pub use ripdpi_mieru::{MieruConfig, MieruMux, MieruProtocol};

#[derive(Clone)]
pub struct MieruSessionFactory {
    pub config: ripdpi_mieru::MieruConfig,
}

/// A Mieru relay session. With multiplexing `off` it is one client over one
/// (protected) TCP carrier, consumed by the first `open_stream` (non-reusable).
/// With multiplexing `low`/`middle`/`high` it is a reusable carrier that the
/// relay pool drives with many `open_stream` calls, each a sub-session
/// multiplexed onto the shared connection.
pub struct MieruSession {
    inner: MieruSessionInner,
}

enum MieruSessionInner {
    OneShot(Box<Mutex<Option<ripdpi_mieru::MieruClient>>>),
    Mux(ripdpi_mieru::MieruMuxConnection),
}

impl RelaySession for MieruSession {
    type Stream = tokio::io::DuplexStream;
    type Datagram = ();
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        match &self.inner {
            MieruSessionInner::OneShot(client) => {
                let client = client
                    .lock()
                    .await
                    .take()
                    .ok_or_else(|| io::Error::other("Mieru session already consumed (one stream per session)"))?;
                client.tcp_connect(target).await.map_err(to_io_error)
            }
            MieruSessionInner::Mux(conn) => conn.open_stream(target).await.map_err(to_io_error),
        }
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "Mieru relay does not support UDP ASSOCIATE"))
    }
}

impl RelaySessionFactory for MieruSessionFactory {
    type Session = MieruSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        // Multiplexed levels reuse one carrier across many relayed streams; `off`
        // keeps the one-stream-per-carrier posture.
        RelayCapabilities { tcp: true, udp: false, reusable: self.config.multiplexing.is_multiplexed() }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let config = self.config.clone();
        // VpnService.protect() invariant: the Mieru carrier socket is protected
        // before connect via the in-process VpnService.protect registry
        // (loopback-skipped, fail-closed under a live TUN), matching the
        // ripdpi-vless / ripdpi-xhttp gold-standard pattern. Mieru is a standalone
        // relay kind (transport_descriptor.rs build_mieru), reachable under a live
        // TUN; own-UID exclusion via computeAppRoutingPlan remains the second
        // layer. (Resolves the prior unverified TODO.) REL-1 / REL-4. See
        // .claude/rules/vpnservice-protect-invariant.md.
        let mut addrs = tokio::net::lookup_host((config.server.as_str(), config.port)).await?;
        let server_addr = addrs.next().ok_or_else(|| {
            std::io::Error::new(std::io::ErrorKind::AddrNotAvailable, "no address resolved for mieru server")
        })?;
        let socket = match server_addr {
            std::net::SocketAddr::V4(_) => tokio::net::TcpSocket::new_v4()?,
            std::net::SocketAddr::V6(_) => tokio::net::TcpSocket::new_v6()?,
        };
        crate::protect::protect_carrier_socket(&socket, server_addr)?;
        let stream = socket.connect(server_addr).await?;
        // Mieru's replay clock comes from the shared network-time provider, never
        // a direct device-clock read. Uncalibrated it falls back to the device
        // clock (first-contact residual; documented in ripdpi-network-time); once
        // any session calibrates the provider from a server's wire timestamp,
        // subsequent handshakes use network time even if the device clock is wrong.
        let time = NetworkTimeProvider::shared();
        let inner = if config.multiplexing.is_multiplexed() {
            let conn =
                ripdpi_mieru::MieruMuxConnection::connect_over(stream, &config, time).await.map_err(to_io_error)?;
            MieruSessionInner::Mux(conn)
        } else {
            let client = ripdpi_mieru::MieruClient::connect_over(stream, &config, time).await.map_err(to_io_error)?;
            MieruSessionInner::OneShot(Box::new(Mutex::new(Some(client))))
        };
        Ok(Arc::new(MieruSession { inner }))
    }
}
