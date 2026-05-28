use std::io;
use std::path::PathBuf;

use ripdpi_relay_mux::{RelayCapabilities, RelayPoolHealth};

use crate::backend::BoxedIo;
use crate::socks::RelayTargetAddr;

pub(crate) struct TorRelayBackend {
    inner: ripdpi_relay_tls_transports::TorRelayBackend,
}

impl TorRelayBackend {
    pub(crate) fn from_bridge_pt_config(config: TorBridgePtRelayConfig) -> io::Result<Self> {
        let transports = config
            .transports
            .into_iter()
            .map(|transport| ripdpi_relay_tls_transports::TorPluggableTransportConfig {
                protocols: transport.protocols,
                binary_path: transport.binary_path,
                arguments: transport.arguments,
                run_on_startup: transport.run_on_startup,
            })
            .collect();
        let inner = ripdpi_relay_tls_transports::TorRelayBackend::from_bridge_pt_config(
            ripdpi_relay_tls_transports::TorBridgePtRelayConfig {
                state_dir: config.state_dir,
                cache_dir: config.cache_dir,
                bridge_lines: config.bridge_lines,
                transports,
            },
        )?;
        Ok(Self { inner })
    }

    pub(crate) fn capabilities(&self) -> RelayCapabilities {
        self.inner.capabilities()
    }

    pub(crate) fn pool_health(&self) -> RelayPoolHealth {
        self.inner.pool_health()
    }

    pub(crate) async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        let target = tor_target(target)?;
        let stream = self.inner.connect_tcp(&target).await?;
        Ok(Box::new(stream))
    }
}

pub(crate) struct TorBridgePtRelayConfig {
    pub(crate) state_dir: PathBuf,
    pub(crate) cache_dir: PathBuf,
    pub(crate) bridge_lines: Vec<String>,
    pub(crate) transports: Vec<TorPluggableTransportConfig>,
}

pub(crate) struct TorPluggableTransportConfig {
    pub(crate) protocols: Vec<String>,
    pub(crate) binary_path: PathBuf,
    pub(crate) arguments: Vec<String>,
    pub(crate) run_on_startup: bool,
}

fn tor_target(target: &RelayTargetAddr) -> io::Result<ripdpi_relay_tls_transports::TorRelayTarget> {
    match target {
        RelayTargetAddr::Ip(addr) => {
            ripdpi_relay_tls_transports::TorRelayTarget::new(addr.ip().to_string(), addr.port())
        }
        RelayTargetAddr::Domain(host, port) => ripdpi_relay_tls_transports::TorRelayTarget::new(host.clone(), *port),
    }
}
