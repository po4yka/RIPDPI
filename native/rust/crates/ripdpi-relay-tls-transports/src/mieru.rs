use std::io;
use std::sync::Arc;

use crate::session_registry::{OwnedSession, SessionRegistry};
use ripdpi_network_time::NetworkTimeProvider;
use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

use crate::util::to_io_error;

pub use ripdpi_mieru::{MieruConfig, MieruMux, MieruProtocol};

#[derive(Clone)]
pub struct MieruSessionFactory {
    config: ripdpi_mieru::MieruConfig,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    sessions: Arc<SessionRegistry<MieruSession>>,
}

impl MieruSessionFactory {
    pub fn new(config: MieruConfig, socket_protection: ripdpi_native_protect::SocketProtectionPolicy) -> Self {
        Self { config, socket_protection, sessions: Arc::default() }
    }
}

/// Every carrier owns its child tasks. Off uses a single slot and a non-reusable
/// pool entry, so independent Off requests never share a carrier.
pub struct MieruSession {
    connection: ripdpi_mieru::MieruMuxConnection,
}

impl OwnedSession for MieruSession {
    fn abort(&self) {
        self.connection.cancel();
    }

    async fn close(&self) -> io::Result<()> {
        self.connection.close().await.map_err(to_io_error)
    }
}

impl RelaySession for MieruSession {
    type Stream = ripdpi_mieru::MieruStream;
    type Datagram = ();
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        self.connection.open_stream(target).await.map_err(to_io_error)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "Mieru relay does not support UDP ASSOCIATE"))
    }
}

impl RelaySessionFactory for MieruSessionFactory {
    async fn shutdown(&self) -> Result<(), Self::Error> {
        self.sessions.shutdown().await
    }

    type Session = MieruSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        // Multiplexed levels reuse one carrier across many relayed streams; `off`
        // keeps the one-stream-per-carrier posture.
        RelayCapabilities { tcp: true, udp: false, reusable: self.config.multiplexing.is_multiplexed() }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        self.config.validate().map_err(to_io_error)?;
        if self.config.protocol == MieruProtocol::Udp {
            return Err(io::Error::new(io::ErrorKind::Unsupported, "Mieru UDP carrier is not supported"));
        }
        self.sessions
            .create(async {
                tokio::time::timeout(std::time::Duration::from_secs(10), async {
                    let config = self.config.clone();
                    // VpnService.protect() invariant: the Mieru carrier socket is protected
                    // before connect via the in-process VpnService.protect registry
                    // (loopback-skipped, fail-closed under a live TUN), matching the
                    // ripdpi-vless / ripdpi-xhttp gold-standard pattern. Mieru is a standalone
                    // relay kind (transport_descriptor.rs build_mieru), reachable under a live
                    // TUN; own-UID exclusion via computeAppRoutingPlan remains the second
                    // layer. (Resolves the prior unverified TODO.) REL-1 / REL-4. See
                    // .claude/rules/vpnservice-protect-invariant.md.
                    let server_addr = self
                        .socket_protection
                        .resolve_host(&config.server, config.port)
                        .await?
                        .into_iter()
                        .next()
                        .ok_or_else(|| {
                            std::io::Error::new(
                                std::io::ErrorKind::AddrNotAvailable,
                                "no address resolved for mieru server",
                            )
                        })?;
                    let socket = match server_addr {
                        std::net::SocketAddr::V4(_) => tokio::net::TcpSocket::new_v4()?,
                        std::net::SocketAddr::V6(_) => tokio::net::TcpSocket::new_v6()?,
                    };
                    crate::protect::protect_carrier_socket(&socket, server_addr, self.socket_protection)?;
                    let stream = socket.connect(server_addr).await?;
                    // Mieru's replay clock comes from the shared network-time provider, never
                    // a direct device-clock read. Uncalibrated it falls back to the device
                    // clock (first-contact residual; documented in ripdpi-network-time); once
                    // any session calibrates the provider from a server's wire timestamp,
                    // subsequent handshakes use network time even if the device clock is wrong.
                    let time = NetworkTimeProvider::shared();
                    let connection = ripdpi_mieru::MieruMuxConnection::connect_over(stream, &config, time)
                        .await
                        .map_err(to_io_error)?;
                    Ok(MieruSession { connection })
                })
                .await
                .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "Mieru carrier connection timed out"))?
            })
            .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn unsupported_udp_carrier_is_rejected_before_socket_connect() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("carrier listener");
        listener.set_nonblocking(true).expect("nonblocking listener");
        let address = listener.local_addr().expect("listener address");
        let factory = MieruSessionFactory::new(
            MieruConfig {
                server: address.ip().to_string(),
                port: address.port(),
                username: "outbound-interop".into(),
                password: "loopback-test-password".into(),
                protocol: MieruProtocol::Udp,
                multiplexing: MieruMux::Off,
                mtu: 1400,
            },
            ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        );
        let result = factory.create_session().await;
        let accepted = listener.accept();
        factory.shutdown().await.expect("shutdown");
        assert!(result.is_err(), "unsupported carrier must fail");
        assert!(
            matches!(accepted, Err(error) if error.kind() == io::ErrorKind::WouldBlock),
            "unsupported carrier must not make a TCP connection first"
        );
    }
}
