use std::io;

use ripdpi_root_helper_protocol::CMD_PROBE_CAPABILITIES;

use super::RootHelperClient;
use crate::capability::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability};
use crate::IpFragmentationCapabilities;

impl RootHelperClient {
    /// Probe what privileged capabilities the helper process has.
    pub fn probe_capabilities(&self) -> io::Result<IpFragmentationCapabilities> {
        let (resp, _fd) = self.transport.send_command(CMD_PROBE_CAPABILITIES, serde_json::Value::Null, None)?;
        Ok(IpFragmentationCapabilities {
            raw_ipv4: resp.data.get("raw_ipv4").and_then(serde_json::Value::as_bool).unwrap_or(false),
            raw_ipv6: resp.data.get("raw_ipv6").and_then(serde_json::Value::as_bool).unwrap_or(false),
            tcp_repair: resp.data.get("tcp_repair").and_then(serde_json::Value::as_bool).unwrap_or(false),
        })
    }
}

/// Parse the JSON `data` blob returned by `CMD_PROBE_CAPABILITIES` into a
/// list of `(RuntimeCapability, CapabilityOutcome<bool>)` pairs.
///
/// The expected JSON shape is:
/// ```json
/// { "raw_ipv4": true, "raw_ipv6": false, "tcp_repair": true }
/// ```
/// Unknown keys are ignored. Missing keys produce `Unavailable { reason: NotProbed }`.
///
/// This function lives in `ripdpi-runtime` because `RuntimeCapability` and
/// `CapabilityOutcome` are runtime/diagnostics concepts rather than helper IPC
/// wire types.
pub fn capability_outcome_from_probe_json(json: &str) -> Vec<(RuntimeCapability, CapabilityOutcome<bool>)> {
    let value: serde_json::Value = match serde_json::from_str(json) {
        Ok(v) => v,
        Err(e) => {
            let msg = e.to_string();
            return vec![
                (
                    RuntimeCapability::RawTcpFakeSend,
                    CapabilityOutcome::ProbeFailed {
                        capability: RuntimeCapability::RawTcpFakeSend,
                        error: msg.clone(),
                    },
                ),
                (
                    RuntimeCapability::RawUdpFragmentation,
                    CapabilityOutcome::ProbeFailed {
                        capability: RuntimeCapability::RawUdpFragmentation,
                        error: msg.clone(),
                    },
                ),
                (
                    RuntimeCapability::TtlWrite,
                    CapabilityOutcome::ProbeFailed { capability: RuntimeCapability::TtlWrite, error: msg },
                ),
            ];
        }
    };

    let extract = |key: &str, cap: RuntimeCapability| -> (RuntimeCapability, CapabilityOutcome<bool>) {
        match value.get(key).and_then(serde_json::Value::as_bool) {
            Some(b) => (cap, CapabilityOutcome::Available(b)),
            None => (cap, CapabilityOutcome::Unavailable { capability: cap, reason: CapabilityUnavailable::NotProbed }),
        }
    };

    vec![
        extract("raw_ipv4", RuntimeCapability::RawTcpFakeSend),
        extract("raw_ipv6", RuntimeCapability::RawUdpFragmentation),
        extract("tcp_repair", RuntimeCapability::TtlWrite),
    ]
}
