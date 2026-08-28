use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

use crate::telemetry::{QuicMigrationTelemetryState, sync_quic_migration_state};

#[derive(Clone)]
pub(crate) struct MasqueSessionFactory {
    pub(crate) config: ripdpi_masque::config::MasqueConfig,
    pub(crate) migration: QuicMigrationTelemetryState,
}

pub(crate) struct MasqueSession {
    client: ripdpi_masque::MasqueClient,
    pub(crate) migration: QuicMigrationTelemetryState,
}

impl RelaySession for MasqueSession {
    type Stream = Box<dyn ripdpi_masque::AsyncIo>;
    type Datagram = ripdpi_masque::MasqueUdpRelay;
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        let result = self.client.connect_tcp(target).await;
        sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
        result
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        let session = self.client.udp_session();
        sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
        Ok(session)
    }
}

impl RelaySessionFactory for MasqueSessionFactory {
    async fn shutdown(&self) -> Result<(), Self::Error> {
        Ok(())
    }

    type Session = MasqueSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let config = self.config.clone();
        let migration = self.migration.clone();
        let client = ripdpi_masque::MasqueClient::new(config)?;
        sync_quic_migration_state(&migration, client.quic_migration_snapshot());
        Ok(Arc::new(MasqueSession { client, migration }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn h3_tcp_rejection_is_copied_to_relay_telemetry() {
        let migration = QuicMigrationTelemetryState::default();
        let client = ripdpi_masque::MasqueClient::new(ripdpi_masque::config::MasqueConfig {
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::default(),
            url: "https://127.0.0.1:9/".to_string(),
            proxy_socket_addr: None,
            tcp_protocol: ripdpi_masque::config::MasqueTcpProtocol::Http3,
            use_http2_fallback: false,
            auth_mode: None,
            auth_token: None,
            client_certificate_chain_pem: None,
            client_private_key_pem: None,
            cloudflare_geohash_header: None,
            privacy_pass_provider_url: None,
            privacy_pass_provider_auth_token: None,
            tls_fingerprint_profile: "native_default".to_string(),
            root_certificate_pem: None,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            ech_config: None,
        })
        .expect("valid H3-selection config");
        let session = MasqueSession { client, migration: migration.clone() };

        let Err(error) = session.open_stream("example.com:443").await else {
            panic!("H3 TCP must fail closed");
        };

        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
        assert_eq!(migration.snapshot(), (Some("failed".to_string()), Some("masque_h3_tcp_unsupported".to_string())));
    }
}
