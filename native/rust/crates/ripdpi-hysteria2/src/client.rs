use std::collections::HashMap;
use std::net::ToSocketAddrs;
use std::sync::Arc;
use std::sync::atomic::AtomicU32;

use tokio::sync::{Mutex, mpsc};
use tokio::task::JoinHandle;

use crate::config::Config;
use crate::error::{HysteriaError, Result};
use crate::migration::QuicMigrationState;
use crate::tcp::{DuplexStream, build_tcp_request, read_tcp_response};
use crate::tls_quic::{ClientSocketSpec, authenticate_connection, build_endpoint, build_tls_config};
use crate::udp::{UdpPacket, UdpSession, dispatch_udp_datagrams};

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

pub async fn connect(config: &Config) -> Result<HysteriaClient> {
    if config.insecure {
        tracing::warn!("hysteria2 session starting with certificate verification DISABLED (insecure=true profile)");
    }

    let server_addr = config
        .server_addr
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| HysteriaError::InvalidAddress(config.server_addr.clone()))?;

    let tls_config = build_tls_config(config)?;
    let socket_spec = ClientSocketSpec {
        ipv6: server_addr.is_ipv6(),
        bind_low_port: config.quic_bind_low_port,
        salamander_key: config.salamander_key.clone(),
    };
    let (endpoint, current_socket) = build_endpoint(config, tls_config, socket_spec.clone())?;
    let connection = endpoint.connect(server_addr, &config.server_name)?.await?;

    let udp_supported = authenticate_connection(config, &connection).await?;
    let max_datagram_size = connection.max_datagram_size();

    let inner = Arc::new(ClientInner {
        endpoint,
        connection,
        next_session_id: AtomicU32::new(1),
        registrations: Mutex::new(HashMap::new()),
        udp_supported,
        max_datagram_size,
        socket_spec,
        migrate_after_handshake: config.quic_migrate_after_handshake,
        current_socket: Mutex::new(current_socket),
        migration: Mutex::new(QuicMigrationState::new_not_attempted()),
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
        let migrated = self.inner.begin_quic_migration().await?;
        match self.open_tcp_stream(address).await {
            Ok(stream) => {
                if migrated {
                    self.inner.complete_quic_migration("path_validated_after_stream_open").await;
                }
                Ok(stream)
            }
            Err(_error) if migrated => {
                let _ = self.inner.rollback_quic_migration("stream_open_failed_after_rebind").await;
                self.open_tcp_stream(address).await
            }
            Err(error) => Err(error),
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

        Ok(DuplexStream::new(send, recv))
    }
}

pub(crate) struct ClientInner {
    pub(crate) endpoint: quinn::Endpoint,
    pub(crate) connection: quinn::Connection,
    pub(crate) next_session_id: AtomicU32,
    pub(crate) registrations: Mutex<HashMap<u32, mpsc::Sender<UdpPacket>>>,
    pub(crate) udp_supported: bool,
    pub(crate) max_datagram_size: Option<usize>,
    pub(crate) socket_spec: ClientSocketSpec,
    pub(crate) migrate_after_handshake: bool,
    pub(crate) current_socket: Mutex<std::net::UdpSocket>,
    pub(crate) migration: Mutex<QuicMigrationState>,
}
