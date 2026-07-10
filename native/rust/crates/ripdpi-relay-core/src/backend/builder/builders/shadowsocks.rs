use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::ShadowsocksSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Shadowsocks(shadowsocks) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected Shadowsocks config"));
    };
    let server_port = u16::try_from(config.common.server_port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Shadowsocks server port must fit u16"))?;
    // Reject a missing password rather than silently deriving a key from an empty
    // string: for legacy AEAD ciphers `unwrap_or_default()` would yield a weakened
    // key via EVP_BytesToKey(&[], ...). Mirrors the chain-relay builder
    // (chain_relay.rs) which already hard-rejects a `None` Shadowsocks password.
    let password = shadowsocks
        .password
        .clone()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "Shadowsocks password is required"))?;
    let factory = ShadowsocksSessionFactory::new(
        config.common.server.clone(),
        server_port,
        shadowsocks.method.clone(),
        password,
        context.outbound_bind_ip,
        context.socket_protection,
    )?;
    Ok(RelayBackend::Shadowsocks(PooledRelayBackend::new(factory, context.pool_config, None)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{CommonRelayConfig, ResolvedRelayFinalmaskConfig, ShadowsocksRelayConfig};
    use crate::telemetry::QuicMigrationTelemetryState;
    use ripdpi_relay_mux::RelayPoolConfig;

    fn shadowsocks_config(password: Option<String>) -> ResolvedRelayRuntimeConfig {
        ResolvedRelayRuntimeConfig {
            common: CommonRelayConfig {
                enabled: true,
                profile_id: "default".to_string(),
                outbound_bind_ip: String::new(),
                socket_protection: crate::config::SocketProtection::Inactive,
                server: "relay.example".to_string(),
                server_port: 8388,
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
            backend: RelayBackendConfig::Shadowsocks(ShadowsocksRelayConfig {
                method: "aes-256-gcm".to_string(),
                password,
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
    fn build_rejects_missing_password() {
        match build(&shadowsocks_config(None), &build_context()) {
            Ok(_) => panic!("missing password must be rejected"),
            Err(error) => {
                assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
                assert!(error.to_string().contains("Shadowsocks password is required"), "unexpected error: {error}");
            }
        }
    }

    #[test]
    fn build_accepts_present_password() {
        if let Err(error) = build(&shadowsocks_config(Some("correct horse".to_string())), &build_context()) {
            panic!("valid password must build: {error}");
        }
    }
}
