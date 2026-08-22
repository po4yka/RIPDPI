use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::required_secret;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::AnyTlsSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::AnyTls(anytls) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected AnyTLS config"));
    };
    let server_port = u16::try_from(config.common.server_port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "AnyTLS server port must fit u16"))?;
    let password = required_secret(anytls.password.as_deref(), "AnyTLS password")?.to_string();
    Ok(RelayBackend::AnyTls(PooledRelayBackend::new(
        AnyTlsSessionFactory {
            client_config: ripdpi_relay_tls_transports::AnyTlsClientConfig {
                server_host: config.common.server.clone(),
                server_port,
                server_name: config.common.server_name.clone(),
                password,
                tls_fingerprint_profile: config.common.tls_fingerprint_profile.clone(),
                root_certificate_pem: anytls.root_certificate_pem.clone(),
                client_name: "ripdpi-anytls/0.1.0".to_string(),
                socket_protection: context.socket_protection,
                outbound_bind_ip: context.outbound_bind_ip,
            },
        },
        context.pool_config,
        None,
    )))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{AnyTlsRelayConfig, CommonRelayConfig, ResolvedRelayFinalmaskConfig};
    use crate::telemetry::QuicMigrationTelemetryState;
    use ripdpi_relay_mux::RelayPoolConfig;

    fn anytls_config(password: Option<String>) -> ResolvedRelayRuntimeConfig {
        ResolvedRelayRuntimeConfig {
            common: CommonRelayConfig {
                enabled: true,
                profile_id: "default".to_string(),
                outbound_bind_ip: String::new(),
                socket_protection: crate::config::SocketProtection::Inactive,
                server: "relay.example".to_string(),
                server_port: 443,
                server_name: "relay.example".to_string(),
                local_socks_host: "127.0.0.1".to_string(),
                local_socks_port: 10_80,
                udp_enabled: true,
                tcp_fallback_enabled: true,
                quic_bind_low_port: false,
                quic_migrate_after_handshake: false,
                tls_fingerprint_profile: "chrome_stable".to_string(),
                finalmask: ResolvedRelayFinalmaskConfig::default(),
            },
            backend: RelayBackendConfig::AnyTls(AnyTlsRelayConfig { password, root_certificate_pem: None }),
        }
    }

    fn build_context() -> BuildContext {
        BuildContext {
            outbound_bind_ip: None,
            socket_protector: None,
            socket_protection: ripdpi_relay_tls_transports::SocketProtectionPolicy::Inactive,
            pool_config: RelayPoolConfig::default(),
            quic_migration: QuicMigrationTelemetryState::default(),
        }
    }

    #[test]
    fn build_rejects_missing_password() {
        let Err(error) = build(&anytls_config(None), &build_context()) else {
            panic!("missing password must be rejected");
        };
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(error.to_string().contains("AnyTLS password is required"), "unexpected error: {error}");
    }

    #[test]
    fn build_rejects_blank_password_instead_of_sending_an_empty_secret() {
        let Err(error) = build(&anytls_config(Some(String::new())), &build_context()) else {
            panic!("an empty password is a misconfiguration, not a credential");
        };
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(error.to_string().contains("AnyTLS password is required"), "unexpected error: {error}");
    }
}
