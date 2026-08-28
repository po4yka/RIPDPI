pub(crate) mod builder;
mod pool;
mod udp;

use std::io;

use ripdpi_relay_mux::{RelayCapabilities, RelayPoolHealth};

use crate::backend::udp::RelayUdpSession;
use crate::protocols::{
    AnyTlsSessionFactory, ChainRelaySessionFactory, Hysteria2SessionFactory, MasqueSessionFactory, MieruSessionFactory,
    ShadowTlsSessionFactory, ShadowsocksSessionFactory, SshSessionFactory, TorRelayBackend, TrojanSessionFactory,
    TuicSessionFactory, VlessRealitySessionFactory, XhttpSessionFactory,
};
use crate::socks::RelayTargetAddr;
use crate::telemetry::{ChainHopTelemetrySnapshot, ChainHopTelemetryState};

pub(crate) use pool::{BoxedIo, PooledRelayBackend};

macro_rules! dispatch_pooled_backend {
    ($self:expr_2021, $backend:ident => $expr:expr_2021, unsupported => $kind:ident => $unsupported:expr_2021) => {
        match $self {
            RelayBackend::Hysteria2($backend) => $expr,
            RelayBackend::Tuic($backend) => $expr,
            RelayBackend::VlessReality($backend) => $expr,
            RelayBackend::Mieru($backend) => $expr,
            RelayBackend::Ssh($backend) => $expr,
            RelayBackend::Xhttp($backend) => $expr,
            RelayBackend::ChainRelay { backend: $backend, .. } => $expr,
            RelayBackend::Masque($backend) => $expr,
            RelayBackend::ShadowTls($backend) => $expr,
            RelayBackend::Trojan($backend) => $expr,
            RelayBackend::AnyTls($backend) => $expr,
            RelayBackend::Shadowsocks($backend) => $expr,
            RelayBackend::Tor($backend) => $expr,
            RelayBackend::Unsupported { kind: $kind } => $unsupported,
        }
    };
}

macro_rules! open_quic_udp_session {
    ($backend:expr_2021, $variant:ident) => {{
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
    Mieru(PooledRelayBackend<MieruSessionFactory>),
    Ssh(PooledRelayBackend<SshSessionFactory>),
    Xhttp(PooledRelayBackend<XhttpSessionFactory>),
    ChainRelay { backend: PooledRelayBackend<ChainRelaySessionFactory>, telemetry: ChainHopTelemetryState },
    Masque(PooledRelayBackend<MasqueSessionFactory>),
    ShadowTls(PooledRelayBackend<ShadowTlsSessionFactory>),
    Trojan(PooledRelayBackend<TrojanSessionFactory>),
    AnyTls(PooledRelayBackend<AnyTlsSessionFactory>),
    Shadowsocks(PooledRelayBackend<ShadowsocksSessionFactory>),
    Tor(Box<TorRelayBackend>),
    Unsupported { kind: String },
}

impl RelayBackend {
    fn unsupported_error(kind: &str) -> io::Error {
        io::Error::new(io::ErrorKind::Unsupported, format!("relay backend {kind} is not implemented"))
    }

    /// # Cancel safety
    /// Affected factories close admission and retain unfinished cleanup on cancellation.
    pub(crate) async fn shutdown(&self) -> io::Result<()> {
        match self {
            Self::Hysteria2(backend) => backend.shutdown().await,
            Self::Tuic(backend) => backend.shutdown().await,
            Self::VlessReality(backend) => backend.shutdown().await,
            Self::Mieru(backend) => backend.shutdown().await,
            Self::Ssh(backend) => backend.shutdown().await,
            Self::Xhttp(backend) => backend.shutdown().await,
            Self::ChainRelay { backend, .. } => backend.shutdown().await,
            Self::Masque(backend) => backend.shutdown().await,
            Self::ShadowTls(backend) => backend.shutdown().await,
            Self::Trojan(backend) => backend.shutdown().await,
            Self::AnyTls(backend) => backend.shutdown().await,
            Self::Shadowsocks(backend) => backend.shutdown().await,
            // These variants have no relay-session factory join registry.
            Self::Tor(_) | Self::Unsupported { .. } => Ok(()),
        }
    }

    pub(crate) fn capabilities(&self) -> RelayCapabilities {
        dispatch_pooled_backend!(self, backend => backend.capabilities(), unsupported => _kind => RelayCapabilities::default())
    }

    pub(crate) fn pool_health(&self) -> Option<RelayPoolHealth> {
        dispatch_pooled_backend!(self, backend => Some(backend.pool_health()), unsupported => _kind => None)
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
            | Self::Mieru(_)
            | Self::Ssh(_)
            | Self::Xhttp(_)
            | Self::ChainRelay { .. }
            | Self::ShadowTls(_)
            | Self::Trojan(_)
            | Self::AnyTls(_)
            | Self::Shadowsocks(_)
            | Self::Tor(_)
            | Self::Unsupported { .. } => (None, None),
        }
    }

    pub(crate) fn chain_hop_snapshot(&self) -> Option<ChainHopTelemetrySnapshot> {
        match self {
            Self::ChainRelay { telemetry, .. } => Some(telemetry.snapshot()),
            _ => None,
        }
    }

    /// # Cancel safety
    ///
    /// conditionally cancel-safe: inherits the selected backend's contract;
    /// callers must discard direct incomplete carriers, while pooled backends
    /// must reset only their newly opened logical stream.
    pub(crate) async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        let result = dispatch_pooled_backend!(
            self,
            backend => backend.connect_tcp(target).await,
            unsupported => kind => Err(Self::unsupported_error(kind))
        );
        // Turn a ShadowTLS v2-server reject into the
        // `FailureClass::ShadowTlsVersionMismatch` token + actionable diagnostic
        // so service telemetry shows "upgrade your ShadowTLS server to v3" instead
        // of a generic TLS handshake error. Applied to the direct ShadowTLS backend
        // and to chain relays (whose entry/exit hop may be ShadowTLS) — the mapper
        // is a no-op for every error that does not carry the typed
        // `ShadowTlsHandshakeError`, so covering `ChainRelay` is safe.
        match self {
            Self::ShadowTls(_) | Self::ChainRelay { .. } => {
                result.map_err(crate::protocols::classify_shadowtls_handshake_error)
            }
            _ => result,
        }
    }

    pub(crate) async fn open_udp_session(&self) -> io::Result<RelayUdpSession> {
        match self {
            Self::Hysteria2(backend) => open_quic_udp_session!(backend, Hysteria2),
            Self::Tuic(backend) => open_quic_udp_session!(backend, Tuic),
            Self::Masque(backend) => open_quic_udp_session!(backend, Masque),
            Self::Trojan(backend) => backend.open_udp_session(RelayUdpSession::Trojan).await,
            Self::AnyTls(backend) => backend.open_udp_session(RelayUdpSession::AnyTls).await,
            Self::Shadowsocks(backend) => backend.open_udp_session(RelayUdpSession::Shadowsocks).await,
            Self::VlessReality(backend) => backend.open_udp_session(RelayUdpSession::VlessReality).await,
            Self::Mieru(_)
            | Self::Ssh(_)
            | Self::Xhttp(_)
            | Self::ChainRelay { .. }
            | Self::ShadowTls(_)
            | Self::Tor(_) => {
                Err(io::Error::new(io::ErrorKind::Unsupported, "relay backend does not support UDP ASSOCIATE"))
            }
            Self::Unsupported { kind, .. } => Err(Self::unsupported_error(kind)),
        }
    }
}
