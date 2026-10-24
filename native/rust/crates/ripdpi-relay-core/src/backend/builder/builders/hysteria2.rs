use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::to_io_error;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::Hysteria2SessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Hysteria2(hysteria) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected Hysteria2 config"));
    };
    let password = hysteria
        .password
        .as_ref()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing Hysteria2 password"))?;
    let mut client_config = ripdpi_hysteria2::Config::from_parts(
        password.clone(),
        &config.common.server,
        config.common.server_port,
        config.common.server_name.clone(),
    )
    .map_err(to_io_error)?;
    client_config.port_hopping = ripdpi_hysteria2::PortHoppingConfig::from_wire(
        hysteria.port_hop_ports.as_deref(),
        hysteria.port_hop_interval_millis,
        hysteria.port_hop_interval_max_millis,
    )
    .map_err(to_io_error)?;
    client_config.salamander_key = hysteria.salamander_key.as_ref().filter(|value| !value.trim().is_empty()).cloned();
    client_config.insecure = hysteria.insecure;
    // Fail closed on a malformed pin: ignoring it would turn an `insecure`
    // profile into one that accepts any certificate.
    client_config.pin_sha256 = hysteria
        .pin_sha256
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .map(ripdpi_hysteria2::parse_certificate_pin_sha256)
        .transpose()
        .map_err(to_io_error)?;
    client_config.quic_bind_low_port = config.common.quic_bind_low_port;
    client_config.quic_migrate_after_handshake = config.common.quic_migrate_after_handshake;
    client_config.socket_protection = context.socket_protection;

    Ok(RelayBackend::Hysteria2(PooledRelayBackend::new(
        Hysteria2SessionFactory { config: client_config, migration: context.quic_migration.clone() },
        context.pool_config,
        Some(context.quic_migration.clone()),
    )))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{CommonRelayConfig, Hysteria2RelayConfig, ResolvedRelayFinalmaskConfig};
    use crate::telemetry::QuicMigrationTelemetryState;
    use ripdpi_relay_mux::RelayPoolConfig;

    fn hysteria_config(pin_sha256: Option<String>) -> ResolvedRelayRuntimeConfig {
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
                local_socks_port: 1080,
                udp_enabled: true,
                tcp_fallback_enabled: true,
                quic_bind_low_port: false,
                quic_migrate_after_handshake: false,
                tls_fingerprint_profile: "chrome_stable".to_string(),
                finalmask: ResolvedRelayFinalmaskConfig::default(),
            },
            backend: RelayBackendConfig::Hysteria2(Hysteria2RelayConfig {
                password: Some("hysteria-secret".to_string()),
                salamander_key: None,
                insecure: true,
                pin_sha256,
                ..Hysteria2RelayConfig::default()
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
    fn build_rejects_invalid_hopping_before_any_network_access() {
        for (ports, interval, maximum) in [
            (Some("0"), None, None),
            (Some("443,8443"), Some(4999), None),
            (Some("443"), Some(10000), Some(5000)),
            (None, Some(5000), None),
        ] {
            let mut config = hysteria_config(None);
            let RelayBackendConfig::Hysteria2(hysteria) = &mut config.backend else { unreachable!() };
            hysteria.port_hop_ports = ports.map(str::to_owned);
            hysteria.port_hop_interval_millis = interval;
            hysteria.port_hop_interval_max_millis = maximum;
            assert!(build(&config, &build_context()).is_err());
        }
    }

    #[test]
    fn build_rejects_a_malformed_certificate_pin() {
        let config = hysteria_config(Some("not-a-sha256".to_string()));
        let Err(error) = build(&config, &build_context()) else {
            panic!("a malformed pin must fail the build instead of being ignored");
        };
        assert!(error.to_string().contains("certificate pin"), "unexpected error: {error}");
    }

    #[test]
    fn build_accepts_a_valid_certificate_pin() {
        let config = hysteria_config(Some("AB:".repeat(31) + "AB"));
        assert!(matches!(build(&config, &build_context()), Ok(RelayBackend::Hysteria2(_))));
    }

    #[test]
    fn build_accepts_a_profile_without_a_pin() {
        assert!(matches!(build(&hysteria_config(None), &build_context()), Ok(RelayBackend::Hysteria2(_))));
        let blank = hysteria_config(Some("  ".to_string()));
        assert!(matches!(build(&blank, &build_context()), Ok(RelayBackend::Hysteria2(_))));
    }
}
