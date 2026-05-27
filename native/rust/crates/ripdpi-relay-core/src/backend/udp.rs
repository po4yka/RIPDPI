use std::io;

use ripdpi_relay_mux::MuxLease;

use crate::protocols::{
    Hysteria2Session, MasqueSession, ShadowsocksSession, ShadowsocksUdpSession, TrojanSession, TrojanUdpSession,
    TuicSession,
};
use crate::socks::RelayTargetAddr;
use crate::telemetry::{sync_quic_migration_state, QuicMigrationTelemetryState};

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
    Trojan(MuxLease<TrojanUdpSession, TrojanSession>),
    Shadowsocks(MuxLease<ShadowsocksUdpSession, ShadowsocksSession>),
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
            Self::Trojan(session) => session.get_mut().send_to(&target.to_connect_target(), payload).await,
            Self::Shadowsocks(session) => session.get_mut().send_to(&target.to_connect_target(), payload).await,
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
            Self::Trojan(session) => {
                let (address, payload) = session.get_mut().recv_from().await?;
                Ok((RelayTargetAddr::from_authority(&address)?, payload))
            }
            Self::Shadowsocks(session) => {
                let (address, payload) = session.get_mut().recv_from().await?;
                Ok((RelayTargetAddr::from_authority(&address)?, payload))
            }
        }
    }
}

fn to_io_error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}
