use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::{finalmask_config, invalid_input};
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::{XhttpSessionFactory, XhttpSessionMode};

/// Build plain VLESS transports that have an in-repository native backend.
///
/// The native stack currently implements plain VLESS only for the xHTTP/TLS
/// transport. Other plain VLESS transports fail closed here rather than
/// reporting a descriptor-backed kind that cannot carry traffic.
pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Vless(vless) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected VLESS config"));
    };
    if vless.vless_transport != "xhttp" {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "plain VLESS native backend supports only xHTTP transport",
        ));
    }

    let mut tls = ripdpi_xhttp::XhttpTlsConfig::from_strings(
        &config.common.server,
        config.common.server_port,
        &config.common.server_name,
        vless.uuid.as_deref().unwrap_or_default(),
        &vless.xhttp_path,
        &vless.xhttp_host,
        &config.common.tls_fingerprint_profile,
    )
    .map_err(invalid_input)?;
    tls.bind_ip = context.outbound_bind_ip;
    tls.socket_protector = context.socket_protector.clone();
    tls.finalmask = finalmask_config(&config.common.finalmask);

    Ok(RelayBackend::Xhttp(PooledRelayBackend::new(
        XhttpSessionFactory { mode: XhttpSessionMode::Tls(tls) },
        context.pool_config,
        None,
    )))
}
