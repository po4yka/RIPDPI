use std::io;
use std::path::PathBuf;

use crate::backend::RelayBackend;
use crate::backend::builder::BuildContext;
use crate::config::{RelayBackendConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::{TorBridgePtRelayConfig, TorPluggableTransportConfig, TorRelayBackend};

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::Tor(tor) = &config.backend else {
        // A registry misroute must fail the build with a typed error instead
        // of panicking: backend construction runs at runtime startup, where a
        // panic would take the VPN service down uncleanly.
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("tor builder received a {} config", config.kind_id()),
        ));
    };
    if context.socket_protection == ripdpi_relay_tls_transports::SocketProtectionPolicy::VpnRequired {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "Tor relay cannot protect Arti-owned carrier sockets in VPN mode",
        ));
    }

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

// NOTE: This variant uses the `Error + Send + Sync + 'static` bound rather than
// the crate-wide `to_io_error(impl Display)` in builders/common.rs because
// `io::Error::other(error)` boxes the full error chain, whereas the Display-bound
// helper calls `.to_string()` and loses it. TorRelayBackend::from_bridge_pt_config
// returns a boxed error and callers depend on the chain for diagnostics.
fn to_io_error(error: impl std::error::Error + Send + Sync + 'static) -> io::Error {
    io::Error::other(error)
}

#[cfg(test)]
mod tests {
    use std::io;

    use crate::backend::builder::BuildContext;
    use crate::tests::sample_config;

    #[test]
    fn tor_builder_rejects_non_tor_config_with_typed_error_instead_of_panicking() {
        let context = BuildContext {
            outbound_bind_ip: None,
            socket_protector: None,
            socket_protection: ripdpi_relay_tls_transports::SocketProtectionPolicy::Inactive,
            pool_config: ripdpi_relay_mux::RelayPoolConfig::default(),
            quic_migration: crate::telemetry::QuicMigrationTelemetryState::default(),
        };

        let error = super::build(&sample_config("trojan"), &context)
            .err()
            .expect("a misrouted non-tor config must fail the build with a typed error");

        assert_eq!(io::ErrorKind::InvalidInput, error.kind());
        assert!(error.to_string().contains("trojan"), "the error must name the misrouted kind, got: {error}");
    }
}
