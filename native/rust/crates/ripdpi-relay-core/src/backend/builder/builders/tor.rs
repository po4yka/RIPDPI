use std::io;
use std::path::PathBuf;

use crate::backend::RelayBackend;
use crate::backend::builder::BuildContext;
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::{TorBridgePtRelayConfig, TorPluggableTransportConfig, TorRelayBackend};

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, _context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Tor(tor) = &config.backend else {
        unreachable!("tor builder only called for tor configs");
    };

    let backend = TorRelayBackend::from_bridge_pt_config(TorBridgePtRelayConfig {
        state_dir: PathBuf::from(&tor.state_dir),
        cache_dir: PathBuf::from(&tor.cache_dir),
        bridge_lines: tor.bridge_lines.clone(),
        transports: tor
            .transports
            .iter()
            .map(|transport| TorPluggableTransportConfig {
                protocols: transport.protocols.clone(),
                binary_path: PathBuf::from(&transport.binary_path),
                arguments: transport.arguments.clone(),
                run_on_startup: transport.run_on_startup,
            })
            .collect(),
    })
    .map_err(to_io_error)?;
    Ok(RelayBackend::Tor(Box::new(backend)))
}

fn to_io_error(error: impl std::error::Error + Send + Sync + 'static) -> io::Error {
    io::Error::other(error)
}
