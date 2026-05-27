pub(crate) mod builder;
mod pool;
mod udp;

use std::io;

use ripdpi_relay_mux::{RelayCapabilities, RelayPoolHealth};

use crate::backend::pool::BoxedIo;
use crate::backend::udp::RelayUdpSession;
use crate::protocols::{
    ChainRelaySessionFactory, Hysteria2SessionFactory, MasqueSessionFactory, ShadowTlsSessionFactory,
    ShadowsocksSessionFactory, TrojanSessionFactory, TuicSessionFactory, VlessRealitySessionFactory,
    XhttpSessionFactory,
};
use crate::socks::RelayTargetAddr;

pub(crate) use builder::build_backend;
pub(crate) use pool::PooledRelayBackend;

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
            RelayBackend::Trojan($backend) => $expr,
            RelayBackend::Shadowsocks($backend) => $expr,
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
    Trojan(PooledRelayBackend<TrojanSessionFactory>),
    Shadowsocks(PooledRelayBackend<ShadowsocksSessionFactory>),
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
            | Self::Trojan(_)
            | Self::Shadowsocks(_)
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
            Self::Trojan(backend) => backend.open_udp_session(RelayUdpSession::Trojan).await,
            Self::Shadowsocks(backend) => backend.open_udp_session(RelayUdpSession::Shadowsocks).await,
            Self::VlessReality(_) | Self::Xhttp(_) | Self::ChainRelay(_) | Self::ShadowTls(_) => {
                Err(io::Error::new(io::ErrorKind::Unsupported, "relay backend does not support UDP ASSOCIATE"))
            }
            Self::Unsupported { kind, .. } => Err(Self::unsupported_error(kind)),
        }
    }
}
