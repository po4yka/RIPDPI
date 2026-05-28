use std::io;

use crate::backend::builder::builders::common::vless_reality_config;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{ChainRelayConfig, RelayBackendConfig, ResolvedChainRelayHopConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::ChainRelaySessionFactory;
use crate::telemetry::ChainHopTelemetryState;

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::ChainRelay(chain) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected chain relay config"));
    };
    let entry = chain_hop_vless_reality_config(chain, ChainHopRole::Entry, &config.common.tls_fingerprint_profile)?;
    let exit = chain_hop_vless_reality_config(chain, ChainHopRole::Exit, &config.common.tls_fingerprint_profile)?;

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

fn chain_hop_vless_reality_config(
    chain: &ChainRelayConfig,
    role: ChainHopRole,
    default_tls_fingerprint_profile: &str,
) -> io::Result<ripdpi_vless::config::VlessRealityConfig> {
    if let Some(hop) = match role {
        ChainHopRole::Entry => chain.entry.as_deref(),
        ChainHopRole::Exit => chain.exit.as_deref(),
    } {
        return resolved_hop_vless_reality_config(hop, role);
    }

    legacy_hop_vless_reality_config(chain, role, default_tls_fingerprint_profile)
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
