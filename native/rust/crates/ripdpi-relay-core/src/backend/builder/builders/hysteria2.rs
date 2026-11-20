use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::{salamander_key, to_io_error};
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
    client_config.salamander_key = salamander_key(hysteria.salamander_key.as_deref())?;
    client_config.realm = realm(hysteria, client_config.port_hopping.is_some())?;
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

/// Rendezvous settings when the profile provisions a rendezvous URL. A realm
/// id alone (the bundle topology declares it, nothing provisions it) keeps
/// the direct dial to the server address.
fn realm(
    hysteria: &crate::config::Hysteria2RelayConfig,
    port_hopping: bool,
) -> io::Result<Option<ripdpi_hysteria2::RealmConfig>> {
    fn filled(value: &Option<String>) -> Option<&str> {
        value.as_deref().map(str::trim).filter(|value| !value.is_empty())
    }
    let realm_id = filled(&hysteria.realm_id);
    let Some(server_url) = filled(&hysteria.realm_server_url) else {
        if realm_id.is_some() {
            tracing::warn!("hysteria2 realm declared without a rendezvous URL; dialing the server address directly");
        }
        return Ok(None);
    };
    let invalid = |message: &str| io::Error::new(io::ErrorKind::InvalidInput, message.to_owned());
    let stun_servers: Vec<String> =
        hysteria.realm_stun_servers.iter().map(|server| server.trim().to_owned()).filter(|s| !s.is_empty()).collect();
    if stun_servers.is_empty() {
        return Err(invalid("Hysteria2 realm needs a STUN server"));
    }
    if port_hopping {
        return Err(invalid("Hysteria2 realm and port hopping are mutually exclusive"));
    }
    Ok(Some(ripdpi_hysteria2::RealmConfig {
        server_url: server_url.to_owned(),
        token: hysteria.realm_token.clone().unwrap_or_default(),
        realm_id: realm_id.ok_or_else(|| invalid("Hysteria2 realm needs a realm id"))?.to_owned(),
        stun_servers,
    }))
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
                local_socks_auth: None,
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
            masque_ech_config: None,
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
    fn build_rejects_a_salamander_key_shorter_than_four_bytes() {
        for (key, accepted) in [("abc", false), ("abcd", true), ("    ", true), ("   ", false), ("", true)] {
            let mut config = hysteria_config(None);
            let RelayBackendConfig::Hysteria2(hysteria) = &mut config.backend else { unreachable!() };
            hysteria.salamander_key = Some(key.to_owned());
            match build(&config, &build_context()) {
                Ok(_) => assert!(accepted, "key {key:?} must be rejected"),
                Err(error) => {
                    assert!(!accepted, "key {key:?} must be accepted");
                    assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
                }
            }
        }
    }

    #[test]
    fn realm_is_used_only_with_a_rendezvous_url_and_complete_settings() {
        let realm_with = |edit: &dyn Fn(&mut Hysteria2RelayConfig), port_hopping: bool| {
            let mut hysteria = Hysteria2RelayConfig {
                realm_id: Some("realm-a".into()),
                realm_server_url: Some("https://realm.example.com:8444".into()),
                realm_token: Some("test-token".into()),
                realm_stun_servers: vec!["stun.example.com:3478".into()],
                ..Hysteria2RelayConfig::default()
            };
            edit(&mut hysteria);
            realm(&hysteria, port_hopping)
        };
        let realm_of = |edit: &dyn Fn(&mut Hysteria2RelayConfig)| realm_with(edit, false);
        let complete = realm_of(&|_| {}).expect("complete realm").expect("realm mode");
        assert_eq!(complete.server_url, "https://realm.example.com:8444");
        assert_eq!(complete.token, "test-token");
        assert_eq!(complete.realm_id, "realm-a");
        assert_eq!(complete.stun_servers, ["stun.example.com:3478"]);

        assert!(
            realm_of(&|h| h.realm_server_url = None).expect("id alone is valid").is_none(),
            "id alone dials direct"
        );
        assert!(realm_of(&|h| h.realm_id = Some("  ".into())).is_err());
        assert!(realm_of(&|h| h.realm_stun_servers.clear()).is_err());
        assert!(realm_with(&|_| {}, true).is_err(), "realm excludes port hopping");
    }

    #[test]
    fn build_accepts_a_profile_without_a_pin() {
        assert!(matches!(build(&hysteria_config(None), &build_context()), Ok(RelayBackend::Hysteria2(_))));
        let blank = hysteria_config(Some("  ".to_string()));
        assert!(matches!(build(&blank, &build_context()), Ok(RelayBackend::Hysteria2(_))));
    }
}
