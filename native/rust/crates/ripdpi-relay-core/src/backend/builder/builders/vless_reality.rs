use std::io;

use crate::backend::builder::builders::common::{finalmask_config, vless_reality_config};
use crate::backend::builder::builders::BackendBuilder;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayKind, ResolvedRelayRuntimeConfig};
use crate::protocols::{VlessRealitySessionFactory, XhttpSessionFactory, XhttpSessionMode};

pub(crate) const XHTTP_BUILDER: BackendBuilder = BackendBuilder::new(supports_xhttp, build_xhttp);
pub(crate) const BUILDER: BackendBuilder = BackendBuilder::new(supports, build);

fn supports_xhttp(config: &ResolvedRelayRuntimeConfig) -> bool {
    matches!(RelayKind::from_config(config), RelayKind::VlessReality { xhttp: true })
}

fn supports(config: &ResolvedRelayRuntimeConfig) -> bool {
    matches!(RelayKind::from_config(config), RelayKind::VlessReality { xhttp: false })
}

fn build_xhttp(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    Ok(RelayBackend::Xhttp(PooledRelayBackend::new(
        XhttpSessionFactory {
            mode: XhttpSessionMode::Reality(ripdpi_xhttp::XhttpRealityConfig {
                vless: vless_reality_config(
                    &config.server,
                    config.server_port,
                    config.vless_uuid.as_deref().unwrap_or_default(),
                    &config.server_name,
                    &config.reality_public_key,
                    &config.reality_short_id,
                    &config.tls_fingerprint_profile,
                )?,
                path: config.xhttp_path.clone(),
                host: if config.xhttp_host.trim().is_empty() {
                    None
                } else {
                    Some(config.xhttp_host.trim().to_owned())
                },
                bind_ip: context.outbound_bind_ip,
                xmux: ripdpi_xhttp::XmuxConfig::default(),
                finalmask: finalmask_config(&config.finalmask),
            }),
        },
        context.pool_config,
        None,
    )))
}

fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    Ok(RelayBackend::VlessReality(PooledRelayBackend::new(
        VlessRealitySessionFactory {
            config: vless_reality_config(
                &config.server,
                config.server_port,
                config.vless_uuid.as_deref().unwrap_or_default(),
                &config.server_name,
                &config.reality_public_key,
                &config.reality_short_id,
                &config.tls_fingerprint_profile,
            )?,
            outbound_bind_ip: context.outbound_bind_ip,
        },
        context.pool_config,
        None,
    )))
}
