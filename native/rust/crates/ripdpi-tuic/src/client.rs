use std::collections::HashMap;
use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicU16, AtomicUsize, Ordering};
use std::time::Duration;

use quinn::Endpoint;
use rustls::ClientConfig as RustlsClientConfig;
use rustls::pki_types::CertificateDer;
use rustls::pki_types::pem::PemObject;
use tokio::sync::Mutex;
use tokio::task::JoinHandle;

use crate::config::Config;
use crate::endpoint::{
    ClientSocketSpec, build_endpoint, build_tls_config, establish_connection, resolve_server_addr, validate_config,
};
use crate::migration::QuicMigration;
use crate::protocol::{HEARTBEAT_DATAGRAM, TuicAddress, authenticate_connection, classify_handshake_failure};
use crate::tcp::{DuplexStream, encode_connect_header};
use crate::udp::{UdpPacket, UdpSession, dispatch_incoming_datagrams};

/// Aborts the contained task when the last owner is dropped.
///
/// Wrapped in `Arc` so `TuicClient` (which is `Clone`) shares ownership; the
/// task is aborted exactly once when the last clone drops.
pub(crate) struct AbortOnDrop(JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        // NEVER panic in Drop.
        self.0.abort();
    }
}

/// TUIC v5 Heartbeat period while relay tasks are active. The reference
/// client sends one every 3 s; tuic-server closes a connection after 10 s of
/// silence by default.
const HEARTBEAT_INTERVAL: Duration = if cfg!(test) { Duration::from_millis(200) } else { Duration::from_secs(3) };

#[derive(Clone)]
pub struct TuicClient {
    inner: Arc<ClientInner>,
}

impl TuicClient {
    pub async fn connect(config: Config) -> io::Result<Self> {
        let additional_roots = additional_roots_from_pem(config.root_certificate_pem.as_deref())?;
        let tls_config = build_tls_config(config.zero_rtt, additional_roots)?;
        Self::connect_with_tls(config, tls_config).await
    }

    pub(crate) async fn connect_with_tls(config: Config, tls_config: RustlsClientConfig) -> io::Result<Self> {
        validate_config(&config)?;
        let server_addr =
            resolve_server_addr(&config.server, config.server_port, config.outbound_bind_ip, config.socket_protection)
                .await?;
        let socket_spec = ClientSocketSpec {
            ipv6: server_addr.is_ipv6(),
            bind_low_port: config.quic_bind_low_port,
            socket_protection: config.socket_protection,
            outbound_bind_ip: config.outbound_bind_ip,
        };
        let (endpoint, current_socket) = build_endpoint(&config, tls_config, socket_spec)?;
        let connection = establish_connection(&endpoint, &config, server_addr).await?;
        // A v4 server completes QUIC/TLS but rejects this client's v5 auth frame,
        // surfacing as an application-close. Classify it so the relay layer can
        // report `TuicVersionUnsupported` instead of a generic protocol error.
        authenticate_connection(&connection, &config)
            .await
            .map_err(|error| classify_handshake_failure(&connection, error))?;
        let max_datagram_size = connection.max_datagram_size();

        let inner = Arc::new(ClientInner {
            dispatch_task: std::sync::OnceLock::new(),
            endpoint,
            connection,
            udp_enabled: config.udp_enabled,
            next_assoc_id: AtomicU16::new(1),
            registrations: Mutex::new(HashMap::new()),
            max_datagram_size,
            socket_spec,
            migrate_after_handshake: config.quic_migrate_after_handshake,
            migration: QuicMigration::new_not_attempted(current_socket),
            active_tasks: AtomicUsize::new(0),
        });
        if config.udp_enabled && max_datagram_size.is_some() {
            // The dispatcher holds a weak handle and `ClientInner` owns its
            // abort guard, so it runs while the client or any UDP session is
            // open, and stops when the last of them drops.
            let task = tokio::spawn(dispatch_incoming_datagrams(inner.connection.clone(), Arc::downgrade(&inner)));
            let _ = inner.dispatch_task.set(AbortOnDrop(task));
        }

        if max_datagram_size.is_some() {
            // Detached on purpose: open streams and UDP sessions keep
            // `ClientInner` alive after the last `TuicClient` drops, and they
            // still need heartbeats. The task ends with `ClientInner`.
            tokio::spawn(send_heartbeats(Arc::downgrade(&inner)));
        }

        Ok(Self { inner })
    }

    pub async fn tcp_connect(&self, authority: &str) -> io::Result<DuplexStream> {
        let target = TuicAddress::from_authority(authority)?;
        let migration = self.inner.begin_quic_migration()?;
        match self.open_tcp_stream(&target).await {
            Ok(stream) => {
                if let Some(migration) = migration {
                    migration.complete("path_validated_after_stream_open");
                }
                Ok(stream)
            }
            Err(error) => match migration {
                Some(migration) => {
                    migration.rollback("stream_open_failed_after_rebind")?;
                    self.open_tcp_stream(&target).await
                }
                None => Err(error),
            },
        }
    }

    pub async fn udp_session(&self) -> io::Result<UdpSession> {
        if !self.inner.udp_enabled {
            return Err(io::Error::new(io::ErrorKind::Unsupported, "TUIC UDP relay is disabled by configuration"));
        }
        if self.inner.max_datagram_size.is_none() {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "TUIC datagram relay is not available on this connection",
            ));
        }

        Ok(UdpSession::new(Arc::clone(&self.inner)))
    }

    pub fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.inner.quic_migration_snapshot()
    }

    async fn open_tcp_stream(&self, target: &TuicAddress) -> io::Result<DuplexStream> {
        // Classify a relay-handshake failure: a v4 server rejects the v5 Connect
        // command by application-closing the connection, which `open_bi` /
        // `encode_connect_header` surface as an error here.
        self.open_tcp_stream_inner(target)
            .await
            .map_err(|error| classify_handshake_failure(&self.inner.connection, error))
    }

    async fn open_tcp_stream_inner(&self, target: &TuicAddress) -> io::Result<DuplexStream> {
        let (mut send, recv) = self.inner.connection.open_bi().await?;
        encode_connect_header(&mut send, target).await?;
        Ok(DuplexStream { send, recv, _active: ActiveTask::new(Arc::clone(&self.inner)) })
    }
}

/// Parse the optional PEM trust anchor into DER certificates for
/// [`build_tls_config`]'s `additional_roots`. Pins a self-signed / private-CA
/// server cert; verification stays ON.
fn additional_roots_from_pem(pem: Option<&str>) -> io::Result<Option<Vec<CertificateDer<'static>>>> {
    let Some(pem) = pem.filter(|value| !value.trim().is_empty()) else {
        return Ok(None);
    };
    let certificates =
        CertificateDer::pem_slice_iter(pem.as_bytes()).collect::<Result<Vec<_>, _>>().map_err(|error| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid TUIC root certificate PEM: {error}"))
        })?;
    if certificates.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "TUIC root certificate PEM contained no certificates"));
    }
    Ok(Some(certificates))
}

pub(crate) struct ClientInner {
    pub(crate) endpoint: Endpoint,
    pub(crate) connection: quinn::Connection,
    pub(crate) udp_enabled: bool,
    pub(crate) next_assoc_id: AtomicU16,
    pub(crate) registrations: Mutex<HashMap<u16, tokio::sync::mpsc::Sender<UdpPacket>>>,
    pub(crate) max_datagram_size: Option<usize>,
    pub(crate) socket_spec: ClientSocketSpec,
    pub(crate) migrate_after_handshake: bool,
    pub(crate) migration: QuicMigration,
    /// Open TCP relays and UDP sessions; heartbeats run only while non-zero.
    pub(crate) active_tasks: AtomicUsize,
    /// Aborts the datagram dispatcher when the last client or session drops.
    /// Empty when UDP is disabled or the connection has no datagrams.
    pub(crate) dispatch_task: std::sync::OnceLock<AbortOnDrop>,
}

/// Counts one relay task (a TCP stream or a UDP session) for the heartbeat.
pub(crate) struct ActiveTask(Arc<ClientInner>);

impl ActiveTask {
    pub(crate) fn new(inner: Arc<ClientInner>) -> Self {
        // Ordering: Relaxed — a standalone counter read only as a heartbeat
        // on/off hint; no other memory is published through it.
        inner.active_tasks.fetch_add(1, Ordering::Relaxed);
        Self(inner)
    }
}

impl Drop for ActiveTask {
    fn drop(&mut self) {
        // Ordering: Relaxed — see `ActiveTask::new`.
        self.0.active_tasks.fetch_sub(1, Ordering::Relaxed);
    }
}

/// Sends a TUIC Heartbeat datagram every [`HEARTBEAT_INTERVAL`] while a relay
/// task is active, so the server's idle timeout and NAT bindings do not
/// close an established but quiet flow. An idle carrier stays silent.
///
/// # Cancel safety
///
/// Cancel-safe: it holds only a weak connection handle and returns once the
/// client, its streams and its UDP sessions are all gone.
async fn send_heartbeats(inner: std::sync::Weak<ClientInner>) {
    let mut ticker = tokio::time::interval_at(tokio::time::Instant::now() + HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        ticker.tick().await;
        let Some(inner) = inner.upgrade() else {
            return;
        };
        // Ordering: Relaxed — see `ActiveTask::new`.
        if inner.active_tasks.load(Ordering::Relaxed) == 0 {
            continue;
        }
        // Every send error (closed connection, datagrams disabled) is terminal.
        if inner.connection.send_datagram(bytes::Bytes::from_static(&HEARTBEAT_DATAGRAM)).is_err() {
            return;
        }
    }
}
