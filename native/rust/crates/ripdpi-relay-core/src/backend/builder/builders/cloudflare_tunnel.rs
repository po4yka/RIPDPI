use std::io;

use crate::backend::builder::builders::common::{finalmask_config, invalid_input};
use crate::backend::builder::builders::BackendBuilder;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayKind, ResolvedRelayRuntimeConfig};
use crate::protocols::{XhttpSessionFactory, XhttpSessionMode};

pub(crate) const BUILDER: BackendBuilder = BackendBuilder::new(supports, build);

fn supports(config: &ResolvedRelayRuntimeConfig) -> bool {
    matches!(RelayKind::from_config(config), RelayKind::CloudflareTunnel)
}

fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let mut tls = ripdpi_xhttp::XhttpTlsConfig::from_strings(
        &config.server,
        config.server_port,
        &config.server_name,
        config.vless_uuid.as_deref().unwrap_or_default(),
        &config.xhttp_path,
        &config.xhttp_host,
        &config.tls_fingerprint_profile,
    )
    .map_err(invalid_input)?;
    tls.bind_ip = context.outbound_bind_ip;
    tls.finalmask = finalmask_config(&config.finalmask);

    Ok(RelayBackend::Xhttp(PooledRelayBackend::new(
        XhttpSessionFactory { mode: XhttpSessionMode::Tls(tls) },
        context.pool_config,
        None,
    )))
}
