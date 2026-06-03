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
