use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::required_secret;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::ShadowTlsSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::ShadowTlsV3(shadowtls) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected ShadowTLS config"));
    };
    let inner = shadowtls
        .inner
        .as_ref()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing ShadowTLS inner relay config"))?;
    if inner.kind != "vless_reality" || inner.vless_transport != "reality_tcp" {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "ShadowTLS supports only a VLESS Reality TCP inner relay",
        ));
    }
    let password = required_secret(shadowtls.password.as_deref(), "ShadowTLS password")?.to_string();
    Ok(RelayBackend::ShadowTls(PooledRelayBackend::new(
        ShadowTlsSessionFactory {
            client_config: ripdpi_relay_tls_transports::ShadowTlsClientConfig {
                password,
                server_name: config.common.server_name.clone(),
                inner_profile_id: shadowtls.inner_profile_id.clone(),
                socket_protection: context.socket_protection,
                outbound_bind_ip: context.outbound_bind_ip,
            },
            outer_server: config.common.server.clone(),
            outer_server_port: config.common.server_port,
            inner: ripdpi_relay_tls_transports::ShadowTlsInnerConfig {
                kind: inner.kind.clone(),
                server: inner.server.clone(),
                server_port: inner.server_port,
                server_name: inner.server_name.clone(),
                reality_public_key: inner.reality_public_key.clone(),
                reality_short_id: inner.reality_short_id.clone(),
                vless_transport: inner.vless_transport.clone(),
                vless_flow: inner.vless_flow.clone(),
                xhttp_mode: inner.xhttp_mode.clone(),
                vless_uuid: inner.vless_uuid.clone(),
                tls_fingerprint_profile: inner.tls_fingerprint_profile.clone(),
            },
        },
        context.pool_config,
        None,
    )))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{
        CommonRelayConfig, ResolvedRelayFinalmaskConfig, ResolvedShadowTlsInnerRelayConfig, ShadowTlsRelayConfig,
    };
    use crate::telemetry::QuicMigrationTelemetryState;
    use ripdpi_relay_mux::RelayPoolConfig;

    fn shadowtls_config(password: Option<String>) -> ResolvedRelayRuntimeConfig {
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
                udp_enabled: false,
                tcp_fallback_enabled: true,
                quic_bind_low_port: false,
                quic_migrate_after_handshake: false,
                tls_fingerprint_profile: "chrome_stable".to_string(),
                finalmask: ResolvedRelayFinalmaskConfig::default(),
            },
            backend: RelayBackendConfig::ShadowTlsV3(ShadowTlsRelayConfig {
                password,
                inner_profile_id: "inner-vless".to_string(),
                inner: Some(ResolvedShadowTlsInnerRelayConfig {
                    kind: "vless_reality".to_string(),
                    profile_id: "inner-vless".to_string(),
                    server: "inner.example".to_string(),
                    server_port: 443,
                    server_name: "inner.example".to_string(),
                    reality_public_key: "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=".to_string(),
                    reality_short_id: String::new(),
                    vless_flow: "xtls-rprx-vision".to_string(),
                    vless_transport: "reality_tcp".to_string(),
                    xhttp_mode: "auto".to_string(),
                    vless_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
                    tls_fingerprint_profile: "chrome_stable".to_string(),
                }),
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
        let Err(error) = build(&shadowtls_config(None), &build_context()) else {
            panic!("missing password must be rejected");
        };
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(error.to_string().contains("ShadowTLS password is required"), "unexpected error: {error}");
    }

    #[test]
    fn build_accepts_present_password() {
        match build(&shadowtls_config(Some("shadowtls-secret".to_string())), &build_context()) {
            Ok(RelayBackend::ShadowTls(_)) => {}
            other => panic!("valid password must build a ShadowTLS backend, got {:?}", std::mem::discriminant(&other)),
        }
    }
}
