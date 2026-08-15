mod error;

use ripdpi_config::RuntimeConfig;
use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;

use crate::transport::TransportConfig;

pub use error::CandidateRuntimeError;

pub struct PreparedCandidateRuntime {
    pub config: RuntimeConfig,
    pub runtime_context: Option<ProxyRuntimeContext>,
}

pub type CandidateCleanupReceipt = crate::types::CandidateRuntimeCleanupReceipt;

pub trait CandidateProbeRuntime: Send {
    fn transport(&self) -> TransportConfig;

    /// Requests cooperative cancellation of listener and connection work.
    fn request_shutdown(&mut self);

    /// Forces tracked I/O closed and joins every owned runtime thread.
    fn force_abort_and_join(&mut self) -> CandidateCleanupReceipt;

    /// Completes cooperative shutdown and joins every owned worker.
    fn shutdown(self: Box<Self>) -> CandidateCleanupReceipt;
}

pub trait CandidateRuntimeLauncher: Send + Sync {
    fn start_candidate_runtime(
        &self,
        prepared: PreparedCandidateRuntime,
    ) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError>;
}
