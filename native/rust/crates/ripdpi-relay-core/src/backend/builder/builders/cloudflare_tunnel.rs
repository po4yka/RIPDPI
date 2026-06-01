use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::{finalmask_config, invalid_input};
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::{XhttpSessionFactory, XhttpSessionMode};

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::CloudflareTunnel(cloudflare) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected Cloudflare tunnel config"));
    };
    let mut tls = ripdpi_xhttp::XhttpTlsConfig::from_strings(
        &config.common.server,
        config.common.server_port,
        &config.common.server_name,
        cloudflare.uuid.as_deref().unwrap_or_default(),
        &cloudflare.xhttp_path,
        &cloudflare.xhttp_host,
        &config.common.tls_fingerprint_profile,
    )
    .map_err(invalid_input)?;
    tls.bind_ip = context.outbound_bind_ip;
    tls.finalmask = finalmask_config(&config.common.finalmask);

    Ok(RelayBackend::Xhttp(PooledRelayBackend::new(
        XhttpSessionFactory { mode: XhttpSessionMode::Tls(tls) },
        context.pool_config,
        None,
    )))
}
