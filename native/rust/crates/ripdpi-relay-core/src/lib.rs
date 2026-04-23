mod runtime_validation;
mod socks5;

use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use ripdpi_relay_mux::{
    BoxFuture, MuxLease, RelayCapabilities, RelayMux, RelayPoolConfig, RelayPoolHealth, RelaySession,
    RelaySessionFactory,
};
use ripdpi_tls_profiles::profile_catalog_version;
use serde::{Deserialize, Serialize};
use tokio::io::{copy_bidirectional, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::time::timeout;

use crate::runtime_validation::{
    describe_runtime_health, describe_upstream, parse_outbound_bind_ip, planned_backend_capabilities,
    planned_backend_fallback_mode, pool_config_for_backend, validate_runtime_config,
};
use crate::socks5::{decode_udp_frame, encode_udp_frame, read_target, write_reply, RelayTargetAddr};

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);
const UDP_BUFFER_SIZE: usize = 65_536;

fn emit_runtime_ready(bind_addr: &str) {
    tracing::info!(
        ring = "relay",
        subsystem = "relay",
        source = "relay",
        kind = "runtime_ready",
        "listener started addr={bind_addr}"
    );
}

fn emit_runtime_stopped() {
    tracing::info!(ring = "relay", subsystem = "relay", source = "relay", kind = "runtime_stopped", "listener stopped");
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedRelayFinalmaskConfig {
    #[serde(default)]
    pub r#type: String,
    #[serde(default)]
    pub header_hex: String,
    #[serde(default)]
    pub trailer_hex: String,
    #[serde(default)]
    pub rand_range: String,
    #[serde(default)]
    pub sudoku_seed: String,
    #[serde(default)]
    pub fragment_packets: i32,
    #[serde(default)]
    pub fragment_min_bytes: i32,
    #[serde(default)]
    pub fragment_max_bytes: i32,
}

impl Default for ResolvedRelayFinalmaskConfig {
    fn default() -> Self {
        Self {
            r#type: "off".to_string(),
            header_hex: String::new(),
            trailer_hex: String::new(),
            rand_range: String::new(),
            sudoku_seed: String::new(),
            fragment_packets: 0,
            fragment_min_bytes: 0,
            fragment_max_bytes: 0,
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedRelayRuntimeConfig {
    pub enabled: bool,
    pub kind: String,
    pub profile_id: String,
    pub outbound_bind_ip: String,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub reality_public_key: String,
    pub reality_short_id: String,
    pub vless_transport: String,
    pub xhttp_path: String,
    pub xhttp_host: String,
    #[serde(default)]
    pub cloudflare_tunnel_mode: String,
    #[serde(default)]
    pub cloudflare_publish_local_origin_url: String,
    #[serde(default)]
    pub cloudflare_credentials_ref: String,
    pub chain_entry_server: String,
    pub chain_entry_port: i32,
    pub chain_entry_server_name: String,
    pub chain_entry_public_key: String,
    pub chain_entry_short_id: String,
    pub chain_entry_profile_id: String,
    pub chain_exit_server: String,
    pub chain_exit_port: i32,
    pub chain_exit_server_name: String,
    pub chain_exit_public_key: String,
    pub chain_exit_short_id: String,
    pub chain_exit_profile_id: String,
    pub masque_url: String,
    pub masque_use_http2_fallback: bool,
    pub masque_cloudflare_geohash_enabled: bool,
    pub tuic_zero_rtt: bool,
    pub tuic_congestion_control: String,
    pub shadow_tls_inner_profile_id: String,
    pub shadow_tls_inner: Option<ResolvedShadowTlsInnerRelayConfig>,
    pub naive_path: String,
    pub local_socks_host: String,
    pub local_socks_port: i32,
    pub udp_enabled: bool,
    pub tcp_fallback_enabled: bool,
    pub quic_bind_low_port: bool,
    pub quic_migrate_after_handshake: bool,
    pub vless_uuid: Option<String>,
    pub chain_entry_uuid: Option<String>,
    pub chain_exit_uuid: Option<String>,
    pub hysteria_password: Option<String>,
    pub hysteria_salamander_key: Option<String>,
    pub tuic_uuid: Option<String>,
    pub tuic_password: Option<String>,
    pub shadow_tls_password: Option<String>,
    pub naive_username: Option<String>,
    pub naive_password: Option<String>,
    pub tls_fingerprint_profile: String,
    pub masque_auth_mode: Option<String>,
    pub masque_auth_token: Option<String>,
    pub masque_client_certificate_chain_pem: Option<String>,
    pub masque_client_private_key_pem: Option<String>,
    pub masque_cloudflare_geohash_header: Option<String>,
    pub masque_privacy_pass_provider_url: Option<String>,
    pub masque_privacy_pass_provider_auth_token: Option<String>,
    pub cloudflare_tunnel_token: Option<String>,
    pub cloudflare_tunnel_credentials_json: Option<String>,
    #[serde(default)]
    pub finalmask: ResolvedRelayFinalmaskConfig,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedShadowTlsInnerRelayConfig {
    pub kind: String,
    pub profile_id: String,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub reality_public_key: String,
    pub reality_short_id: String,
    pub vless_transport: String,
    pub vless_uuid: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RelayTelemetry {
    pub source: &'static str,
    pub state: String,
    pub health: String,
    pub active_sessions: u64,
    pub total_sessions: u64,
    pub listener_address: Option<String>,
    pub upstream_address: Option<String>,
    pub last_target: Option<String>,
    pub last_error: Option<String>,
    pub profile_id: Option<String>,
    pub protocol_kind: Option<String>,
    pub tcp_capable: Option<bool>,
    pub udp_capable: Option<bool>,
    pub fallback_mode: Option<String>,
    pub last_handshake_error: Option<String>,
    pub chain_entry_state: Option<String>,
    pub chain_exit_state: Option<String>,
    pub strategy_pack_id: Option<String>,
    pub strategy_pack_version: Option<String>,
    pub tls_profile_id: Option<String>,
    pub tls_profile_catalog_version: Option<String>,
    pub morph_policy_id: Option<String>,
    pub quic_migration_status: Option<String>,
    pub quic_migration_reason: Option<String>,
    pub pt_runtime_kind: Option<String>,
    pub pt_runtime_state: Option<String>,
    pub captured_at: u64,
}

trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}
type BoxedIo = Box<dyn AsyncIo>;

#[derive(Clone, Default)]
struct QuicMigrationTelemetryState {
    inner: Arc<Mutex<QuicMigrationTelemetrySnapshot>>,
}

#[derive(Default)]
struct QuicMigrationTelemetrySnapshot {
    status: Option<String>,
    reason: Option<String>,
}

impl QuicMigrationTelemetryState {
    fn update(&self, status: Option<&str>, reason: Option<&str>) {
        let mut snapshot = self.inner.lock().expect("quic migration telemetry");
        snapshot.status = status.map(ToOwned::to_owned);
        snapshot.reason = reason.map(ToOwned::to_owned);
    }

    fn snapshot(&self) -> (Option<String>, Option<String>) {
        let snapshot = self.inner.lock().expect("quic migration telemetry");
        (snapshot.status.clone(), snapshot.reason.clone())
    }
}

enum RelayUdpSession {
    Hysteria2 {
        session: MuxLease<ripdpi_hysteria2::UdpSession, Hysteria2Session>,
        migration: QuicMigrationTelemetryState,
    },
    Tuic {
        session: MuxLease<ripdpi_tuic::UdpSession, TuicSession>,
        migration: QuicMigrationTelemetryState,
    },
    Masque {
        session: MuxLease<ripdpi_masque::MasqueUdpRelay, MasqueSession>,
        migration: QuicMigrationTelemetryState,
    },
}

impl RelayUdpSession {
    async fn send_to(&mut self, target: &RelayTargetAddr, payload: &[u8]) -> io::Result<()> {
        match self {
            Self::Hysteria2 { session, migration } => {
                let result = session.get_mut().send_to(&target.to_connect_target(), payload).await.map_err(to_io_error);
                sync_quic_migration_state(migration, session.get_mut().quic_migration_snapshot());
                result
            }
            Self::Tuic { session, migration } => {
                let result = session.get_mut().send_to(&target.to_connect_target(), payload).await;
                sync_quic_migration_state(migration, session.get_mut().quic_migration_snapshot());
                result
            }
            Self::Masque { session, migration } => {
                let result = session.get_mut().send_to(&target.to_connect_target(), payload).await;
                sync_quic_migration_state(migration, session.get_mut().quic_migration_snapshot());
                result
            }
        }
    }

    async fn recv_from(&mut self) -> io::Result<(RelayTargetAddr, Vec<u8>)> {
        match self {
            Self::Hysteria2 { session, migration } => {
                let (address, payload) = session.get_mut().recv_from().await.map_err(to_io_error)?;
                sync_quic_migration_state(migration, session.get_mut().quic_migration_snapshot());
                Ok((RelayTargetAddr::from_authority(&address)?, payload))
            }
            Self::Tuic { session, migration } => {
                let (address, payload) = session.get_mut().recv_from().await?;
                sync_quic_migration_state(migration, session.get_mut().quic_migration_snapshot());
                Ok((RelayTargetAddr::from_authority(&address)?, payload))
            }
            Self::Masque { session, migration } => {
                let (address, payload) = session.get_mut().recv_from().await?;
                sync_quic_migration_state(migration, session.get_mut().quic_migration_snapshot());
                Ok((RelayTargetAddr::from_authority(&address)?, payload))
            }
        }
    }
}

fn sync_quic_migration_state(telemetry: &QuicMigrationTelemetryState, snapshot: (Option<String>, Option<String>)) {
    telemetry.update(snapshot.0.as_deref(), snapshot.1.as_deref());
}

macro_rules! dispatch_pooled_backend {
    ($self:expr, $backend:ident => $expr:expr, unsupported => $unsupported:expr) => {
        match $self {
            RelayBackend::Hysteria2($backend) => $expr,
            RelayBackend::Tuic($backend) => $expr,
            RelayBackend::VlessReality($backend) => $expr,
            RelayBackend::Xhttp($backend) => $expr,
            RelayBackend::ChainRelay($backend) => $expr,
            RelayBackend::Masque($backend) => $expr,
            RelayBackend::ShadowTls($backend) => $expr,
            RelayBackend::Unsupported { .. } => $unsupported,
        }
    };
}

macro_rules! open_quic_udp_session {
    ($backend:expr, $variant:ident) => {{
        let migration = $backend.quic_migration_snapshot_state();
        $backend
            .open_udp_session(move |session| RelayUdpSession::$variant { session, migration: migration.clone() })
            .await
    }};
}

enum RelayBackend {
    Hysteria2(PooledRelayBackend<Hysteria2SessionFactory>),
    Tuic(PooledRelayBackend<TuicSessionFactory>),
    VlessReality(PooledRelayBackend<VlessRealitySessionFactory>),
    Xhttp(PooledRelayBackend<XhttpSessionFactory>),
    ChainRelay(PooledRelayBackend<ChainRelaySessionFactory>),
    Masque(PooledRelayBackend<MasqueSessionFactory>),
    ShadowTls(PooledRelayBackend<ShadowTlsSessionFactory>),
    Unsupported { kind: String },
}

impl RelayBackend {
    fn unsupported_error(kind: &str) -> io::Error {
        io::Error::new(io::ErrorKind::Unsupported, format!("relay backend {kind} is not implemented"))
    }

    fn capabilities(&self) -> RelayCapabilities {
        dispatch_pooled_backend!(self, backend => backend.capabilities(), unsupported => RelayCapabilities::default())
    }

    fn pool_health(&self) -> Option<RelayPoolHealth> {
        dispatch_pooled_backend!(self, backend => Some(backend.pool_health()), unsupported => None)
    }

    fn udp_capable(&self) -> bool {
        self.capabilities().udp
    }

    fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        match self {
            Self::Hysteria2(backend) => backend.quic_migration_snapshot(),
            Self::Tuic(backend) => backend.quic_migration_snapshot(),
            Self::Masque(backend) => backend.quic_migration_snapshot(),
            Self::VlessReality(_)
            | Self::Xhttp(_)
            | Self::ChainRelay(_)
            | Self::ShadowTls(_)
            | Self::Unsupported { .. } => (None, None),
        }
    }

    async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        dispatch_pooled_backend!(
            self,
            backend => backend.connect_tcp(target).await,
            unsupported => {
                let Self::Unsupported { kind } = self else { unreachable!("macro must only route Unsupported here") };
                Err(Self::unsupported_error(kind))
            }
        )
    }

    async fn open_udp_session(&self) -> io::Result<RelayUdpSession> {
        match self {
            Self::Hysteria2(backend) => open_quic_udp_session!(backend, Hysteria2),
            Self::Tuic(backend) => open_quic_udp_session!(backend, Tuic),
            Self::Masque(backend) => open_quic_udp_session!(backend, Masque),
            Self::VlessReality(_) | Self::Xhttp(_) | Self::ChainRelay(_) | Self::ShadowTls(_) => {
                Err(io::Error::new(io::ErrorKind::Unsupported, "relay backend does not support UDP ASSOCIATE"))
            }
            Self::Unsupported { kind, .. } => Err(Self::unsupported_error(kind)),
        }
    }
}

struct PooledRelayBackend<F>
where
    F: RelaySessionFactory<Error = io::Error>,
{
    mux: RelayMux<F>,
    migration: Option<QuicMigrationTelemetryState>,
}

#[derive(Clone)]
struct Hysteria2SessionFactory {
    config: ripdpi_hysteria2::Config,
    migration: QuicMigrationTelemetryState,
}

struct Hysteria2Session {
    client: ripdpi_hysteria2::HysteriaClient,
    migration: QuicMigrationTelemetryState,
}

#[derive(Clone)]
struct TuicSessionFactory {
    config: ripdpi_tuic::Config,
    migration: QuicMigrationTelemetryState,
}

struct TuicSession {
    client: ripdpi_tuic::TuicClient,
    migration: QuicMigrationTelemetryState,
}

#[derive(Clone)]
struct VlessRealitySessionFactory {
    config: ripdpi_vless::config::VlessRealityConfig,
    outbound_bind_ip: Option<IpAddr>,
}

struct VlessRealitySession {
    config: ripdpi_vless::config::VlessRealityConfig,
    outbound_bind_ip: Option<IpAddr>,
}

#[derive(Clone)]
enum XhttpSessionMode {
    Reality(ripdpi_xhttp::XhttpRealityConfig),
    Tls(ripdpi_xhttp::XhttpTlsConfig),
}

#[derive(Clone)]
struct XhttpSessionFactory {
    mode: XhttpSessionMode,
}

struct XhttpSession {
    client: ripdpi_xhttp::XhttpClient,
}

#[derive(Clone)]
struct ChainRelaySessionFactory {
    entry: ripdpi_vless::config::VlessRealityConfig,
    exit: ripdpi_vless::config::VlessRealityConfig,
    outbound_bind_ip: Option<IpAddr>,
}

struct ChainRelaySession {
    entry: ripdpi_vless::config::VlessRealityConfig,
    exit: ripdpi_vless::config::VlessRealityConfig,
    outbound_bind_ip: Option<IpAddr>,
}

#[derive(Clone)]
struct MasqueSessionFactory {
    config: ripdpi_masque::config::MasqueConfig,
    migration: QuicMigrationTelemetryState,
}

struct MasqueSession {
    client: ripdpi_masque::MasqueClient,
    migration: QuicMigrationTelemetryState,
}

#[derive(Clone)]
struct ShadowTlsSessionFactory {
    client_config: ripdpi_shadowtls::Config,
    outer_server: String,
    outer_server_port: i32,
    inner: ResolvedShadowTlsInnerRelayConfig,
}

struct ShadowTlsSession {
    client_config: ripdpi_shadowtls::Config,
    outer_server: String,
    outer_server_port: i32,
    inner: ResolvedShadowTlsInnerRelayConfig,
}

impl RelaySession for Hysteria2Session {
    type Stream = ripdpi_hysteria2::DuplexStream;
    type Datagram = ripdpi_hysteria2::UdpSession;
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move {
            let stream = self.client.tcp_connect(target).await.map_err(to_io_error)?;
            sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
            Ok(stream)
        })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            let session = self.client.udp_session().await.map_err(to_io_error)?;
            sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
            Ok(session)
        })
    }
}

impl RelaySessionFactory for Hysteria2SessionFactory {
    type Session = Hysteria2Session;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let config = self.config.clone();
        let migration = self.migration.clone();
        Box::pin(async move {
            let client = ripdpi_hysteria2::connect(&config).await.map_err(to_io_error)?;
            sync_quic_migration_state(&migration, client.quic_migration_snapshot());
            Ok(Arc::new(Hysteria2Session { client, migration }))
        })
    }
}

impl RelaySession for TuicSession {
    type Stream = ripdpi_tuic::DuplexStream;
    type Datagram = ripdpi_tuic::UdpSession;
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move {
            let stream = self.client.tcp_connect(target).await?;
            sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
            Ok(stream)
        })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            let session = self.client.udp_session().await?;
            sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
            Ok(session)
        })
    }
}

impl RelaySessionFactory for TuicSessionFactory {
    type Session = TuicSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let config = self.config.clone();
        let migration = self.migration.clone();
        Box::pin(async move {
            let client = ripdpi_tuic::TuicClient::connect(config).await?;
            sync_quic_migration_state(&migration, client.quic_migration_snapshot());
            Ok(Arc::new(TuicSession { client, migration }))
        })
    }
}

impl RelaySession for VlessRealitySession {
    type Stream = Box<dyn ripdpi_vless::AsyncIo>;
    type Datagram = ();
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move {
            let stream = match self.outbound_bind_ip {
                Some(bind_ip) => {
                    ripdpi_vless::VlessRealityClient::connect_with_bind(&self.config, bind_ip, target).await?
                }
                None => ripdpi_vless::VlessRealityClient::connect(&self.config, target).await?,
            };
            let stream: Box<dyn ripdpi_vless::AsyncIo> = Box::new(stream);
            Ok(stream)
        })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            Err(io::Error::new(io::ErrorKind::Unsupported, "VLESS Reality relay does not support UDP ASSOCIATE"))
        })
    }
}

impl RelaySessionFactory for VlessRealitySessionFactory {
    type Session = VlessRealitySession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let config = self.config.clone();
        let outbound_bind_ip = self.outbound_bind_ip;
        Box::pin(async move { Ok(Arc::new(VlessRealitySession { config, outbound_bind_ip })) })
    }
}

impl RelaySession for XhttpSession {
    type Stream = ripdpi_xhttp::XhttpStream;
    type Datagram = ();
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move { self.client.connect(target).await })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            Err(io::Error::new(io::ErrorKind::Unsupported, "xHTTP relay does not support UDP ASSOCIATE"))
        })
    }
}

impl RelaySessionFactory for XhttpSessionFactory {
    type Session = XhttpSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: true }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let mode = self.mode.clone();
        Box::pin(async move {
            let client = match mode {
                XhttpSessionMode::Reality(config) => ripdpi_xhttp::XhttpClient::new_reality(config),
                XhttpSessionMode::Tls(config) => ripdpi_xhttp::XhttpClient::new_tls(config),
            };
            Ok(Arc::new(XhttpSession { client }))
        })
    }
}

impl RelaySession for ChainRelaySession {
    type Stream = Box<dyn ripdpi_vless::AsyncIo>;
    type Datagram = ();
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move {
            let exit_target = format!("{}:{}", self.exit.server, self.exit.port);
            let first_hop = match self.outbound_bind_ip {
                Some(bind_ip) => {
                    ripdpi_vless::VlessRealityClient::connect_with_bind(&self.entry, bind_ip, &exit_target).await?
                }
                None => ripdpi_vless::VlessRealityClient::connect(&self.entry, &exit_target).await?,
            };
            let second_hop = ripdpi_vless::VlessRealityClient::connect_over(&self.exit, first_hop, target).await?;
            let stream: Box<dyn ripdpi_vless::AsyncIo> = Box::new(second_hop);
            Ok(stream)
        })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            Err(io::Error::new(io::ErrorKind::Unsupported, "chain relay does not support UDP ASSOCIATE"))
        })
    }
}

impl RelaySessionFactory for ChainRelaySessionFactory {
    type Session = ChainRelaySession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let entry = self.entry.clone();
        let exit = self.exit.clone();
        let outbound_bind_ip = self.outbound_bind_ip;
        Box::pin(async move { Ok(Arc::new(ChainRelaySession { entry, exit, outbound_bind_ip })) })
    }
}

impl RelaySession for MasqueSession {
    type Stream = Box<dyn ripdpi_masque::AsyncIo>;
    type Datagram = ripdpi_masque::MasqueUdpRelay;
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move {
            let stream = self.client.connect_tcp(target).await?;
            sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
            Ok(stream)
        })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            let session = self.client.udp_session();
            sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
            Ok(session)
        })
    }
}

impl RelaySessionFactory for MasqueSessionFactory {
    type Session = MasqueSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let config = self.config.clone();
        let migration = self.migration.clone();
        Box::pin(async move {
            let client = ripdpi_masque::MasqueClient::new(config)?;
            sync_quic_migration_state(&migration, client.quic_migration_snapshot());
            Ok(Arc::new(MasqueSession { client, migration }))
        })
    }
}

impl RelaySession for ShadowTlsSession {
    type Stream = Box<dyn ripdpi_vless::AsyncIo>;
    type Datagram = ();
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move {
            let client = ripdpi_shadowtls::ShadowTlsClient::new(self.client_config.clone());
            let transport = client
                .connect(&self.outer_server, self.outer_server_port)
                .await
                .map_err(|error| io::Error::new(error.kind(), format!("shadowtls connect: {error}")))?;

            match self.inner.kind.as_str() {
                "vless_reality" => {
                    if self.inner.vless_transport == "xhttp" {
                        return Err(io::Error::new(
                            io::ErrorKind::Unsupported,
                            "ShadowTLS inner VLESS xHTTP transport is not supported yet",
                        ));
                    }
                    let uuid =
                        self.inner.vless_uuid.as_ref().filter(|value| !value.trim().is_empty()).ok_or_else(|| {
                            io::Error::new(io::ErrorKind::InvalidInput, "missing ShadowTLS inner VLESS UUID")
                        })?;
                    let config = ripdpi_vless::config::VlessRealityConfig::from_strings(
                        &self.inner.server,
                        self.inner.server_port,
                        uuid,
                        &self.inner.server_name,
                        &self.inner.reality_public_key,
                        &self.inner.reality_short_id,
                        "chrome_stable",
                    )
                    .map_err(|error| {
                        io::Error::new(io::ErrorKind::InvalidInput, format!("shadowtls inner vless: {error}"))
                    })?;
                    let stream = ripdpi_vless::VlessRealityClient::connect_over(&config, transport, target).await?;
                    let stream: Box<dyn ripdpi_vless::AsyncIo> = Box::new(stream);
                    Ok(stream)
                }
                other => Err(io::Error::new(
                    io::ErrorKind::Unsupported,
                    format!("ShadowTLS inner relay kind {other} is not supported"),
                )),
            }
        })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            Err(io::Error::new(io::ErrorKind::Unsupported, "ShadowTLS relay does not support UDP ASSOCIATE"))
        })
    }
}

impl RelaySessionFactory for ShadowTlsSessionFactory {
    type Session = ShadowTlsSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let client_config = self.client_config.clone();
        let outer_server = self.outer_server.clone();
        let outer_server_port = self.outer_server_port;
        let inner = self.inner.clone();
        Box::pin(
            async move { Ok(Arc::new(ShadowTlsSession { client_config, outer_server, outer_server_port, inner })) },
        )
    }
}

impl<F> PooledRelayBackend<F>
where
    F: RelaySessionFactory<Error = io::Error>,
{
    fn new(factory: F, config: RelayPoolConfig, migration: Option<QuicMigrationTelemetryState>) -> Self {
        Self { mux: RelayMux::new(factory, config), migration }
    }

    fn capabilities(&self) -> RelayCapabilities {
        self.mux.capabilities()
    }

    fn pool_health(&self) -> RelayPoolHealth {
        self.mux.health()
    }

    fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.migration.as_ref().map_or((None, None), QuicMigrationTelemetryState::snapshot)
    }

    fn quic_migration_snapshot_state(&self) -> QuicMigrationTelemetryState {
        self.migration.clone().unwrap_or_default()
    }
}

impl<F> PooledRelayBackend<F>
where
    F: RelaySessionFactory<Error = io::Error>,
    <F::Session as RelaySession>::Stream: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        let stream = self.mux.open_stream(&target.to_connect_target()).await?;
        Ok(Box::new(stream))
    }
}

impl<F> PooledRelayBackend<F>
where
    F: RelaySessionFactory<Error = io::Error>,
    <F::Session as RelaySession>::Datagram: Send + 'static,
{
    async fn open_udp_session<M>(&self, map: M) -> io::Result<RelayUdpSession>
    where
        M: FnOnce(MuxLease<<F::Session as RelaySession>::Datagram, F::Session>) -> RelayUdpSession,
    {
        self.mux.open_datagram().await.map(map)
    }
}

pub struct RelayRuntime {
    config: ResolvedRelayRuntimeConfig,
    stop_requested: AtomicBool,
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    backend: Mutex<Option<Arc<RelayBackend>>>,
    listener_address: Mutex<Option<String>>,
    last_target: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
    last_handshake_error: Mutex<Option<String>>,
}

impl RelayRuntime {
    pub fn new(config: ResolvedRelayRuntimeConfig) -> Arc<Self> {
        Arc::new(Self {
            config,
            stop_requested: AtomicBool::new(false),
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            backend: Mutex::new(None),
            listener_address: Mutex::new(None),
            last_target: Mutex::new(None),
            last_error: Mutex::new(None),
            last_handshake_error: Mutex::new(None),
        })
    }

    pub fn stop(&self) {
        self.stop_requested.store(true, Ordering::SeqCst);
    }

    pub fn telemetry(&self) -> RelayTelemetry {
        let backend = self.backend.lock().expect("relay backend").clone();
        let capabilities =
            backend.as_deref().map_or_else(|| planned_backend_capabilities(&self.config), RelayBackend::capabilities);
        let (quic_migration_status, quic_migration_reason) =
            backend.as_deref().map_or((None, None), RelayBackend::quic_migration_snapshot);
        let is_running = self.running.load(Ordering::SeqCst);
        let state = if is_running { "running" } else { "idle" };

        RelayTelemetry {
            source: "relay",
            state: state.to_string(),
            health: describe_runtime_health(state, backend.as_deref()),
            active_sessions: self.active_sessions.load(Ordering::SeqCst),
            total_sessions: self.total_sessions.load(Ordering::SeqCst),
            listener_address: self.listener_address.lock().expect("listener address").clone(),
            upstream_address: Some(describe_upstream(&self.config)),
            last_target: self.last_target.lock().expect("last target").clone(),
            last_error: self.last_error.lock().expect("last error").clone(),
            profile_id: Some(self.config.profile_id.clone()),
            protocol_kind: Some(self.config.kind.clone()),
            tcp_capable: Some(capabilities.tcp),
            udp_capable: Some(capabilities.udp),
            fallback_mode: planned_backend_fallback_mode(&self.config),
            last_handshake_error: self.last_handshake_error.lock().expect("handshake error").clone(),
            chain_entry_state: if self.config.kind == "chain_relay" {
                Some(if is_running { "connected" } else { "idle" }.to_string())
            } else {
                None
            },
            chain_exit_state: if self.config.kind == "chain_relay" {
                Some(if is_running { "connected" } else { "idle" }.to_string())
            } else {
                None
            },
            strategy_pack_id: None,
            strategy_pack_version: None,
            tls_profile_id: Some(self.config.tls_fingerprint_profile.clone()),
            tls_profile_catalog_version: Some(profile_catalog_version().to_string()),
            morph_policy_id: None,
            quic_migration_status,
            quic_migration_reason,
            pt_runtime_kind: None,
            pt_runtime_state: None,
            captured_at: now_ms(),
        }
    }

    pub async fn run(self: Arc<Self>) -> io::Result<()> {
        let backend = Arc::new(build_backend(&self.config).await?);
        validate_runtime_config(&self.config, &backend)?;
        *self.backend.lock().expect("relay backend") = Some(Arc::clone(&backend));

        let bind_addr = format!("{}:{}", self.config.local_socks_host, self.config.local_socks_port);
        let listener = TcpListener::bind(&bind_addr).await?;
        *self.listener_address.lock().expect("listener address") = Some(bind_addr);
        self.running.store(true, Ordering::SeqCst);
        emit_runtime_ready(self.listener_address.lock().expect("listener address").as_deref().unwrap_or_default());

        while !self.stop_requested.load(Ordering::SeqCst) {
            match timeout(ACCEPT_POLL_INTERVAL, listener.accept()).await {
                Ok(Ok((stream, _))) => {
                    let runtime = Arc::clone(&self);
                    let backend = Arc::clone(&backend);
                    tokio::spawn(async move {
                        runtime.active_sessions.fetch_add(1, Ordering::SeqCst);
                        runtime.total_sessions.fetch_add(1, Ordering::SeqCst);
                        if let Err(error) = runtime.handle_client(stream, backend).await {
                            *runtime.last_error.lock().expect("last error") = Some(error.to_string());
                        }
                        runtime.active_sessions.fetch_sub(1, Ordering::SeqCst);
                    });
                }
                Ok(Err(error)) => {
                    *self.last_error.lock().expect("last error") = Some(error.to_string());
                }
                Err(_) => {}
            }
        }

        self.running.store(false, Ordering::SeqCst);
        emit_runtime_stopped();
        *self.backend.lock().expect("relay backend") = None;
        Ok(())
    }

    async fn handle_client(&self, mut client: TcpStream, backend: Arc<RelayBackend>) -> io::Result<()> {
        let mut greeting = [0u8; 2];
        client.read_exact(&mut greeting).await?;
        if greeting[0] != 0x05 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 version"));
        }

        let method_count = usize::from(greeting[1]);
        let mut methods = vec![0u8; method_count];
        client.read_exact(&mut methods).await?;
        client.write_all(&[0x05, 0x00]).await?;

        let mut request_header = [0u8; 4];
        client.read_exact(&mut request_header).await?;
        if request_header[0] != 0x05 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 request"));
        }

        let command = request_header[1];
        let target = read_target(&mut client, request_header[3]).await?;
        *self.last_target.lock().expect("last target") = Some(target.to_string());

        match command {
            0x01 => self.handle_connect(client, backend, target).await,
            0x03 => self.handle_udp_associate(client, backend).await,
            _ => {
                write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                Err(io::Error::new(io::ErrorKind::Unsupported, format!("SOCKS5 command {command:#x} is not supported")))
            }
        }
    }

    async fn handle_connect(
        &self,
        mut client: TcpStream,
        backend: Arc<RelayBackend>,
        target: RelayTargetAddr,
    ) -> io::Result<()> {
        let mut upstream = match backend.connect_tcp(&target).await {
            Ok(stream) => stream,
            Err(error) => {
                *self.last_handshake_error.lock().expect("handshake error") = Some(error.to_string());
                write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                return Err(error);
            }
        };

        write_reply(&mut client, 0x00, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
        let _ = copy_bidirectional(&mut client, &mut upstream).await?;
        Ok(())
    }

    async fn handle_udp_associate(&self, mut client: TcpStream, backend: Arc<RelayBackend>) -> io::Result<()> {
        if !backend.udp_capable() {
            write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                format!("relay backend {} does not support UDP ASSOCIATE", self.config.kind),
            ));
        }

        let mut udp_session = match backend.open_udp_session().await {
            Ok(session) => session,
            Err(error) => {
                *self.last_handshake_error.lock().expect("handshake error") = Some(error.to_string());
                write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                return Err(error);
            }
        };

        let udp_socket = UdpSocket::bind(format!("{}:0", self.config.local_socks_host)).await?;
        let bound = udp_socket.local_addr()?;
        write_reply(&mut client, 0x00, bound).await?;

        let control_ip = client.peer_addr()?.ip();
        let mut associated_client = None;
        let mut udp_buffer = vec![0u8; UDP_BUFFER_SIZE];
        let mut control_probe = [0u8; 1];
        let control_closed = async {
            let _ = client.read(&mut control_probe).await;
        };
        tokio::pin!(control_closed);

        loop {
            tokio::select! {
                _ = &mut control_closed => break,
                recv = udp_socket.recv_from(&mut udp_buffer) => {
                    let (received, source) = recv?;
                    if source.ip() != control_ip {
                        continue;
                    }
                    associated_client = Some(source);
                let (target, payload) = decode_udp_frame(&udp_buffer[..received])?;
                *self.last_target.lock().expect("last target") = Some(target.to_string());
                    if let Err(error) = udp_session.send_to(&target, payload).await {
                        *self.last_handshake_error.lock().expect("handshake error") = Some(error.to_string());
                        return Err(error);
                    }
                }
                result = udp_session.recv_from() => {
                    let (target, payload) = result?;
                    let Some(destination) = associated_client else {
                        continue;
                    };
                    let frame = encode_udp_frame(&target, &payload)?;
                    udp_socket.send_to(&frame, destination).await?;
                }
            }
        }

        Ok(())
    }
}

async fn build_backend(config: &ResolvedRelayRuntimeConfig) -> io::Result<RelayBackend> {
    let outbound_bind_ip = parse_outbound_bind_ip(&config.outbound_bind_ip)?;
    let pool_config = pool_config_for_backend(config);
    let quic_migration = QuicMigrationTelemetryState::default();
    match config.kind.as_str() {
        "hysteria2" => {
            let password = config
                .hysteria_password
                .as_ref()
                .filter(|value| !value.trim().is_empty())
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing Hysteria2 password"))?;
            let mut client_config = ripdpi_hysteria2::Config::from_url(&format!(
                "hysteria2://{password}@{}:{}/?sni={}",
                config.server, config.server_port, config.server_name,
            ))
            .map_err(to_io_error)?;
            client_config.salamander_key =
                config.hysteria_salamander_key.as_ref().filter(|value| !value.trim().is_empty()).cloned();
            client_config.quic_bind_low_port = config.quic_bind_low_port;
            client_config.quic_migrate_after_handshake = config.quic_migrate_after_handshake;
            Ok(RelayBackend::Hysteria2(PooledRelayBackend::new(
                Hysteria2SessionFactory { config: client_config, migration: quic_migration.clone() },
                pool_config,
                Some(quic_migration),
            )))
        }
        "tuic_v5" => Ok(RelayBackend::Tuic(PooledRelayBackend::new(
            TuicSessionFactory {
                config: ripdpi_tuic::Config {
                    server: config.server.clone(),
                    server_port: config.server_port,
                    server_name: config.server_name.clone(),
                    uuid: config.tuic_uuid.clone().unwrap_or_default(),
                    password: config.tuic_password.clone().unwrap_or_default(),
                    zero_rtt: config.tuic_zero_rtt,
                    congestion_control: config.tuic_congestion_control.clone(),
                    udp_enabled: config.udp_enabled,
                    quic_bind_low_port: config.quic_bind_low_port,
                    quic_migrate_after_handshake: config.quic_migrate_after_handshake,
                },
                migration: quic_migration.clone(),
            },
            pool_config,
            Some(quic_migration),
        ))),
        "vless_reality" if config.vless_transport == "xhttp" => {
            let vless = ripdpi_vless::config::VlessRealityConfig::from_strings(
                &config.server,
                config.server_port,
                config.vless_uuid.as_deref().unwrap_or_default(),
                &config.server_name,
                &config.reality_public_key,
                &config.reality_short_id,
                &config.tls_fingerprint_profile,
            )
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?;
            Ok(RelayBackend::Xhttp(PooledRelayBackend::new(
                XhttpSessionFactory {
                    mode: XhttpSessionMode::Reality(ripdpi_xhttp::XhttpRealityConfig {
                        vless,
                        path: config.xhttp_path.clone(),
                        host: if config.xhttp_host.trim().is_empty() {
                            None
                        } else {
                            Some(config.xhttp_host.trim().to_owned())
                        },
                        bind_ip: outbound_bind_ip,
                        xmux: ripdpi_xhttp::XmuxConfig::default(),
                        finalmask: ripdpi_xhttp::FinalmaskConfig {
                            r#type: config.finalmask.r#type.clone(),
                            header_hex: config.finalmask.header_hex.clone(),
                            trailer_hex: config.finalmask.trailer_hex.clone(),
                            rand_range: config.finalmask.rand_range.clone(),
                            sudoku_seed: config.finalmask.sudoku_seed.clone(),
                            fragment_packets: config.finalmask.fragment_packets,
                            fragment_min_bytes: config.finalmask.fragment_min_bytes,
                            fragment_max_bytes: config.finalmask.fragment_max_bytes,
                        },
                    }),
                },
                pool_config,
                None,
            )))
        }
        "vless_reality" => Ok(RelayBackend::VlessReality(PooledRelayBackend::new(
            VlessRealitySessionFactory {
                config: ripdpi_vless::config::VlessRealityConfig::from_strings(
                    &config.server,
                    config.server_port,
                    config.vless_uuid.as_deref().unwrap_or_default(),
                    &config.server_name,
                    &config.reality_public_key,
                    &config.reality_short_id,
                    &config.tls_fingerprint_profile,
                )
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?,
                outbound_bind_ip,
            },
            pool_config,
            None,
        ))),
        "cloudflare_tunnel" => Ok(RelayBackend::Xhttp(PooledRelayBackend::new(
            XhttpSessionFactory {
                mode: XhttpSessionMode::Tls({
                    let mut tls = ripdpi_xhttp::XhttpTlsConfig::from_strings(
                        &config.server,
                        config.server_port,
                        &config.server_name,
                        config.vless_uuid.as_deref().unwrap_or_default(),
                        &config.xhttp_path,
                        &config.xhttp_host,
                        &config.tls_fingerprint_profile,
                    )
                    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?;
                    tls.bind_ip = outbound_bind_ip;
                    tls.finalmask = ripdpi_xhttp::FinalmaskConfig {
                        r#type: config.finalmask.r#type.clone(),
                        header_hex: config.finalmask.header_hex.clone(),
                        trailer_hex: config.finalmask.trailer_hex.clone(),
                        rand_range: config.finalmask.rand_range.clone(),
                        sudoku_seed: config.finalmask.sudoku_seed.clone(),
                        fragment_packets: config.finalmask.fragment_packets,
                        fragment_min_bytes: config.finalmask.fragment_min_bytes,
                        fragment_max_bytes: config.finalmask.fragment_max_bytes,
                    };
                    tls
                }),
            },
            pool_config,
            None,
        ))),
        "chain_relay" => Ok(RelayBackend::ChainRelay(PooledRelayBackend::new(
            ChainRelaySessionFactory {
                entry: ripdpi_vless::config::VlessRealityConfig::from_strings(
                    &config.chain_entry_server,
                    config.chain_entry_port,
                    config.chain_entry_uuid.as_deref().unwrap_or_default(),
                    &config.chain_entry_server_name,
                    &config.chain_entry_public_key,
                    &config.chain_entry_short_id,
                    &config.tls_fingerprint_profile,
                )
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("chain entry: {error}")))?,
                exit: ripdpi_vless::config::VlessRealityConfig::from_strings(
                    &config.chain_exit_server,
                    config.chain_exit_port,
                    config.chain_exit_uuid.as_deref().unwrap_or_default(),
                    &config.chain_exit_server_name,
                    &config.chain_exit_public_key,
                    &config.chain_exit_short_id,
                    &config.tls_fingerprint_profile,
                )
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("chain exit: {error}")))?,
                outbound_bind_ip,
            },
            pool_config,
            None,
        ))),
        "masque" => Ok(RelayBackend::Masque(PooledRelayBackend::new(
            MasqueSessionFactory {
                config: ripdpi_masque::config::MasqueConfig {
                    url: config.masque_url.clone(),
                    use_http2_fallback: config.masque_use_http2_fallback,
                    auth_mode: config.masque_auth_mode.clone(),
                    auth_token: config.masque_auth_token.clone(),
                    client_certificate_chain_pem: config.masque_client_certificate_chain_pem.clone(),
                    client_private_key_pem: config.masque_client_private_key_pem.clone(),
                    cloudflare_geohash_header: config.masque_cloudflare_geohash_header.clone(),
                    privacy_pass_provider_url: config.masque_privacy_pass_provider_url.clone(),
                    privacy_pass_provider_auth_token: config.masque_privacy_pass_provider_auth_token.clone(),
                    tls_fingerprint_profile: config.tls_fingerprint_profile.clone(),
                    quic_bind_low_port: config.quic_bind_low_port,
                    quic_migrate_after_handshake: config.quic_migrate_after_handshake,
                },
                migration: quic_migration.clone(),
            },
            pool_config,
            Some(quic_migration),
        ))),
        "shadowtls_v3" => Ok(RelayBackend::ShadowTls(PooledRelayBackend::new(
            ShadowTlsSessionFactory {
                client_config: ripdpi_shadowtls::Config {
                    password: config.shadow_tls_password.clone().unwrap_or_default(),
                    server_name: config.server_name.clone(),
                    inner_profile_id: config.shadow_tls_inner_profile_id.clone(),
                },
                outer_server: config.server.clone(),
                outer_server_port: config.server_port,
                inner: config.shadow_tls_inner.clone().ok_or_else(|| {
                    io::Error::new(io::ErrorKind::InvalidInput, "missing ShadowTLS inner relay config")
                })?,
            },
            pool_config,
            None,
        ))),
        other => Ok(RelayBackend::Unsupported { kind: other.to_string() }),
    }
}

fn to_io_error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|duration| duration.as_millis() as u64).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime_validation::validate_finalmask_config;

    fn sample_config(kind: &str) -> ResolvedRelayRuntimeConfig {
        ResolvedRelayRuntimeConfig {
            enabled: true,
            kind: kind.to_string(),
            profile_id: "default".to_string(),
            outbound_bind_ip: String::new(),
            server: "relay.example".to_string(),
            server_port: 443,
            server_name: "relay.example".to_string(),
            reality_public_key: String::new(),
            reality_short_id: String::new(),
            vless_transport: "reality_tcp".to_string(),
            xhttp_path: String::new(),
            xhttp_host: String::new(),
            cloudflare_tunnel_mode: "consume_existing".to_string(),
            cloudflare_publish_local_origin_url: String::new(),
            cloudflare_credentials_ref: String::new(),
            chain_entry_server: String::new(),
            chain_entry_port: 443,
            chain_entry_server_name: String::new(),
            chain_entry_public_key: String::new(),
            chain_entry_short_id: String::new(),
            chain_entry_profile_id: String::new(),
            chain_exit_server: String::new(),
            chain_exit_port: 443,
            chain_exit_server_name: String::new(),
            chain_exit_public_key: String::new(),
            chain_exit_short_id: String::new(),
            chain_exit_profile_id: String::new(),
            masque_url: "https://masque.example/".to_string(),
            masque_use_http2_fallback: true,
            masque_cloudflare_geohash_enabled: false,
            tuic_zero_rtt: false,
            tuic_congestion_control: "bbr".to_string(),
            shadow_tls_inner_profile_id: String::new(),
            shadow_tls_inner: None,
            naive_path: String::new(),
            local_socks_host: "127.0.0.1".to_string(),
            local_socks_port: 10_80,
            udp_enabled: false,
            tcp_fallback_enabled: true,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            vless_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            chain_entry_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            chain_exit_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            hysteria_password: Some("secret".to_string()),
            hysteria_salamander_key: None,
            tuic_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            tuic_password: Some("secret".to_string()),
            shadow_tls_password: Some("secret".to_string()),
            naive_username: Some("user".to_string()),
            naive_password: Some("secret".to_string()),
            tls_fingerprint_profile: "chrome_stable".to_string(),
            masque_auth_mode: Some("token".to_string()),
            masque_auth_token: Some("token".to_string()),
            masque_client_certificate_chain_pem: None,
            masque_client_private_key_pem: None,
            masque_cloudflare_geohash_header: None,
            masque_privacy_pass_provider_url: None,
            masque_privacy_pass_provider_auth_token: None,
            cloudflare_tunnel_token: None,
            cloudflare_tunnel_credentials_json: None,
            finalmask: ResolvedRelayFinalmaskConfig::default(),
        }
    }

    #[test]
    fn relay_runtime_allows_hysteria_udp_and_salamander() {
        let mut config = sample_config("hysteria2");
        config.udp_enabled = true;
        config.hysteria_salamander_key = Some("salamander".to_string());
        let capabilities = planned_backend_capabilities(&config);
        assert!(capabilities.udp, "Hysteria2 should report UDP capability");
    }

    #[test]
    fn relay_runtime_allows_tuic_udp_and_zero_rtt() {
        let mut config = sample_config("tuic_v5");
        config.udp_enabled = true;
        config.tuic_zero_rtt = true;

        let capabilities = planned_backend_capabilities(&config);
        assert!(capabilities.tcp, "TUIC should report TCP capability");
        assert!(capabilities.udp, "TUIC should report UDP capability");
        assert_eq!("relay.example:443", describe_upstream(&config));
    }

    #[tokio::test]
    async fn relay_runtime_rejects_udp_without_backend_support() {
        let mut config = sample_config("vless_reality");
        config.udp_enabled = true;
        let backend = RelayBackend::Unsupported { kind: "vless_reality".to_string() };

        let error = validate_runtime_config(&config, &backend).expect_err("UDP must fail fast");
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    }

    #[tokio::test]
    async fn relay_runtime_allows_masque_udp_and_privacy_pass_provider() {
        let mut config = sample_config("masque");
        config.udp_enabled = true;
        config.masque_auth_mode = Some("privacy_pass".to_string());
        config.masque_auth_token = None;
        config.masque_client_certificate_chain_pem = None;
        config.masque_client_private_key_pem = None;
        config.masque_cloudflare_geohash_header = None;
        config.masque_privacy_pass_provider_url = Some("https://provider.example/token".to_string());

        let capabilities = planned_backend_capabilities(&config);
        assert!(capabilities.udp, "MASQUE should report UDP capability");
        let backend = build_backend(&config).await.expect("masque backend");
        validate_runtime_config(&config, &backend).expect("MASQUE privacy pass should validate");
    }

    #[test]
    fn relay_runtime_preserves_cloudflare_mtls_material() {
        let mut config = sample_config("masque");
        config.masque_auth_mode = Some("cloudflare_mtls".to_string());
        config.masque_auth_token = None;
        config.masque_client_certificate_chain_pem = Some("cert-chain".to_string());
        config.masque_client_private_key_pem = Some("private-key".to_string());
        config.masque_cloudflare_geohash_header = Some("u4pruyd-GB".to_string());

        assert_eq!(config.masque_auth_mode.as_deref(), Some("cloudflare_mtls"));
        assert_eq!(config.masque_cloudflare_geohash_header.as_deref(), Some("u4pruyd-GB"));
    }

    #[test]
    fn relay_runtime_rejects_finalmask_for_unsupported_transport() {
        let mut config = sample_config("vless_reality");
        config.finalmask = ResolvedRelayFinalmaskConfig {
            r#type: "header_custom".to_string(),
            header_hex: "abcd".to_string(),
            ..ResolvedRelayFinalmaskConfig::default()
        };

        let error = validate_finalmask_config(&config).expect_err("finalmask should be rejected");
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    }

    #[test]
    fn relay_runtime_accepts_finalmask_for_xhttp_vless() {
        let mut config = sample_config("vless_reality");
        config.vless_transport = "xhttp".to_string();
        config.finalmask = ResolvedRelayFinalmaskConfig {
            r#type: "fragment".to_string(),
            fragment_packets: 3,
            fragment_min_bytes: 32,
            fragment_max_bytes: 96,
            ..ResolvedRelayFinalmaskConfig::default()
        };

        validate_finalmask_config(&config).expect("xhttp finalmask should validate");
    }

    #[test]
    fn relay_runtime_accepts_finalmask_for_cloudflare_xhttp() {
        let mut config = sample_config("cloudflare_tunnel");
        config.finalmask = ResolvedRelayFinalmaskConfig {
            r#type: "sudoku".to_string(),
            sudoku_seed: "fixture-seed".to_string(),
            ..ResolvedRelayFinalmaskConfig::default()
        };

        validate_finalmask_config(&config).expect("cloudflare xhttp finalmask should validate");
    }

    #[test]
    fn relay_runtime_accepts_noise_for_xhttp_transports() {
        let mut config = sample_config("cloudflare_tunnel");
        config.finalmask = ResolvedRelayFinalmaskConfig {
            r#type: "noise".to_string(),
            rand_range: "8-12".to_string(),
            ..ResolvedRelayFinalmaskConfig::default()
        };

        validate_finalmask_config(&config).expect("noise should validate");
    }

    #[test]
    fn relay_telemetry_reports_tls_catalog_version() {
        let runtime = RelayRuntime::new(sample_config("masque"));

        let telemetry = runtime.telemetry();

        assert_eq!(Some("chrome_stable"), telemetry.tls_profile_id.as_deref());
        assert_eq!(Some(profile_catalog_version()), telemetry.tls_profile_catalog_version.as_deref());
    }

    #[test]
    fn relay_runtime_routes_vless_xhttp_through_tcp_only_backend() {
        let mut config = sample_config("vless_reality");
        config.vless_transport = "xhttp".to_string();
        config.xhttp_path = "/api/v1/stream".to_string();

        let capabilities = planned_backend_capabilities(&config);
        assert_eq!((true, false), (capabilities.tcp, capabilities.udp));
        assert_eq!("relay.example:443/api/v1/stream", describe_upstream(&config));
    }

    #[test]
    fn relay_runtime_assigns_central_pool_policy_by_backend_family() {
        let hysteria = pool_config_for_backend(&sample_config("hysteria2"));
        let xhttp = pool_config_for_backend(&ResolvedRelayRuntimeConfig {
            vless_transport: "xhttp".to_string(),
            ..sample_config("vless_reality")
        });
        let chain = pool_config_for_backend(&sample_config("chain_relay"));

        assert_eq!(64, hysteria.max_active_leases);
        assert_eq!(Duration::from_secs(45), hysteria.idle_timeout);
        assert_eq!(48, xhttp.max_active_leases);
        assert_eq!(Duration::from_secs(20), xhttp.idle_timeout);
        assert_eq!(16, chain.max_active_leases);
        assert_eq!(Duration::from_secs(5), chain.idle_timeout);
    }

    #[tokio::test]
    async fn relay_runtime_routes_cloudflare_tunnel_through_xhttp_backend() {
        let mut config = sample_config("cloudflare_tunnel");
        config.server = "edge.example.com".to_string();
        config.server_name = "edge.example.com".to_string();
        config.xhttp_path = "/cdn/api".to_string();

        let backend = build_backend(&config).await;
        assert!(backend.is_ok(), "cloudflare tunnel backend should resolve");
        assert_eq!("edge.example.com:443/cdn/api", describe_upstream(&config));
    }

    #[test]
    fn relay_runtime_rejects_invalid_outbound_bind_ip() {
        let mut config = sample_config("vless_reality");
        config.outbound_bind_ip = "not-an-ip".to_string();
        let backend = RelayBackend::Unsupported { kind: "vless_reality".to_string() };

        let error = validate_runtime_config(&config, &backend).expect_err("invalid bind ip must fail");
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn relay_runtime_rejects_bind_ip_for_unsupported_backend() {
        let mut config = sample_config("hysteria2");
        config.outbound_bind_ip = "203.0.113.10".to_string();
        let backend = RelayBackend::Unsupported { kind: "hysteria2".to_string() };

        let error = validate_runtime_config(&config, &backend).expect_err("unsupported bind ip must fail");
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    }

    #[tokio::test]
    async fn relay_runtime_builds_shadowtls_backend_with_inner_vless_profile() {
        let mut config = sample_config("shadowtls_v3");
        config.shadow_tls_inner_profile_id = "inner-vless".to_string();
        config.shadow_tls_inner = Some(ResolvedShadowTlsInnerRelayConfig {
            kind: "vless_reality".to_string(),
            profile_id: "inner-vless".to_string(),
            server: "inner.example".to_string(),
            server_port: 443,
            server_name: "inner.example".to_string(),
            reality_public_key: "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=".to_string(),
            reality_short_id: String::new(),
            vless_transport: "reality_tcp".to_string(),
            vless_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
        });

        let backend = build_backend(&config).await.expect("shadowtls backend");
        match backend {
            RelayBackend::ShadowTls(_) => {}
            other => panic!("expected ShadowTLS backend, got {:?}", std::mem::discriminant(&other)),
        }
    }
}
