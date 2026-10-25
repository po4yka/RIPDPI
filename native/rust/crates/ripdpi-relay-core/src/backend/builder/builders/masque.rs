use std::io;
use std::net::IpAddr;

use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::MasqueSessionFactory;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    // `build_backend` resolved the ECH bootstrap asynchronously beforehand.
    let masque_config = build_masque_client_config_with_ech_lookup(config, |_| Ok(context.masque_ech_config.clone()))?;
    Ok(RelayBackend::Masque(PooledRelayBackend::new(
        MasqueSessionFactory { config: masque_config, migration: context.quic_migration.clone() },
        context.pool_config,
        Some(context.quic_migration.clone()),
    )))
}

/// Resolve the MASQUE proxy's ECH config before the sync builder runs. The
/// lookup is async encrypted DNS, so it never blocks a runtime thread. Other
/// relay kinds get `None`. Config rejections come first, so a profile that
/// [`build`] rejects causes no network I/O.
///
/// # Cancel safety
///
/// Cancel-safe: dropping it drops the in-flight lookup; nothing is published.
pub(crate) async fn resolve_ech_before_build(
    config: &ResolvedRelayRuntimeConfig,
    outbound_bind_ip: Option<IpAddr>,
) -> io::Result<Option<ripdpi_masque::config::OutboundEchConfig>> {
    let RelayBackendConfig::Masque(masque) = &config.backend else {
        return Ok(None);
    };
    validate_masque_config(config)?;
    // ECH bootstrap sockets need the same protection policy as the carrier.
    ripdpi_masque::config::resolve_ech_config_via_encrypted_dns(
        &masque_url_host(&masque.url)?,
        outbound_bind_ip,
        config.common.socket_protection.into(),
    )
    .await
}

/// Config checks that must reject a profile before any ECH network I/O.
fn validate_masque_config(
    config: &ResolvedRelayRuntimeConfig,
) -> io::Result<(&crate::config::MasqueRelayConfig, ripdpi_masque::config::MasqueTcpProtocol)> {
    let RelayBackendConfig::Masque(masque) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected MASQUE config"));
    };
    let tcp_protocol = ripdpi_masque::config::MasqueTcpProtocol::from_wire(&masque.tcp_protocol)?;
    if tcp_protocol == ripdpi_masque::config::MasqueTcpProtocol::Http3 {
        return Err(crate::error::classified_error(
            ripdpi_failure_classifier::FailureClass::MasqueH3TcpUnsupported,
            io::ErrorKind::Unsupported,
            "HTTP/3 TCP requires RFC 9114 classic CONNECT; select HTTP/2",
        ));
    }
    if config.common.socket_protection == crate::config::SocketProtection::VpnRequired
        && masque.auth_mode.as_deref().is_some_and(|mode| mode.trim().eq_ignore_ascii_case("privacy_pass"))
    {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "MASQUE Privacy Pass provider fetch cannot protect its HTTP client sockets in VPN mode",
        ));
    }
    Ok((masque, tcp_protocol))
}

fn build_masque_client_config_with_ech_lookup(
    config: &ResolvedRelayRuntimeConfig,
    ech_lookup: impl FnOnce(&str) -> io::Result<Option<ripdpi_masque::config::OutboundEchConfig>>,
) -> io::Result<ripdpi_masque::config::MasqueConfig> {
    let (masque, tcp_protocol) = validate_masque_config(config)?;
    let ech_config = ech_lookup(&masque_url_host(&masque.url)?)?;
    Ok(ripdpi_masque::config::MasqueConfig {
        socket_protection: config.common.socket_protection.into(),
        url: masque.url.clone(),
        proxy_socket_addr: masque.proxy_socket_addr,
        tcp_protocol,
        use_http2_fallback: masque.use_http2_fallback,
        auth_mode: masque.auth_mode.clone(),
        auth_token: masque.auth_token.clone(),
        client_certificate_chain_pem: masque.client_certificate_chain_pem.clone(),
        client_private_key_pem: masque.client_private_key_pem.clone(),
        cloudflare_geohash_header: masque.cloudflare_geohash_header.clone(),
        privacy_pass_provider_url: masque.privacy_pass_provider_url.clone(),
        privacy_pass_provider_auth_token: masque.privacy_pass_provider_auth_token.clone(),
        tls_fingerprint_profile: config.common.tls_fingerprint_profile.clone(),
        root_certificate_pem: None,
        quic_bind_low_port: config.common.quic_bind_low_port,
        quic_migrate_after_handshake: config.common.quic_migrate_after_handshake,
        ech_config,
    })
}

fn masque_url_host(url: &str) -> io::Result<String> {
    let parsed = url::Url::parse(url).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    parsed
        .host_str()
        .map(str::to_owned)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL is missing a host"))
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

    #[test]
    fn masque_privacy_pass_vpn_rejection_precedes_ech_lookup() {
        let lookup = FakeEchLookup::available("ech.com", BORING_ECH_CONFIG_LIST);
        let mut config = sample_masque_runtime_config();
        config.common.socket_protection = crate::config::SocketProtection::VpnRequired;
        let RelayBackendConfig::Masque(masque) = &mut config.backend else {
            panic!("expected MASQUE config");
        };
        masque.auth_mode = Some("privacy_pass".to_string());
        masque.privacy_pass_provider_url = Some("https://provider.example/token".to_string());

        let error = build_masque_client_config_with_ech_lookup(&config, |host| lookup.lookup(host))
            .expect_err("unprotectable Privacy Pass provider must fail before ECH lookup");

        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
        assert!(lookup.requests().is_empty(), "VPN rejection must precede encrypted-DNS network I/O");
    }

    #[test]
    fn masque_h3_tcp_rejection_precedes_ech_lookup() {
        let lookup = FakeEchLookup::available("ech.com", BORING_ECH_CONFIG_LIST);
        let mut config = sample_masque_runtime_config();
        let RelayBackendConfig::Masque(masque) = &mut config.backend else {
            panic!("expected MASQUE config");
        };
        masque.tcp_protocol = "http3".to_string();

        let error = build_masque_client_config_with_ech_lookup(&config, |host| lookup.lookup(host))
            .expect_err("H3 TCP must fail before ECH lookup");

        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
        assert_eq!(
            Some(ripdpi_failure_classifier::FailureClass::MasqueH3TcpUnsupported),
            crate::error::relay_failure_class(&error),
            "the h3 rejection must carry its failure class as typed data, got: {error}"
        );
        assert!(error.to_string().contains("masque_h3_tcp_unsupported"));
        assert!(lookup.requests().is_empty(), "H3 TCP rejection must precede encrypted-DNS network I/O");
    }

    #[tokio::test]
    async fn async_ech_prefetch_rejects_h3_tcp_before_lookup() {
        let mut config = sample_masque_runtime_config();
        let RelayBackendConfig::Masque(masque) = &mut config.backend else {
            panic!("expected MASQUE config");
        };
        masque.tcp_protocol = "http3".to_string();

        let error = resolve_ech_before_build(&config, None).await.expect_err("H3 TCP must fail before ECH lookup");

        assert_eq!(
            Some(ripdpi_failure_classifier::FailureClass::MasqueH3TcpUnsupported),
            crate::error::relay_failure_class(&error),
        );
    }

    fn sample_masque_runtime_config() -> ResolvedRelayRuntimeConfig {
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
                local_socks_auth: None,
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
