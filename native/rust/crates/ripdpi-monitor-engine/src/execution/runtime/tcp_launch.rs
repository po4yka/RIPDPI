use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;

use crate::candidates::StrategyCandidateSpec;

use super::{CandidateProbeRuntime, CandidateRuntimeError, CandidateRuntimeLauncher, probe_runtime_transport};

pub(crate) fn probe_tcp_runtime_transport(
    launcher: &dyn CandidateRuntimeLauncher,
    spec: &StrategyCandidateSpec,
    runtime_context: Option<&ProxyRuntimeContext>,
) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
    let mut probe_spec = spec.clone();
    // TCP candidate probes never exercise SOCKS5 UDP ASSOCIATE. Override only
    // the ephemeral runtime spec so persisted recommendations remain unchanged.
    probe_spec.config.protocols.udp_associate_enabled = Some(false);
    probe_runtime_transport(launcher, &probe_spec, runtime_context)
}
