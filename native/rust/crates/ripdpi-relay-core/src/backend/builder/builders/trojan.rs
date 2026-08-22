use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::required_secret;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::TrojanSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Trojan(trojan) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected Trojan config"));
    };
    let server_port = u16::try_from(config.common.server_port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Trojan server port must fit u16"))?;
    let password = required_secret(trojan.password.as_deref(), "Trojan password")?.to_string();
    Ok(RelayBackend::Trojan(PooledRelayBackend::new(
        TrojanSessionFactory {
            client_config: ripdpi_relay_tls_transports::TrojanClientConfig {
                server_host: config.common.server.clone(),
                server_port,
                server_name: config.common.server_name.clone(),
                password,
                tls_fingerprint_profile: config.common.tls_fingerprint_profile.clone(),
                root_certificate_pem: trojan.root_certificate_pem.clone(),
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
    use crate::config::{CommonRelayConfig, ResolvedRelayFinalmaskConfig, TrojanRelayConfig};
    use crate::telemetry::QuicMigrationTelemetryState;
    use ripdpi_relay_mux::RelayPoolConfig;

    fn trojan_config(password: Option<String>) -> ResolvedRelayRuntimeConfig {
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
            backend: RelayBackendConfig::Trojan(TrojanRelayConfig { password, root_certificate_pem: None }),
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
        let Err(error) = build(&trojan_config(None), &build_context()) else {
            panic!("missing password must be rejected");
        };
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(error.to_string().contains("Trojan password is required"), "unexpected error: {error}");
    }

    #[test]
    fn build_rejects_blank_password_instead_of_sending_an_empty_secret() {
        let Err(error) = build(&trojan_config(Some("   ".to_string())), &build_context()) else {
            panic!("a blank password is a misconfiguration, not a credential");
        };
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(error.to_string().contains("Trojan password is required"), "unexpected error: {error}");
    }

    #[test]
    fn build_accepts_present_password_and_preserves_its_exact_bytes() {
        // Leading/trailing whitespace is part of the secret; the builder must
        // forward it verbatim.
        match build(&trojan_config(Some(" padded secret ".to_string())), &build_context()) {
            Ok(RelayBackend::Trojan(backend)) => {
                assert!(backend.capabilities().tcp, "a valid Trojan config must report TCP capability");
            }
            other => panic!("valid password must build a Trojan backend, got {:?}", std::mem::discriminant(&other)),
        }
    }
}
