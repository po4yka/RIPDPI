use ripdpi_config::RuntimeConfig;
use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;

use crate::transport::TransportConfig;

/// Failure modes when preparing or launching a candidate probe runtime.
///
/// `Display` is byte-for-byte compatible with the previous
/// `Result<_, String>` contract so that error text surfaced where it reaches a
/// `String` boundary (the `rationale` field of a failed candidate summary, JNI)
/// is unchanged.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum CandidateRuntimeError {
    /// No launcher was wired into the session (default `Unavailable` launcher).
    #[error("candidate runtime launcher is not configured")]
    LauncherUnavailable,
    /// Building the runtime config from the UI candidate spec failed.
    #[error("{0}")]
    Preparation(String),
    /// The launcher could not bring the candidate runtime up.
    #[error("{0}")]
    Launch(String),
}

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
