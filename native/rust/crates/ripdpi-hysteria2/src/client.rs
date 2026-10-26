use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::AtomicU32;
use std::time::Duration;

use tokio::sync::{Mutex, mpsc};
use tokio::task::JoinHandle;

use crate::config::Config;
use crate::error::{HysteriaError, Result};
use crate::hopping_socket::{HoppingSocket, StopHoppingOnDrop};
use crate::migration::QuicMigration;
use crate::tcp::{DuplexStream, build_tcp_request, read_tcp_response};
use crate::tls_quic::{ClientSocketSpec, H3ConnectionGuard, authenticate_connection, build_endpoint, build_tls_config};
use crate::udp::{UdpPacket, UdpSession, dispatch_udp_datagrams};

const ANDROID_UDP_AUTH_SETTLE_GRACE: Duration = Duration::from_millis(100);

/// Aborts the contained task when the last owner is dropped.
///
/// Wrapped in `Arc` so `HysteriaClient` (which is `Clone`) shares ownership; the
/// task is aborted exactly once when the last clone drops.
pub(crate) struct AbortOnDrop(JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        // NEVER panic in Drop.
        self.0.abort();
    }
}

/// # Cancel safety:
/// cancel-safe: DNS and handshake futures own their partial state. The hopping
/// guard stops its timer and closes both paths if connect or H3 auth is cancelled.
pub async fn connect(config: &Config) -> Result<HysteriaClient> {
    if let Some(policy) = &config.port_hopping {
        policy.validate_deadline()?;
    }
    if config.insecure && config.pin_sha256.is_none() {
        tracing::warn!("hysteria2 session starting with certificate verification DISABLED (insecure=true profile)");
    }

    let server_addr = config
        .socket_protection
        .resolve_authority(&config.server_addr)
        .await?
        .into_iter()
        .next()
        .ok_or_else(|| HysteriaError::InvalidAddress(config.server_addr.clone()))?;

    let tls_config = build_tls_config(config)?;
    let socket_spec = ClientSocketSpec {
        ipv6: server_addr.is_ipv6(),
        bind_low_port: config.quic_bind_low_port,
        salamander_key: config.salamander_key.clone(),
        socket_protection: config.socket_protection,
    };
    let (endpoint, current_socket, hopping_socket) =
        build_endpoint(config, tls_config, socket_spec.clone(), server_addr)?;
    let hopping_stop = StopHoppingOnDrop::new(hopping_socket.clone());
    let connection = endpoint.connect(server_addr, &config.server_name)?.await?;

    let (udp_supported, h3_guard) = authenticate_connection(config, &connection).await?;
    let max_datagram_size = connection.max_datagram_size();
    let udp_ready_at = tokio::time::Instant::now() + udp_auth_settle_grace(cfg!(target_os = "android"));

    let inner = Arc::new(ClientInner {
        _hopping_stop: hopping_stop,
        endpoint,
        connection,
        _h3_guard: h3_guard,
        next_session_id: AtomicU32::new(1),
        registrations: Mutex::new(HashMap::new()),
        udp_supported,
        max_datagram_size,
        udp_ready_at,
        socket_spec,
        migrate_after_handshake: config.quic_migrate_after_handshake,
        migration: QuicMigration::new_not_attempted(current_socket),
        hopping_socket,
    });

    let dispatch_guard = if udp_supported {
        let handle = tokio::spawn(dispatch_udp_datagrams(Arc::clone(&inner)));
        Some(Arc::new(AbortOnDrop(handle)))
    } else {
        None
    };

    Ok(HysteriaClient { inner, _dispatch_guard: dispatch_guard })
}

#[derive(Clone)]
pub struct HysteriaClient {
    inner: Arc<ClientInner>,
    /// Keeps the datagram-dispatch task alive and aborts it when the last
    /// `HysteriaClient` clone is dropped. `None` when UDP is not supported.
    _dispatch_guard: Option<Arc<AbortOnDrop>>,
}

impl HysteriaClient {
    pub async fn tcp_connect(&self, address: &str) -> Result<DuplexStream> {
        let migration = self.inner.begin_quic_migration();
        match self.open_tcp_stream(address).await {
            Ok(stream) => {
                if let Some(migration) = migration {
                    migration.complete("path_validated_after_stream_open");
                }
                Ok(stream)
            }
            Err(error) => match migration {
                Some(migration) if error.is_path_failure() => {
                    migration.rollback("stream_open_failed_after_rebind")?;
                    self.open_tcp_stream(address).await
                }
                Some(migration) => {
                    // The server answered on the new path; only the target failed.
                    migration.complete("server_responded_after_rebind");
                    Err(error)
                }
                None => Err(error),
            },
        }
    }

    pub fn udp_supported(&self) -> bool {
        self.inner.udp_supported
    }

    pub async fn udp_session(&self) -> Result<UdpSession> {
        if !self.inner.udp_supported || self.inner.max_datagram_size.is_none() {
            return Err(HysteriaError::UdpNotSupported);
        }

        Ok(UdpSession::new(Arc::clone(&self.inner)))
    }

    pub fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.inner.quic_migration_snapshot()
    }

    async fn open_tcp_stream(&self, address: &str) -> Result<DuplexStream> {
        let (mut send, mut recv) = self.inner.connection.open_bi().await?;
        send.write_all(&build_tcp_request(address, 0)).await?;

        let (status_ok, message) = read_tcp_response(&mut recv).await?;
        if !status_ok {
            return Err(HysteriaError::TcpConnect(message));
        }

        Ok(DuplexStream::new(send, recv, self.inner.hopping_socket.as_ref().map(|_| Arc::clone(&self.inner))))
    }
}

pub(crate) struct ClientInner {
    // Drop the schedule and carrier sockets before the rest of client state.
    _hopping_stop: StopHoppingOnDrop,
    pub(crate) endpoint: quinn::Endpoint,
    pub(crate) connection: quinn::Connection,
    _h3_guard: H3ConnectionGuard,
    pub(crate) next_session_id: AtomicU32,
    pub(crate) registrations: Mutex<HashMap<u32, mpsc::Sender<UdpPacket>>>,
    pub(crate) udp_supported: bool,
    pub(crate) max_datagram_size: Option<usize>,
    pub(crate) udp_ready_at: tokio::time::Instant,
    pub(crate) socket_spec: ClientSocketSpec,
    pub(crate) migrate_after_handshake: bool,
    pub(crate) migration: QuicMigration,
    pub(crate) hopping_socket: Option<Arc<HoppingSocket>>,
}

impl Drop for ClientInner {
    fn drop(&mut self) {
        // Close QUIC before releasing its carrier. Fields then drop normally;
        // no detached task or endpoint driver can keep the hopping timer alive.
        if self.hopping_socket.is_some() {
            self.connection.close(0u32.into(), b"client closed");
            self.endpoint.close(0u32.into(), b"client closed");
        }
    }
}

fn udp_auth_settle_grace(is_android: bool) -> Duration {
    if is_android { ANDROID_UDP_AUTH_SETTLE_GRACE } else { Duration::ZERO }
}

#[cfg(test)]
mod tests {
    use super::{ANDROID_UDP_AUTH_SETTLE_GRACE, udp_auth_settle_grace};
    use crate::test_server::{ServerEvent, ServerOptions, TestServer};

    /// A quiet session must keep sending PINGs, or the QUIC idle timeout and
    /// mobile UDP NAT expiry kill every multiplexed stream.
    #[tokio::test]
    async fn idle_session_sends_keep_alive_pings() {
        let server = TestServer::start(ServerOptions::default()).expect("test server");
        let client = crate::connect(&server.client_config()).await.expect("connect");
        // Let handshake-time probes settle so only keep-alive PINGs count.
        tokio::time::sleep(std::time::Duration::from_secs(1)).await;
        let pings_before = client.inner.connection.stats().frame_tx.ping;

        tokio::time::sleep(std::time::Duration::from_secs(11)).await;

        assert!(
            client.inner.connection.stats().frame_tx.ping > pings_before,
            "no keep-alive PING in 11 s of idle after the handshake settled"
        );
    }

    /// The client's stream receive window bounds how much the server may send
    /// before the client reads. With quinn's 1.25 MB default the echoed bytes
    /// back up into the server and stall this upload long before 6 MiB.
    #[tokio::test]
    async fn stream_receive_window_uses_the_reference_size() {
        use tokio::io::AsyncWriteExt;

        let server = TestServer::start(ServerOptions::default()).expect("test server");
        let client = crate::connect(&server.client_config()).await.expect("connect");
        let mut stream = client.tcp_connect("192.0.2.1:80").await.expect("TCP stream");
        let upload = vec![0x5a; 6 * 1024 * 1024];

        let written = tokio::time::timeout(std::time::Duration::from_secs(10), stream.write_all(&upload)).await;

        assert!(written.is_ok(), "the upload stalled: the client stream receive window is too small");
    }

    /// A server that answers on the new path proves the path works; its
    /// refusal must neither roll the shared endpoint back nor send the
    /// CONNECT twice.
    #[tokio::test]
    async fn refused_tcp_connect_after_rebind_keeps_the_migration_and_is_sent_once() {
        let mut server =
            TestServer::start(ServerOptions { refuse_tcp: true, ..ServerOptions::default() }).expect("test server");
        let mut config = server.client_config();
        config.quic_migrate_after_handshake = true;
        let client = crate::connect(&config).await.expect("connect");

        let error = client.tcp_connect("192.0.2.1:80").await.err().expect("the server refuses the target");

        assert!(matches!(error, crate::HysteriaError::TcpConnect(_)), "unexpected error: {error}");
        assert_eq!(client.quic_migration_snapshot().0.as_deref(), Some("validated"));
        assert_eq!(server.events.recv().await, Some(ServerEvent::TcpRequest("192.0.2.1:80".into())));
        assert!(server.events.try_recv().is_err(), "the refused CONNECT was sent again");
    }

    #[test]
    fn android_udp_waits_for_server_auth_handoff_before_first_datagram() {
        assert_eq!(udp_auth_settle_grace(true), ANDROID_UDP_AUTH_SETTLE_GRACE);
        assert_eq!(udp_auth_settle_grace(false), std::time::Duration::ZERO);
    }
}
