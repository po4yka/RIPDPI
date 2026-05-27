use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::MasqueSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let masque_config = build_masque_client_config(config)?;
    Ok(RelayBackend::Masque(PooledRelayBackend::new(
        MasqueSessionFactory { config: masque_config, migration: context.quic_migration.clone() },
        context.pool_config,
        Some(context.quic_migration.clone()),
    )))
}

fn build_masque_client_config(config: &ResolvedRelayRuntimeConfig) -> io::Result<ripdpi_masque::config::MasqueConfig> {
    build_masque_client_config_with_ech_lookup(config, ripdpi_masque::config::resolve_ech_config_via_encrypted_dns)
}

fn build_masque_client_config_with_ech_lookup(
    config: &ResolvedRelayRuntimeConfig,
    ech_lookup: impl FnOnce(&str) -> io::Result<Option<ripdpi_masque::config::OutboundEchConfig>>,
) -> io::Result<ripdpi_masque::config::MasqueConfig> {
    let RelayBackendConfig::Masque(masque) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected MASQUE config"));
    };
    let ech_config = resolve_masque_ech_config(&masque.url, ech_lookup)?;
    Ok(ripdpi_masque::config::MasqueConfig {
        url: masque.url.clone(),
        use_http2_fallback: masque.use_http2_fallback,
        auth_mode: masque.auth_mode.clone(),
        auth_token: masque.auth_token.clone(),
        client_certificate_chain_pem: masque.client_certificate_chain_pem.clone(),
        client_private_key_pem: masque.client_private_key_pem.clone(),
        cloudflare_geohash_header: masque.cloudflare_geohash_header.clone(),
        privacy_pass_provider_url: masque.privacy_pass_provider_url.clone(),
        privacy_pass_provider_auth_token: masque.privacy_pass_provider_auth_token.clone(),
        tls_fingerprint_profile: config.common.tls_fingerprint_profile.clone(),
        quic_bind_low_port: config.common.quic_bind_low_port,
        quic_migrate_after_handshake: config.common.quic_migrate_after_handshake,
        ech_config,
    })
}

fn resolve_masque_ech_config(
    url: &str,
    ech_lookup: impl FnOnce(&str) -> io::Result<Option<ripdpi_masque::config::OutboundEchConfig>>,
) -> io::Result<Option<ripdpi_masque::config::OutboundEchConfig>> {
    let parsed = url::Url::parse(url).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let host =
        parsed.host_str().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL is missing a host"))?;
    ech_lookup(host)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{CommonRelayConfig, MasqueRelayConfig, ResolvedRelayFinalmaskConfig};
    use std::sync::{Arc, Mutex};

    const BORING_ECH_CONFIG_LIST: &[u8] = &[
        0x00, 0x3e, 0xfe, 0x0d, 0x00, 0x3a, 0x00, 0x00, 0x20, 0x00, 0x20, 0xbb, 0x2f, 0x29, 0xe3, 0xe3, 0x05, 0x7e,
        0x04, 0x19, 0xd5, 0x2f, 0xc5, 0xf4, 0x41, 0x18, 0x77, 0x6f, 0x8d, 0xb6, 0x1c, 0xea, 0x4f, 0xdf, 0x76, 0x07,
        0x9b, 0x93, 0x60, 0x6c, 0x5a, 0x62, 0x48, 0x00, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00,
        0x07, 0x65, 0x63, 0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
    ];

    #[test]
    fn masque_builder_populates_ech_config_via_encrypted_dns_facade() {
        let lookup = FakeEchLookup::available("ech.com", BORING_ECH_CONFIG_LIST);
        let config = sample_masque_runtime_config();

        let masque = build_masque_client_config_with_ech_lookup(&config, |host| lookup.lookup(host))
            .expect("MASQUE client config");

        assert_eq!(
            masque.ech_config,
            Some(
                ripdpi_masque::config::OutboundEchConfig::new("ech.com", BORING_ECH_CONFIG_LIST.to_vec()).expect("ech")
            )
        );
        assert_eq!(lookup.requests(), vec!["masque.example".to_string()]);
    }

    fn sample_masque_runtime_config() -> ResolvedRelayRuntimeConfig {
        ResolvedRelayRuntimeConfig {
            common: CommonRelayConfig {
                enabled: true,
                profile_id: "default".to_string(),
                outbound_bind_ip: String::new(),
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
            backend: RelayBackendConfig::Masque(MasqueRelayConfig {
                url: "https://masque.example/".to_string(),
                use_http2_fallback: true,
                ..MasqueRelayConfig::default()
            }),
        }
    }

    #[derive(Clone)]
    struct FakeEchLookup {
        outcome: Option<ripdpi_masque::config::OutboundEchConfig>,
        requests: Arc<Mutex<Vec<String>>>,
    }

    impl FakeEchLookup {
        fn available(public_name: &str, config_list: &[u8]) -> Self {
            Self {
                outcome: Some(
                    ripdpi_masque::config::OutboundEchConfig::new(public_name, config_list.to_vec()).expect("ech"),
                ),
                requests: Arc::new(Mutex::new(Vec::new())),
            }
        }

        fn lookup(&self, host: &str) -> io::Result<Option<ripdpi_masque::config::OutboundEchConfig>> {
            self.requests.lock().expect("requests lock").push(host.to_string());
            Ok(self.outcome.clone())
        }

        fn requests(&self) -> Vec<String> {
            self.requests.lock().expect("requests lock").clone()
        }
    }
}
