use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;

use crate::candidates::StrategyCandidateSpec;

use super::contracts::{CandidateProbeRuntime, CandidateRuntimeError, CandidateRuntimeLauncher};
use super::preparation::prepare_candidate_runtime;

pub fn probe_runtime_transport(
    launcher: &dyn CandidateRuntimeLauncher,
    spec: &StrategyCandidateSpec,
    runtime_context: Option<&ProxyRuntimeContext>,
) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
    let prepared = prepare_candidate_runtime(spec, runtime_context)?;
    match launcher.start_candidate_runtime(prepared) {
        Ok(runtime) => {
            let transport = runtime.transport();
            tracing::debug!(candidate = spec.id, transport = ?transport, "probe runtime started");
            Ok(runtime)
        }
        Err(err) => {
            tracing::warn!(candidate = spec.id, error = %err, "probe runtime failed to start");
            Err(err)
        }
    }
}

pub(crate) fn probe_tcp_runtime_transport(
    launcher: &dyn CandidateRuntimeLauncher,
    spec: &StrategyCandidateSpec,
    runtime_context: Option<&ProxyRuntimeContext>,
) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
    let mut probe_spec = spec.clone();
    // TCP candidate probes never exercise SOCKS5 UDP ASSOCIATE. Keeping the
    // user's default here makes a TCP-only relay fail validation before any
    // target is tested. Override only the ephemeral runtime spec so the
    // candidate's persisted/recommended configuration remains unchanged.
    probe_spec.config.protocols.udp_associate_enabled = Some(false);
    probe_runtime_transport(launcher, &probe_spec, runtime_context)
}
