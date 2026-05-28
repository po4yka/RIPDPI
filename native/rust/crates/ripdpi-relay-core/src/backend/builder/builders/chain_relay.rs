use std::io;
use std::net::IpAddr;

use crate::backend::builder::builders::common::vless_reality_config;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{ChainRelayConfig, RelayBackendConfig, ResolvedChainRelayHopConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::{ChainEntryConnector, ChainExitConnector, ChainRelaySessionFactory};
use crate::telemetry::ChainHopTelemetryState;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::ChainRelay(chain) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected chain relay config"));
    };
    let entry = chain_entry_connector(chain, &config.common.tls_fingerprint_profile, context.outbound_bind_ip)?;
    let exit = chain_exit_connector(chain, &config.common.tls_fingerprint_profile)?;

    let telemetry = ChainHopTelemetryState::default();
    let backend = PooledRelayBackend::new(
        ChainRelaySessionFactory {
            entry,
            exit,
            outbound_bind_ip: context.outbound_bind_ip,
            telemetry: telemetry.clone(),
        },
        context.pool_config,
        None,
    );

    Ok(RelayBackend::ChainRelay { backend, telemetry })
}

#[derive(Clone, Copy)]
enum ChainHopRole {
    Entry,
    Exit,
}

impl ChainHopRole {
    fn label(self) -> &'static str {
        match self {
            Self::Entry => "entry",
            Self::Exit => "exit",
        }
    }
}

fn chain_entry_connector(
    chain: &ChainRelayConfig,
    default_tls_fingerprint_profile: &str,
    outbound_bind_ip: Option<IpAddr>,
) -> io::Result<ChainEntryConnector> {
    let Some(hop) = chain.entry.as_deref() else {
        return legacy_hop_vless_reality_config(chain, ChainHopRole::Entry, default_tls_fingerprint_profile)
            .map(ChainEntryConnector::VlessReality);
    };
    match hop.kind.as_str() {
        "vless_reality" => {
            resolved_hop_vless_reality_config(hop, ChainHopRole::Entry).map(ChainEntryConnector::VlessReality)
        }
        "masque" => resolved_hop_masque_config(hop, ChainHopRole::Entry).map(ChainEntryConnector::Masque),
        "trojan" => resolved_hop_trojan_config(hop, ChainHopRole::Entry).map(ChainEntryConnector::Trojan),
        "shadowsocks" => resolved_hop_shadowsocks_factory(hop, ChainHopRole::Entry, outbound_bind_ip)
            .map(ChainEntryConnector::Shadowsocks),
        other => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("chain entry: resolved hop kind {other} is not supported by chain relay"),
        )),
    }
}

fn chain_exit_connector(
    chain: &ChainRelayConfig,
    default_tls_fingerprint_profile: &str,
) -> io::Result<ChainExitConnector> {
    let Some(hop) = chain.exit.as_deref() else {
        return legacy_hop_vless_reality_config(chain, ChainHopRole::Exit, default_tls_fingerprint_profile)
            .map(ChainExitConnector::VlessReality);
    };
    match hop.kind.as_str() {
        "vless_reality" => {
            resolved_hop_vless_reality_config(hop, ChainHopRole::Exit).map(ChainExitConnector::VlessReality)
        }
        "masque" => resolved_hop_masque_config(hop, ChainHopRole::Exit).map(ChainExitConnector::Masque),
        "trojan" => resolved_hop_trojan_config(hop, ChainHopRole::Exit).map(ChainExitConnector::Trojan),
        "shadowsocks" => {
            resolved_hop_shadowsocks_factory(hop, ChainHopRole::Exit, None).map(ChainExitConnector::Shadowsocks)
        }
        other => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("chain exit: resolved hop kind {other} is not supported by chain relay"),
        )),
    }
}

fn resolved_hop_vless_reality_config(
    hop: &ResolvedChainRelayHopConfig,
    role: ChainHopRole,
) -> io::Result<ripdpi_vless::config::VlessRealityConfig> {
    let label = role.label();
    if hop.kind != "vless_reality" {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("chain {label}: resolved hop kind {} is not supported by the fixed VLESS chain runtime", hop.kind),
        ));
    }
    if hop.vless_transport != "reality_tcp" {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!(
                "chain {label}: resolved VLESS hop transport {} is not supported by chain relay",
                hop.vless_transport
            ),
        ));
    }
    vless_reality_config(
        &hop.server,
        hop.server_port,
        hop.vless_uuid.as_deref().unwrap_or_default(),
        &hop.server_name,
        &hop.reality_public_key,
        &hop.reality_short_id,
        &hop.tls_fingerprint_profile,
    )
    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: {error}")))
}

fn resolved_hop_masque_config(
    hop: &ResolvedChainRelayHopConfig,
    role: ChainHopRole,
) -> io::Result<ripdpi_masque::config::MasqueConfig> {
    let label = role.label();
    if hop.masque_url.trim().is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: MASQUE URL is required")));
    }
    Ok(ripdpi_masque::config::MasqueConfig {
        url: hop.masque_url.clone(),
        proxy_socket_addr: None,
        use_http2_fallback: hop.masque_use_http2_fallback,
        auth_mode: hop.masque_auth_mode.clone(),
        auth_token: hop.masque_auth_token.clone(),
        client_certificate_chain_pem: hop.masque_client_certificate_chain_pem.clone(),
        client_private_key_pem: hop.masque_client_private_key_pem.clone(),
        cloudflare_geohash_header: hop.masque_cloudflare_geohash_header.clone(),
        privacy_pass_provider_url: hop.masque_privacy_pass_provider_url.clone(),
        privacy_pass_provider_auth_token: hop.masque_privacy_pass_provider_auth_token.clone(),
        tls_fingerprint_profile: hop.tls_fingerprint_profile.clone(),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    })
}

fn resolved_hop_trojan_config(
    hop: &ResolvedChainRelayHopConfig,
    role: ChainHopRole,
) -> io::Result<ripdpi_relay_tls_transports::TrojanClientConfig> {
    let label = role.label();
    let server_port = u16::try_from(hop.server_port).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: Trojan server port must fit u16"))
    })?;
    Ok(ripdpi_relay_tls_transports::TrojanClientConfig {
        server_host: hop.server.clone(),
        server_port,
        server_name: hop.server_name.clone(),
        password: hop.trojan_password.clone().unwrap_or_default(),
        tls_fingerprint_profile: hop.tls_fingerprint_profile.clone(),
        root_certificate_pem: hop.trojan_root_certificate_pem.clone(),
    })
}

fn resolved_hop_shadowsocks_factory(
    hop: &ResolvedChainRelayHopConfig,
    role: ChainHopRole,
    outbound_bind_ip: Option<IpAddr>,
) -> io::Result<ripdpi_relay_tls_transports::ShadowsocksSessionFactory> {
    let label = role.label();
    let server_port = u16::try_from(hop.server_port).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: Shadowsocks server port must fit u16"))
    })?;
    let method = hop.shadowsocks_method.clone().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: Shadowsocks method is required"))
    })?;
    let password = hop.shadowsocks_password.clone().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: Shadowsocks password is required"))
    })?;
    ripdpi_relay_tls_transports::ShadowsocksSessionFactory::new(
        hop.server.clone(),
        server_port,
        method,
        password,
        outbound_bind_ip,
    )
    .map_err(|error| io::Error::new(error.kind(), format!("chain {label}: {error}")))
}

fn legacy_hop_vless_reality_config(
    chain: &ChainRelayConfig,
    role: ChainHopRole,
    default_tls_fingerprint_profile: &str,
) -> io::Result<ripdpi_vless::config::VlessRealityConfig> {
    let label = role.label();
    let (server, port, uuid, server_name, public_key, short_id) = match role {
        ChainHopRole::Entry => (
            &chain.entry_server,
            chain.entry_port,
            chain.entry_uuid.as_deref().unwrap_or_default(),
            &chain.entry_server_name,
            &chain.entry_public_key,
            &chain.entry_short_id,
        ),
        ChainHopRole::Exit => (
            &chain.exit_server,
            chain.exit_port,
            chain.exit_uuid.as_deref().unwrap_or_default(),
            &chain.exit_server_name,
            &chain.exit_public_key,
            &chain.exit_short_id,
        ),
    };
    vless_reality_config(server, port, uuid, server_name, public_key, short_id, default_tls_fingerprint_profile)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: {error}")))
}
