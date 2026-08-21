// Crate-local hardening for the unsafe-block audit (H1). `ripdpi-monitor-engine`
// carries a single `unsafe` block: a test-only raw-pointer deref that shares a
// stack-local cancel flag with a probe family via an `AtomicPtr`
// (`engine/runners/connectivity/support.rs`). Re-enabling the workspace-deferred
// `undocumented_unsafe_blocks` lint locally turns the inline SAFETY-comment
// requirement into a build-time error, and `multiple_unsafe_ops_per_block`
// forces a separate justification per unsafe operation — matching the floor
// already opted into by ripdpi-vless / ripdpi-io-uring / ripdpi-privileged-ops /
// ripdpi-proxy-runtime while the rest of the workspace migrates gradually.
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

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
pub(crate) use ripdpi_diagnostics_contracts::types;
#[cfg(test)]
pub(crate) use session::validate_scan_request;

#[cfg(test)]
mod test_fixtures;
#[cfg(test)]
mod tests;

pub use engine::probe_descriptors_as_json;
pub use execution::{
    CandidateAttemptCorrelationId, CandidateCleanupReceipt, CandidateDesyncExecutionDisposition,
    CandidateDesyncExecutionReceipt, CandidateProbeRuntime, CandidateRuntimeError, CandidateRuntimeExecutionEvidence,
    CandidateRuntimeLauncher, CandidateRuntimeShutdownMode, CandidateRuntimeTerminalReceipt,
    CandidateRuntimeTerminalStatus, PreparedCandidateRuntime,
};
pub use platform::{MonitorPlatformBridge, ScopedMonitorLogLevel};
pub use session::MonitorSession;

pub fn parse_proxy_config_payload_json(json: &str) -> Result<ProxyConfigPayload, String> {
    parse_proxy_config_json(json).map_err(|err| err.to_string())
}
