use std::io;
use std::net::{SocketAddr, ToSocketAddrs};

use crate::runtime::state::RuntimeState;

/// Resolve a probe domain to a `SocketAddr` on port 443.
pub(crate) fn resolve_probe_target(state: &RuntimeState, domain: &str) -> io::Result<SocketAddr> {
    // Try encrypted DNS first (respects protect_path for VPN bypass).
    match state.resolve_warmup_probe_host(domain) {
        Ok(mut addr) => {
            addr.set_port(443);
            return Ok(addr);
        }
        // In VPN mode the system resolver is the underlying network's resolver. It would send
        // the probe domain in plaintext, and a poisoned answer would then be learned as a block.
        Err(err) if state.warmup_runs_under_vpn() => return Err(err),
        Err(_) => {}
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

    #[test]
    fn resolve_probe_target_does_not_use_the_system_resolver_in_vpn_mode() {
        let mut config = RuntimeConfig::default();
        config.process.protect_path = Some("/nonexistent/protect.sock".to_string());
        let state = RuntimeState::test(config);

        // The system resolver answers `localhost`; only the encrypted path may answer here.
        assert!(resolve_probe_target(&state, "localhost").is_err());
    }
}
