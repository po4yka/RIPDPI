use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::{finalmask_config, vless_reality_config};
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, RelayKind, ResolvedRelayRuntimeConfig};
use crate::protocols::{VlessRealitySessionFactory, XhttpSessionFactory, XhttpSessionMode};

/// Build the VLESS Reality relay backend, dispatching by transport sub-mode.
///
/// The single `vless_reality` `relay_kind` splits across two `RelayBackend`
/// variants: the `xhttp` transport routes through the multiplexed `Xhttp`
/// backend, classic Reality through the TCP `VlessReality` backend. This
/// sub-mode branch deliberately stays explicit inside the builder — it is not a
/// `relay_kind`-keyed fact and so is not a `RelayTransportDescriptor` field.
pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    if matches!(RelayKind::from_config(config), RelayKind::VlessReality { xhttp: true }) {
        build_xhttp(config, context)
    } else {
        build_reality(config, context)
    }
}

fn build_xhttp(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::VlessReality(vless) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected VLESS Reality config"));
    };
    Ok(RelayBackend::Xhttp(PooledRelayBackend::new(
        XhttpSessionFactory {
            mode: XhttpSessionMode::Reality(ripdpi_xhttp::XhttpRealityConfig {
                vless: vless_reality_config(
                    &config.common.server,
                    config.common.server_port,
                    vless.uuid.as_deref().unwrap_or_default(),
                    &config.common.server_name,
                    &vless.reality_public_key,
                    &vless.reality_short_id,
                    &config.common.tls_fingerprint_profile,
                )?,
                path: vless.xhttp_path.clone(),
                host: if vless.xhttp_host.trim().is_empty() { None } else { Some(vless.xhttp_host.trim().to_owned()) },
                bind_ip: context.outbound_bind_ip,
                xmux: ripdpi_xhttp::XmuxConfig::default(),
                finalmask: finalmask_config(&config.common.finalmask),
                protocol_mode: ripdpi_xhttp::XhttpProtocolMode::default(),
            }),
        },
        context.pool_config,
        None,
    )))
}

fn build_reality(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::VlessReality(vless) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected VLESS Reality config"));
    };
    Ok(RelayBackend::VlessReality(PooledRelayBackend::new(
        VlessRealitySessionFactory {
            config: vless_reality_config(
                &config.common.server,
                config.common.server_port,
                vless.uuid.as_deref().unwrap_or_default(),
                &config.common.server_name,
                &vless.reality_public_key,
                &vless.reality_short_id,
                &config.common.tls_fingerprint_profile,
            )?,
            outbound_bind_ip: context.outbound_bind_ip,
        },
        context.pool_config,
        None,
    )))
}
