use std::io;
use std::net::{SocketAddr, ToSocketAddrs};

use crate::runtime::state::RuntimeState;

/// Resolve a probe domain to a `SocketAddr` on port 443.
pub(crate) fn resolve_probe_target(state: &RuntimeState, domain: &str) -> io::Result<SocketAddr> {
    // Try encrypted DNS first (respects protect_path for VPN bypass).
    if let Ok(mut addr) = state.resolve_warmup_probe_host(domain) {
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::config::RuntimeConfig;

    #[test]
    fn resolve_probe_target_falls_back_to_system_resolution() {
        let state = RuntimeState::test(RuntimeConfig::default());

        let target = resolve_probe_target(&state, "localhost").expect("resolve localhost");

        assert_eq!(target.port(), 443);
    }
}
