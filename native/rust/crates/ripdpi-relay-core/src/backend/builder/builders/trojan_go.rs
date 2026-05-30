use std::io;

use crate::backend::builder::builders::common::to_io_error;
use crate::backend::builder::BuildContext;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::TrojanGoSessionFactory;

/// Build the Trojan-Go relay backend.
///
/// Trojan-Go is TCP-only and non-reusable. The SMUX / WebSocket wire engine in
/// `ripdpi-trojan-go` is stubbed in this foundation, so the built backend
/// validates config and fails session creation with `Unimplemented` rather than
/// carrying traffic.
pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::TrojanGo(trojan_go) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected Trojan-Go config"));
    };
    let port = u16::try_from(config.common.server_port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Trojan-Go server port must fit u16"))?;
    let password = trojan_go
        .password
        .clone()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing Trojan-Go password"))?;
    let mux = ripdpi_relay_tls_transports::TrojanGoMux::parse(&trojan_go.mux).map_err(to_io_error)?;
    let inner_cipher = trojan_go.inner_cipher.trim();
    let inner = ripdpi_relay_tls_transports::TrojanGoInner::parse((!inner_cipher.is_empty()).then_some(inner_cipher))
        .map_err(to_io_error)?;

    let client_config = ripdpi_relay_tls_transports::TrojanGoConfig {
        server: config.common.server.clone(),
        port,
        password,
        sni: optional(&trojan_go.sni),
        ws_path: optional(&trojan_go.ws_path),
        ws_host: optional(&trojan_go.ws_host),
        mux,
        inner,
    };
    client_config.validate().map_err(to_io_error)?;

    Ok(RelayBackend::TrojanGo(PooledRelayBackend::new(
        TrojanGoSessionFactory { config: client_config },
        context.pool_config,
        None,
    )))
}

fn optional(value: &Option<String>) -> Option<String> {
    value.as_ref().map(|inner| inner.trim().to_owned()).filter(|inner| !inner.is_empty())
}
