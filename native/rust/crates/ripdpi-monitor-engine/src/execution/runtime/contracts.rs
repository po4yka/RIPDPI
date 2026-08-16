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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CandidateRuntimeTerminalReceipt {
    pub cleanup: CandidateCleanupReceipt,
    pub terminal_status: CandidateRuntimeTerminalStatus,
    pub execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
}

impl CandidateRuntimeTerminalReceipt {
    pub fn clean_shutdown(cleanup: CandidateCleanupReceipt) -> Self {
        Self { cleanup, terminal_status: CandidateRuntimeTerminalStatus::CleanShutdown, execution_evidence: Vec::new() }
    }

    pub fn forced_abort(cleanup: CandidateCleanupReceipt) -> Self {
        Self { cleanup, terminal_status: CandidateRuntimeTerminalStatus::ForcedAbort, execution_evidence: Vec::new() }
    }

    pub fn runtime_failed(cleanup: CandidateCleanupReceipt) -> Self {
        Self { cleanup, terminal_status: CandidateRuntimeTerminalStatus::RuntimeFailed, execution_evidence: Vec::new() }
    }

    pub fn runtime_panicked(cleanup: CandidateCleanupReceipt) -> Self {
        Self {
            cleanup,
            terminal_status: CandidateRuntimeTerminalStatus::RuntimePanicked,
            execution_evidence: Vec::new(),
        }
    }

    pub fn already_joined() -> Self {
        Self {
            cleanup: CandidateCleanupReceipt::default(),
            terminal_status: CandidateRuntimeTerminalStatus::AlreadyJoined,
            execution_evidence: Vec::new(),
        }
    }
}

impl Default for CandidateRuntimeTerminalReceipt {
    fn default() -> Self {
        Self::clean_shutdown(CandidateCleanupReceipt::default())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CandidateRuntimeTerminalStatus {
    CleanShutdown,
    ForcedAbort,
    RuntimeFailed,
    RuntimePanicked,
    AlreadyJoined,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CandidateRuntimeExecutionEvidence {
    Desync(CandidateDesyncExecutionReceipt),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CandidateDesyncExecutionReceipt {
    pub disposition: CandidateDesyncExecutionDisposition,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CandidateDesyncExecutionDisposition {
    Applied,
    ActivationSkipped,
    PlanFailedPlainFallback,
    ExecutionFailed,
}

pub trait CandidateProbeRuntime: Send {
    fn transport(&self) -> TransportConfig;

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
