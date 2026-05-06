use ripdpi_config::RuntimeConfig;
use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;

use crate::transport::TransportConfig;

pub struct PreparedCandidateRuntime {
    pub config: RuntimeConfig,
    pub runtime_context: Option<ProxyRuntimeContext>,
}

pub trait CandidateProbeRuntime: Send {
    fn transport(&self) -> TransportConfig;
}

pub trait CandidateRuntimeLauncher: Send + Sync {
    fn start_candidate_runtime(
        &self,
        prepared: PreparedCandidateRuntime,
    ) -> Result<Box<dyn CandidateProbeRuntime>, String>;
}

pub struct UnavailableCandidateRuntimeLauncher;

impl CandidateRuntimeLauncher for UnavailableCandidateRuntimeLauncher {
    fn start_candidate_runtime(
        &self,
        _prepared: PreparedCandidateRuntime,
    ) -> Result<Box<dyn CandidateProbeRuntime>, String> {
        Err("candidate runtime launcher is not configured".to_string())
    }
}
