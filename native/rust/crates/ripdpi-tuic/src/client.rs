use std::collections::HashMap;
use std::io;
use std::sync::Arc;
use std::sync::atomic::AtomicU16;

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
use crate::protocol::{TuicAddress, authenticate_connection, classify_handshake_failure};
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

#[derive(Clone)]
pub struct TuicClient {
    inner: Arc<ClientInner>,
    /// Keeps the datagram-dispatch task alive and aborts it when the last
    /// `TuicClient` clone is dropped. `None` when UDP was disabled at connect time.
    _dispatch_guard: Option<Arc<AbortOnDrop>>,
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
            endpoint,
            connection,
            udp_enabled: config.udp_enabled,
            next_assoc_id: AtomicU16::new(1),
            registrations: Mutex::new(HashMap::new()),
            max_datagram_size,
            socket_spec,
            migrate_after_handshake: config.quic_migrate_after_handshake,
            migration: QuicMigration::new_not_attempted(current_socket),
        });

        let dispatch_guard = if config.udp_enabled && max_datagram_size.is_some() {
            let handle = tokio::spawn(dispatch_incoming_datagrams(Arc::clone(&inner)));
            Some(Arc::new(AbortOnDrop(handle)))
        } else {
            None
        };

        Ok(Self { inner, _dispatch_guard: dispatch_guard })
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
        Ok(DuplexStream { send, recv })
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
}
