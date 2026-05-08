use std::io;
use std::net::{SocketAddr, ToSocketAddrs};

use crate::runtime::state::RuntimeState;

/// Resolve a probe domain to a `SocketAddr` on port 443.
pub(crate) fn resolve_probe_target(state: &RuntimeState, domain: &str) -> io::Result<SocketAddr> {
    use ripdpi_proxy_runtime_adapter::ws_bootstrap::resolve_host_via_encrypted_dns;

    let settings = state.warmup_probe_settings();
    // Try encrypted DNS first (respects protect_path for VPN bypass).
    if let Ok(mut addr) = resolve_host_via_encrypted_dns(
        domain,
        state.runtime_context.as_ref(),
        settings.protect_path.as_deref(),
        settings.ipv6_enabled,
    ) {
        addr.set_port(443);
        return Ok(addr);
    }

    let addr = (domain, 443u16)
        .to_socket_addrs()
        .map_err(|err| io::Error::new(io::ErrorKind::NotFound, format!("warmup: cannot resolve {domain}: {err}")))?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, format!("warmup: no addresses for {domain}")))?;
    Ok(addr)
}
