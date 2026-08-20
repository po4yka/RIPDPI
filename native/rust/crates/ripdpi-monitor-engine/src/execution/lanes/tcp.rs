use std::sync::{Arc, atomic::AtomicBool};

mod domain_probe;
mod execution;

use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::types::DomainTarget;

use crate::execution::runtime::{CandidateRuntimeLauncher, CandidateRuntimeSupervisor, probe_tcp_runtime_transport};
use crate::execution::scoring::{CandidateExecution, failed_candidate_execution, not_applicable_candidate_execution};

pub(crate) struct TcpCandidateExecutionContext<'a> {
    pub(crate) tls_verifier: Option<&'a Arc<dyn ServerCertVerifier>>,
    pub(crate) keylog_path: Option<&'a str>,
    pub(crate) cancel: &'a AtomicBool,
    pub(crate) supervisor: &'a CandidateRuntimeSupervisor,
}

pub fn execute_tcp_candidate(
    runtime_launcher: &dyn CandidateRuntimeLauncher,
    spec: &StrategyCandidateSpec,
    targets: &[DomainTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
    probe_seed: u64,
    context: TcpCandidateExecutionContext<'_>,
) -> CandidateExecution {
    if targets.is_empty() {
        return not_applicable_candidate_execution(spec, 0, 3, "No HTTP or HTTPS targets configured");
    }
    let probe_started = std::time::Instant::now();
    match probe_tcp_runtime_transport(runtime_launcher, spec, runtime_context) {
        Ok(runtime) => execution::execute_started_tcp_candidate(
            context.supervisor.supervise(runtime),
            spec,
            targets,
            probe_seed,
            context,
            probe_started,
        ),
        Err(err) => failed_candidate_execution(spec, targets.len() * 2, 3, err.to_string()),
    }
}
