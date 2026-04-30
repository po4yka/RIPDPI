use std::io;

use ripdpi_relay_mux::{
    MuxLease, RelayCapabilities, RelayMux, RelayPoolConfig, RelayPoolHealth, RelaySession, RelaySessionFactory,
};
use tokio::io::{AsyncRead, AsyncWrite};

use crate::config::ResolvedRelayRuntimeConfig;
use crate::protocols::{
    ChainRelaySessionFactory, Hysteria2Session, Hysteria2SessionFactory, MasqueSession, MasqueSessionFactory,
    ShadowTlsSessionFactory, TuicSession, TuicSessionFactory, VlessRealitySessionFactory, XhttpSessionFactory,
    XhttpSessionMode,
};
use crate::runtime_validation::{parse_outbound_bind_ip, pool_config_for_backend};
use crate::socks::RelayTargetAddr;
use crate::telemetry::{sync_quic_migration_state, QuicMigrationTelemetryState};

pub(crate) trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}
pub(crate) type BoxedIo = Box<dyn AsyncIo>;

pub(crate) enum RelayUdpSession {
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
    pub(crate) async fn send_to(&mut self, target: &RelayTargetAddr, payload: &[u8]) -> io::Result<()> {
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

    pub(crate) async fn recv_from(&mut self) -> io::Result<(RelayTargetAddr, Vec<u8>)> {
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

pub(crate) enum RelayBackend {
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

    pub(crate) fn capabilities(&self) -> RelayCapabilities {
        dispatch_pooled_backend!(self, backend => backend.capabilities(), unsupported => RelayCapabilities::default())
    }

    pub(crate) fn pool_health(&self) -> Option<RelayPoolHealth> {
        dispatch_pooled_backend!(self, backend => Some(backend.pool_health()), unsupported => None)
    }

    pub(crate) fn udp_capable(&self) -> bool {
        self.capabilities().udp
    }

    pub(crate) fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
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

    pub(crate) async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        dispatch_pooled_backend!(
            self,
            backend => backend.connect_tcp(target).await,
            unsupported => {
                let Self::Unsupported { kind } = self else { unreachable!("macro must only route Unsupported here") };
                Err(Self::unsupported_error(kind))
            }
        )
    }

    pub(crate) async fn open_udp_session(&self) -> io::Result<RelayUdpSession> {
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

pub(crate) struct PooledRelayBackend<F>
where
    F: RelaySessionFactory<Error = io::Error>,
{
    mux: RelayMux<F>,
    migration: Option<QuicMigrationTelemetryState>,
}

impl<F> PooledRelayBackend<F>
where
    F: RelaySessionFactory<Error = io::Error>,
{
    fn new(factory: F, config: RelayPoolConfig, migration: Option<QuicMigrationTelemetryState>) -> Self {
        Self { mux: RelayMux::new(factory, config), migration }
    }

    pub(crate) fn capabilities(&self) -> RelayCapabilities {
        self.mux.capabilities()
    }

    pub(crate) fn pool_health(&self) -> RelayPoolHealth {
        self.mux.health()
    }

    pub(crate) fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.migration.as_ref().map_or((None, None), QuicMigrationTelemetryState::snapshot)
    }

    pub(crate) fn quic_migration_snapshot_state(&self) -> QuicMigrationTelemetryState {
        self.migration.clone().unwrap_or_default()
    }
}

impl<F> PooledRelayBackend<F>
where
    F: RelaySessionFactory<Error = io::Error>,
    <F::Session as RelaySession>::Stream: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    pub(crate) async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        let stream = self.mux.open_stream(&target.to_connect_target()).await?;
        Ok(Box::new(stream))
    }
}

impl<F> PooledRelayBackend<F>
where
    F: RelaySessionFactory<Error = io::Error>,
    <F::Session as RelaySession>::Datagram: Send + 'static,
{
    pub(crate) async fn open_udp_session<M>(&self, map: M) -> io::Result<RelayUdpSession>
    where
        M: FnOnce(MuxLease<<F::Session as RelaySession>::Datagram, F::Session>) -> RelayUdpSession,
    {
        self.mux.open_datagram().await.map(map)
    }
}

pub(crate) async fn build_backend(config: &ResolvedRelayRuntimeConfig) -> io::Result<RelayBackend> {
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
