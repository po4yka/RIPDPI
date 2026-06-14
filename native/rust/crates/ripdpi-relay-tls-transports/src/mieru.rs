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

/// One Mieru session over one (protected) TCP connection. Mieru is
/// non-reusable in this build (one relayed stream per session), so the client
/// is consumed by the first `open_stream`.
pub struct MieruSession {
    client: Mutex<Option<ripdpi_mieru::MieruClient>>,
}

impl RelaySession for MieruSession {
    type Stream = tokio::io::DuplexStream;
    type Datagram = ();
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        let client = self
            .client
            .lock()
            .await
            .take()
            .ok_or_else(|| io::Error::other("Mieru session already consumed (one stream per session)"))?;
        client.tcp_connect(target).await.map_err(to_io_error)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "Mieru relay does not support UDP ASSOCIATE"))
    }
}

impl RelaySessionFactory for MieruSessionFactory {
    type Session = MieruSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let config = self.config.clone();
        // The relay transport layer dials the carrier here with a bare
        // TcpStream::connect, the same posture as the sibling transports in this
        // crate (e.g. shadowsocks.rs). The VpnService.protect() invariant for
        // outbound relay sockets is owned by the relay layer, NOT this crate (see
        // .claude/rules/vpnservice-protect-invariant.md). TODO: confirm the
        // Mieru RelayKind is covered by the relay protect chain before shipping
        // live traffic.
        let stream = tokio::net::TcpStream::connect((config.server.as_str(), config.port)).await?;
        // Mieru's replay clock comes from the shared network-time provider, never
        // a direct device-clock read. Uncalibrated it falls back to the device
        // clock (first-contact residual; documented in ripdpi-network-time); once
        // any session calibrates the provider from a server's wire timestamp,
        // subsequent handshakes use network time even if the device clock is wrong.
        let time = NetworkTimeProvider::shared();
        let client = ripdpi_mieru::MieruClient::connect_over(stream, &config, time).await.map_err(to_io_error)?;
        Ok(Arc::new(MieruSession { client: Mutex::new(Some(client)) }))
    }
}
