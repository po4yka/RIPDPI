mod engine;
mod execution;
mod platform;
mod probes;
mod session;

pub mod contracts;
pub mod wire;

use ripdpi_proxy_config::{parse_proxy_config_json, ProxyConfigPayload};

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

pub use execution::{CandidateProbeRuntime, CandidateRuntimeLauncher, PreparedCandidateRuntime};
pub use platform::{MonitorPlatformBridge, ScopedMonitorLogLevel};
pub use session::MonitorSession;

pub fn parse_proxy_config_payload_json(json: &str) -> Result<ProxyConfigPayload, String> {
    parse_proxy_config_json(json).map_err(|err| err.to_string())
}
