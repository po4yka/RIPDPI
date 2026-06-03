mod engine;
mod execution;
mod platform;
mod probes;
mod session;

pub mod contracts;
pub mod wire;

use ripdpi_monitor_adapter::proxy_config::{ProxyConfigPayload, parse_proxy_config_json};

pub(crate) use probes::{
    blockpage_fingerprints, candidates, cdn_ech, classification, connectivity, http, observations, strategy, telegram,
    tls, transport, util,
};
pub(crate) use ripdpi_diagnostics_contracts as types;
#[cfg(test)]
pub(crate) use session::validate_scan_request;

#[cfg(test)]
mod test_fixtures;
#[cfg(test)]
mod tests;

pub use engine::probe_descriptors_as_json;
pub use execution::{CandidateProbeRuntime, CandidateRuntimeError, CandidateRuntimeLauncher, PreparedCandidateRuntime};
pub use platform::{MonitorPlatformBridge, ScopedMonitorLogLevel};
pub use session::MonitorSession;

pub fn parse_proxy_config_payload_json(json: &str) -> Result<ProxyConfigPayload, String> {
    parse_proxy_config_json(json).map_err(|err| err.to_string())
}
