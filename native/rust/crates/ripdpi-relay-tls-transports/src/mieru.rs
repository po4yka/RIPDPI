use std::io;
use std::sync::Arc;

use crate::session_registry::{OwnedSession, Registered, SessionRegistry};
use ripdpi_network_time::NetworkTimeProvider;
use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

use crate::util::to_io_error;

pub use ripdpi_mieru::{MieruConfig, MieruMux, MieruProtocol};

#[derive(Clone)]
pub struct MieruSessionFactory {
    config: ripdpi_mieru::MieruConfig,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    sessions: Arc<SessionRegistry<MieruCarrier>>,
}

impl MieruSessionFactory {
    pub fn new(config: MieruConfig, socket_protection: ripdpi_native_protect::SocketProtectionPolicy) -> Self {
        Self { config, socket_protection, sessions: Arc::default() }
    }
}

/// Every carrier owns its child tasks. Off uses a single slot and a non-reusable
/// pool entry, so independent Off requests never share a carrier.
/// A registry-owned carrier; callers hold it as [`MieruSession`].
pub struct MieruCarrier {
    connection: ripdpi_mieru::MieruMuxConnection,
}

/// Dropping the last handle aborts the carrier.
pub type MieruSession = Registered<MieruCarrier>;

impl OwnedSession for MieruCarrier {
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
                tokio::time::timeout(CARRIER_CONNECT_DEADLINE, async {
                    let started = tokio::time::Instant::now();
                    let config = self.config.clone();
                    // VpnService.protect() invariant: the Mieru carrier socket is protected
                    // before connect via the in-process VpnService.protect registry
                    // (loopback-skipped, fail-closed under a live TUN), matching the
                    // ripdpi-vless / ripdpi-xhttp gold-standard pattern. Mieru is a standalone
                    // relay kind (transport_descriptor.rs build_mieru), reachable under a live
                    // TUN; own-UID exclusion via computeAppRoutingPlan remains the second
                    // layer. (Resolves the prior unverified TODO.) REL-1 / REL-4. See
                    // .claude/rules/vpnservice-protect-invariant.md.
                    let addrs = self.socket_protection.resolve_host(&config.server, config.port).await?;
                    let stream = connect_first_reachable(
                        addrs,
                        self.socket_protection,
                        CARRIER_CONNECT_DEADLINE.saturating_sub(started.elapsed()),
                    )
                    .await?;
                    // Each segment is one write; Nagle would hold it for the
                    // previous segment's ACK (upstream Go dials with NODELAY).
                    stream.set_nodelay(true)?;
                    // Mieru's replay clock comes from the shared network-time provider.
                    // The provider is calibrated only from the first authenticated
                    // server segment, so calibration happens after a session already
                    // worked. It cannot rescue a first contact whose clock is outside
                    // the server's +-1 minute window: the server then drops the
                    // client silently, which surfaces as `NoAuthenticatedResponse`.
                    let time = NetworkTimeProvider::shared();
                    let connection = ripdpi_mieru::MieruMuxConnection::connect_over(stream, &config, time)
                        .await
                        .map_err(to_io_error)?;
                    Ok(MieruCarrier { connection })
                })
                .await
                .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "Mieru carrier connection timed out"))?
            })
            .await
    }
}

/// Total time the carrier connect may take, across every address.
const CARRIER_CONNECT_DEADLINE: std::time::Duration = std::time::Duration::from_secs(10);
/// Least time one address attempt gets while budget remains, so a long address
/// list cannot starve a reachable address of its handshake.
const MIN_ATTEMPT: std::time::Duration = std::time::Duration::from_secs(1);

/// Connect to the first reachable address of `addrs`, in order, with a fresh
/// socket per attempt. Each socket is protected before connect (see
/// `.claude/rules/vpnservice-protect-invariant.md`). Returns the last error when
/// every attempt fails.
///
/// # Cancel safety
/// Cancel-safe: the future owns the socket being connected; dropping it closes it.
async fn connect_first_reachable(
    addrs: Vec<std::net::SocketAddr>,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    budget: std::time::Duration,
) -> io::Result<tokio::net::TcpStream> {
    connect_in_order(addrs, budget, |server_addr| async move {
        let socket = match server_addr {
            std::net::SocketAddr::V4(_) => tokio::net::TcpSocket::new_v4()?,
            std::net::SocketAddr::V6(_) => tokio::net::TcpSocket::new_v6()?,
        };
        crate::protect::protect_carrier_socket(&socket, server_addr, socket_protection)?;
        socket.connect(server_addr).await
    })
    .await
}

/// Try `connect` on each address in order. An attempt gets the remaining budget
/// divided by the addresses left (never less than [`MIN_ATTEMPT`], never more
/// than the remainder), so a blackholed address cannot use the whole budget.
async fn connect_in_order<T, F, Fut>(
    addrs: Vec<std::net::SocketAddr>,
    budget: std::time::Duration,
    mut connect: F,
) -> io::Result<T>
where
    F: FnMut(std::net::SocketAddr) -> Fut,
    Fut: std::future::Future<Output = io::Result<T>>,
{
    let mut last_error = io::Error::new(io::ErrorKind::AddrNotAvailable, "no address resolved for mieru server");
    let started = tokio::time::Instant::now();
    let total = addrs.len();
    for (index, server_addr) in addrs.into_iter().enumerate() {
        let remaining = budget.saturating_sub(started.elapsed());
        let left = u32::try_from(total - index).unwrap_or(u32::MAX);
        let share = (remaining / left).max(MIN_ATTEMPT).min(remaining);
        match tokio::time::timeout(share, connect(server_addr)).await {
            Ok(Ok(stream)) => return Ok(stream),
            Ok(Err(error)) => last_error = error,
            Err(_) => last_error = io::Error::new(io::ErrorKind::TimedOut, "Mieru server address timed out"),
        }
    }
    Err(last_error)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn connect_first_reachable_skips_a_refused_address() {
        let refused = {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
            listener.local_addr().expect("addr")
        };
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("bind live");
        let live = listener.local_addr().expect("live addr");
        let policy = ripdpi_native_protect::SocketProtectionPolicy::Inactive;

        let stream = connect_first_reachable(vec![refused, live], policy, CARRIER_CONNECT_DEADLINE)
            .await
            .expect("second address is reachable");
        assert_eq!(stream.peer_addr().expect("peer"), live);

        let error = connect_first_reachable(vec![refused], policy, CARRIER_CONNECT_DEADLINE)
            .await
            .expect_err("all addresses refused");
        assert_eq!(error.kind(), io::ErrorKind::ConnectionRefused);
    }

    #[tokio::test]
    async fn blackholed_first_address_leaves_budget_for_the_next() {
        let blackhole: std::net::SocketAddr = "192.0.2.1:443".parse().expect("addr");
        let live: std::net::SocketAddr = "127.0.0.1:443".parse().expect("addr");
        let budget = std::time::Duration::from_secs(2);
        let started = tokio::time::Instant::now();
        let reached = connect_in_order(vec![blackhole, live], budget, |addr| async move {
            if addr == blackhole { std::future::pending::<io::Result<std::net::SocketAddr>>().await } else { Ok(addr) }
        })
        .await
        .expect("the second address must still be tried");
        assert_eq!(reached, live);
        assert!(started.elapsed() < budget, "the first attempt takes at most its share");
    }

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
