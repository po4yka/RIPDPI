use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::required_secret;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::TuicSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::TuicV5(tuic) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected TUIC config"));
    };
    let uuid = required_secret(tuic.uuid.as_deref(), "TUIC UUID")?.to_string();
    let password = required_secret(tuic.password.as_deref(), "TUIC password")?.to_string();
    Ok(RelayBackend::Tuic(PooledRelayBackend::new(
        TuicSessionFactory {
            config: ripdpi_tuic::Config {
                server: config.common.server.clone(),
                server_port: config.common.server_port,
                server_name: config.common.server_name.clone(),
                uuid,
                password,
                zero_rtt: tuic.zero_rtt,
                congestion_control: tuic.congestion_control.clone(),
                udp_enabled: config.common.udp_enabled,
                quic_bind_low_port: config.common.quic_bind_low_port,
                quic_migrate_after_handshake: config.common.quic_migrate_after_handshake,
                socket_protection: context.socket_protection,
                outbound_bind_ip: context.outbound_bind_ip,
                // Relay profiles currently expose TUIC zero-RTT and congestion
                // control, but not TUIC keepalive. Keep it disabled until the
                // Kotlin/native relay config contract adds an explicit field.
                keepalive_interval_ms: 0,
                root_certificate_pem: None,
            },
            migration: context.quic_migration.clone(),
        },
        context.pool_config,
        Some(context.quic_migration.clone()),
    )))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{CommonRelayConfig, ResolvedRelayFinalmaskConfig, TuicRelayConfig};
    use crate::telemetry::QuicMigrationTelemetryState;
    use ripdpi_relay_mux::RelayPoolConfig;

    fn tuic_config(uuid: Option<String>, password: Option<String>) -> ResolvedRelayRuntimeConfig {
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
            backend: RelayBackendConfig::TuicV5(TuicRelayConfig {
                uuid,
                password,
                zero_rtt: false,
                congestion_control: "bbr".to_string(),
            }),
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
    fn build_rejects_missing_uuid() {
        let config = tuic_config(None, Some("tuic-secret".to_string()));
        let Err(error) = build(&config, &build_context()) else {
            panic!("missing UUID must be rejected");
        };
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(error.to_string().contains("TUIC UUID is required"), "unexpected error: {error}");
    }

    #[test]
    fn build_rejects_missing_password() {
        let config = tuic_config(Some("00000000-0000-0000-0000-000000000000".to_string()), None);
        let Err(error) = build(&config, &build_context()) else {
            panic!("missing password must be rejected");
        };
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(error.to_string().contains("TUIC password is required"), "unexpected error: {error}");
    }

    #[test]
    fn build_accepts_present_uuid_and_password() {
        let config =
            tuic_config(Some("00000000-0000-0000-0000-000000000000".to_string()), Some("tuic-secret".to_string()));
        match build(&config, &build_context()) {
            Ok(RelayBackend::Tuic(_)) => {}
            other => panic!("valid credentials must build a TUIC backend, got {:?}", std::mem::discriminant(&other)),
        }
    }
}
