use ripdpi_config::RuntimeConfig;
use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;

use crate::transport::TransportConfig;

use super::{CandidateAttemptCorrelationId, CandidateRuntimeError, CandidateRuntimeTerminalReceipt};

pub struct PreparedCandidateRuntime {
    pub config: RuntimeConfig,
    pub runtime_context: Option<ProxyRuntimeContext>,
    pub generation: u64,
}

pub trait CandidateProbeRuntime: Send {
    fn transport(&self) -> TransportConfig;

    fn generation(&self) -> u64 {
        0
    }

    fn transport_for_attempt(&self, _attempt_token: &CandidateAttemptCorrelationId) -> TransportConfig {
        self.transport()
    }

    /// Requests cooperative cancellation of listener and connection work.
    fn request_shutdown(&mut self);

    /// Forces tracked I/O closed and joins every owned runtime thread.
    fn force_abort_and_join(&mut self) -> CandidateRuntimeTerminalReceipt;

    /// Completes cooperative shutdown and joins every owned worker.
    fn shutdown(self: Box<Self>) -> CandidateRuntimeTerminalReceipt;
}

pub trait CandidateRuntimeLauncher: Send + Sync {
    fn start_candidate_runtime(
        &self,
        prepared: PreparedCandidateRuntime,
    ) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError>;
}
