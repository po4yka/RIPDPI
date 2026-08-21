use std::io;
use std::sync::Arc;

use ripdpi_failure_classifier::FailureClass;
use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};
use ripdpi_tuic::{TuicFailureKind, TuicHandshakeError};

use crate::telemetry::{QuicMigrationTelemetryState, sync_quic_migration_state};

/// Map a `ripdpi-tuic` handshake error into a relay error carrying the
/// `FailureClass::TuicVersionUnsupported` token. A v4 server rejects this
/// v5-only client; without this mapping the failure surfaces as a generic
/// protocol error. The token plus the typed error's actionable Display flow
/// through the SOCKS handshake-error telemetry (`record_handshake_error` →
/// `last_handshake_error`) to the service diagnostic surface, satisfying the
/// "v4-server attempts produce a user-actionable diagnostic" contract in
/// `docs/architecture/tuic-v4-policy.md`. Non-version errors pass through
/// unchanged.
fn classify_tuic_handshake_error(error: io::Error) -> io::Error {
    let is_version_unsupported = error
        .get_ref()
        .and_then(|inner| inner.downcast_ref::<TuicHandshakeError>())
        .is_some_and(|handshake| handshake.kind() == TuicFailureKind::VersionUnsupported);
    if !is_version_unsupported {
        return error;
    }
    // The discriminator downstream consumes used to be the leading token
    // string (`tuic_version_unsupported`); it now travels as typed data on the
    // error payload via `crate::error::relay_failure_class`. Display text
    // stays byte-identical for the telemetry surface.
    crate::error::classified_error(FailureClass::TuicVersionUnsupported, io::ErrorKind::Unsupported, error.to_string())
}

#[derive(Clone)]
pub(crate) struct TuicSessionFactory {
    pub(crate) config: ripdpi_tuic::Config,
    pub(crate) migration: QuicMigrationTelemetryState,
}

pub(crate) struct TuicSession {
    client: ripdpi_tuic::TuicClient,
    pub(crate) migration: QuicMigrationTelemetryState,
}

impl RelaySession for TuicSession {
    type Stream = ripdpi_tuic::DuplexStream;
    type Datagram = ripdpi_tuic::UdpSession;
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        let stream = self.client.tcp_connect(target).await.map_err(classify_tuic_handshake_error)?;
        sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
        Ok(stream)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        let session = self.client.udp_session().await.map_err(classify_tuic_handshake_error)?;
        sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
        Ok(session)
    }
}

impl RelaySessionFactory for TuicSessionFactory {
    type Session = TuicSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: self.config.udp_enabled, reusable: true }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let config = self.config.clone();
        let migration = self.migration.clone();
        let client = ripdpi_tuic::TuicClient::connect(config).await.map_err(classify_tuic_handshake_error)?;
        sync_quic_migration_state(&migration, client.quic_migration_snapshot());
        Ok(Arc::new(TuicSession { client, migration }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_unsupported_maps_to_failure_class_token_and_actionable_message() {
        // The runtime path: a typed TUIC version error becomes a relay error
        // whose recorded handshake-error string carries
        // FailureClass::TuicVersionUnsupported and a server-upgrade hint.
        let tuic_error = io::Error::other(TuicHandshakeError::version_unsupported());
        let mapped = classify_tuic_handshake_error(tuic_error);

        assert_eq!(mapped.kind(), io::ErrorKind::Unsupported);
        let message = mapped.to_string();
        assert!(
            message.starts_with(FailureClass::TuicVersionUnsupported.as_str()),
            "telemetry string must lead with the failure-class token, got: {message}",
        );
        assert!(message.contains("v5"), "diagnostic must be user-actionable (server upgrade), got: {message}");
    }

    #[test]
    fn non_version_errors_pass_through_unchanged() {
        let original = io::Error::new(io::ErrorKind::ConnectionReset, "upstream reset");
        let mapped = classify_tuic_handshake_error(original);
        assert_eq!(mapped.kind(), io::ErrorKind::ConnectionReset);
        assert_eq!(mapped.to_string(), "upstream reset");
    }

    #[test]
    fn factory_reports_udp_capability_from_configuration() {
        let config = ripdpi_tuic::Config {
            server: "127.0.0.1".to_owned(),
            server_port: 443,
            server_name: "localhost".to_owned(),
            uuid: String::new(),
            password: String::new(),
            zero_rtt: false,
            congestion_control: "bbr".to_owned(),
            udp_enabled: false,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            keepalive_interval_ms: 0,
            root_certificate_pem: None,
        };
        let factory = TuicSessionFactory { config, migration: QuicMigrationTelemetryState::default() };

        assert!(!factory.capabilities().udp);
    }
    #[test]
    fn classified_handshake_error_exposes_the_failure_class_for_downcast() {
        use crate::error::relay_failure_class;

        let mapped = super::classify_tuic_handshake_error(io::Error::other(TuicHandshakeError::version_unsupported()));

        assert_eq!(
            Some(FailureClass::TuicVersionUnsupported),
            relay_failure_class(&mapped),
            "the failure class must travel as typed data, not only as a display-token prefix"
        );
    }
}
