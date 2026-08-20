mod error;

use ripdpi_config::RuntimeConfig;
use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;

use crate::transport::TransportConfig;

pub use error::CandidateRuntimeError;

pub struct PreparedCandidateRuntime {
    pub config: RuntimeConfig,
    pub runtime_context: Option<ProxyRuntimeContext>,
    pub generation: u64,
}

pub type CandidateCleanupReceipt = crate::types::CandidateRuntimeCleanupReceipt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CandidateRuntimeTerminalReceipt {
    generation: u64,
    cleanup: CandidateCleanupReceipt,
    shutdown_mode: CandidateRuntimeShutdownMode,
    worker_outcome: CandidateRuntimeWorkerOutcome,
    execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
    execution_evidence_overflowed: bool,
}

impl CandidateRuntimeTerminalReceipt {
    pub fn clean_shutdown(
        generation: u64,
        cleanup: CandidateCleanupReceipt,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
    ) -> Option<Self> {
        (generation != 0
            && valid_completed_cleanup(cleanup, false)
            && execution_evidence_matches_generation(generation, &execution_evidence))
        .then(|| {
            Self::new(
                generation,
                cleanup,
                CandidateRuntimeShutdownMode::CleanShutdown,
                CandidateRuntimeWorkerOutcome::Clean,
                execution_evidence,
                false,
            )
        })
    }

    pub fn forced_abort(
        generation: u64,
        cleanup: CandidateCleanupReceipt,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
    ) -> Option<Self> {
        (generation != 0
            && valid_completed_cleanup(cleanup, true)
            && execution_evidence_matches_generation(generation, &execution_evidence))
        .then(|| {
            Self::new(
                generation,
                cleanup,
                CandidateRuntimeShutdownMode::ForcedAbort,
                CandidateRuntimeWorkerOutcome::Clean,
                execution_evidence,
                false,
            )
        })
    }

    pub fn runtime_failed(
        generation: u64,
        cleanup: CandidateCleanupReceipt,
        shutdown_mode: CandidateRuntimeShutdownMode,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
    ) -> Option<Self> {
        (generation != 0
            && valid_failed_cleanup(cleanup, shutdown_mode)
            && execution_evidence_matches_generation(generation, &execution_evidence))
        .then(|| {
            Self::new(
                generation,
                cleanup,
                shutdown_mode,
                CandidateRuntimeWorkerOutcome::RuntimeFailed,
                execution_evidence,
                false,
            )
        })
    }

    pub fn runtime_panicked(
        generation: u64,
        cleanup: CandidateCleanupReceipt,
        shutdown_mode: CandidateRuntimeShutdownMode,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
    ) -> Option<Self> {
        (generation != 0
            && valid_failed_cleanup(cleanup, shutdown_mode)
            && execution_evidence_matches_generation(generation, &execution_evidence))
        .then(|| {
            Self::new(
                generation,
                cleanup,
                shutdown_mode,
                CandidateRuntimeWorkerOutcome::RuntimePanicked,
                execution_evidence,
                false,
            )
        })
    }

    pub fn already_joined() -> Self {
        Self::new(
            0,
            CandidateCleanupReceipt::default(),
            CandidateRuntimeShutdownMode::AlreadyJoined,
            CandidateRuntimeWorkerOutcome::AlreadyJoined,
            Vec::new(),
            false,
        )
    }

    pub fn with_execution_evidence_overflowed(mut self, overflowed: bool) -> Self {
        self.execution_evidence_overflowed = overflowed;
        self
    }

    fn new(
        generation: u64,
        cleanup: CandidateCleanupReceipt,
        shutdown_mode: CandidateRuntimeShutdownMode,
        worker_outcome: CandidateRuntimeWorkerOutcome,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
        execution_evidence_overflowed: bool,
    ) -> Self {
        Self { generation, cleanup, shutdown_mode, worker_outcome, execution_evidence, execution_evidence_overflowed }
    }

    pub(crate) fn aggregate(
        cleanup: CandidateCleanupReceipt,
        shutdown_mode: CandidateRuntimeShutdownMode,
        worker_outcome: CandidateRuntimeWorkerOutcome,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
        execution_evidence_overflowed: bool,
    ) -> Self {
        Self::new(0, cleanup, shutdown_mode, worker_outcome, execution_evidence, execution_evidence_overflowed)
    }

    pub fn cleanup(&self) -> CandidateCleanupReceipt {
        self.cleanup
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    pub fn terminal_status(&self) -> CandidateRuntimeTerminalStatus {
        CandidateRuntimeTerminalStatus::from_modes(self.shutdown_mode, self.worker_outcome)
    }

    pub fn shutdown_mode(&self) -> CandidateRuntimeShutdownMode {
        self.shutdown_mode
    }

    pub fn worker_outcome(&self) -> CandidateRuntimeWorkerOutcome {
        self.worker_outcome
    }

    pub fn execution_evidence(&self) -> &[CandidateRuntimeExecutionEvidence] {
        &self.execution_evidence
    }

    pub fn execution_evidence_overflowed(&self) -> bool {
        self.execution_evidence_overflowed
    }
}

fn valid_completed_cleanup(cleanup: CandidateCleanupReceipt, forced: bool) -> bool {
    cleanup.started > 0
        && cleanup.started == cleanup.stopped
        && cleanup.stopped == cleanup.joined
        && if forced { cleanup.forced_abort > 0 } else { cleanup.forced_abort == 0 }
}

fn valid_failed_cleanup(cleanup: CandidateCleanupReceipt, shutdown_mode: CandidateRuntimeShutdownMode) -> bool {
    cleanup.started > 0
        && cleanup.joined == cleanup.started
        && match shutdown_mode {
            CandidateRuntimeShutdownMode::CleanShutdown => cleanup.forced_abort == 0,
            CandidateRuntimeShutdownMode::ForcedAbort => cleanup.forced_abort > 0,
            CandidateRuntimeShutdownMode::AlreadyJoined => false,
        }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum CandidateRuntimeShutdownMode {
    CleanShutdown,
    ForcedAbort,
    AlreadyJoined,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum CandidateRuntimeWorkerOutcome {
    Clean,
    RuntimeFailed,
    RuntimePanicked,
    AlreadyJoined,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum CandidateRuntimeTerminalStatus {
    CleanShutdown,
    ForcedAbort,
    RuntimeFailed,
    RuntimePanicked,
    AlreadyJoined,
}

impl CandidateRuntimeTerminalStatus {
    fn from_modes(shutdown_mode: CandidateRuntimeShutdownMode, worker_outcome: CandidateRuntimeWorkerOutcome) -> Self {
        match worker_outcome {
            CandidateRuntimeWorkerOutcome::RuntimePanicked => Self::RuntimePanicked,
            CandidateRuntimeWorkerOutcome::RuntimeFailed => Self::RuntimeFailed,
            CandidateRuntimeWorkerOutcome::AlreadyJoined => Self::AlreadyJoined,
            CandidateRuntimeWorkerOutcome::Clean => match shutdown_mode {
                CandidateRuntimeShutdownMode::ForcedAbort => Self::ForcedAbort,
                CandidateRuntimeShutdownMode::AlreadyJoined => Self::AlreadyJoined,
                CandidateRuntimeShutdownMode::CleanShutdown => Self::CleanShutdown,
            },
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CandidateRuntimeExecutionEvidence {
    Desync(CandidateDesyncExecutionReceipt),
}

impl CandidateRuntimeExecutionEvidence {
    fn generation(&self) -> u64 {
        match self {
            Self::Desync(receipt) => receipt.generation,
        }
    }
}

fn execution_evidence_matches_generation(
    generation: u64,
    execution_evidence: &[CandidateRuntimeExecutionEvidence],
) -> bool {
    execution_evidence.iter().all(|evidence| evidence.generation() == generation)
}

impl CandidateRuntimeExecutionEvidence {
    pub fn from_runtime_desync(evidence: &ripdpi_runtime_api::DesyncExecutionEvidence) -> Option<Self> {
        let receipt = evidence.receipt();
        Some(Self::Desync(CandidateDesyncExecutionReceipt {
            disposition: match receipt.disposition() {
                ripdpi_runtime_api::DesyncExecutionDisposition::Applied => CandidateDesyncExecutionDisposition::Applied,
                ripdpi_runtime_api::DesyncExecutionDisposition::ActivationSkipped => {
                    CandidateDesyncExecutionDisposition::ActivationSkipped
                }
                ripdpi_runtime_api::DesyncExecutionDisposition::PlanFailedPlainFallback => {
                    CandidateDesyncExecutionDisposition::PlanFailedPlainFallback
                }
                ripdpi_runtime_api::DesyncExecutionDisposition::ExecutionFailed => {
                    CandidateDesyncExecutionDisposition::ExecutionFailed
                }
                _ => return None,
            },
            generation: evidence.generation(),
            attempt_token: CandidateAttemptCorrelationId::from_opaque(evidence.attempt_token().as_opaque_str())?,
            connection_ordinal: evidence.connection_ordinal(),
            transport: receipt.transport(),
            configured_family: receipt.configured_family(),
            effective_family: receipt.effective_family(),
            marker_base: receipt.marker_base(),
            marker_delta: receipt.marker_delta(),
            resolved_offset: receipt.resolved_offset(),
            planned_steps: receipt.planned_steps(),
            attempted_actions: receipt.attempted_actions(),
            completed_actions: receipt.completed_actions(),
            real_writes_committed: receipt.real_writes_committed(),
            completed_awaits: receipt.completed_awaits(),
            payload_bytes_committed: receipt.payload_bytes_committed(),
            tls_record_prelude_applied: receipt.tls_record_prelude_applied(),
            tls_prelude_configured_count: receipt
                .tls_prelude()
                .map_or(0, ripdpi_runtime_api::DesyncTlsPreludeEvidence::configured_count),
            tls_prelude_applied_count: receipt
                .tls_prelude()
                .map_or(0, ripdpi_runtime_api::DesyncTlsPreludeEvidence::applied_count),
            tls_prelude_kind: receipt.tls_prelude().and_then(ripdpi_runtime_api::DesyncTlsPreludeEvidence::kind),
            tls_prelude_marker_base: receipt
                .tls_prelude()
                .and_then(ripdpi_runtime_api::DesyncTlsPreludeEvidence::marker_base),
            tls_prelude_marker_delta: receipt
                .tls_prelude()
                .and_then(ripdpi_runtime_api::DesyncTlsPreludeEvidence::marker_delta),
            tls_prelude_resolved_offset: receipt
                .tls_prelude()
                .and_then(ripdpi_runtime_api::DesyncTlsPreludeEvidence::resolved_offset),
            udp_ipv6_extension_profile: receipt.udp_ipv6_extension_profile(),
            fallback_reason: receipt.fallback_reason(),
            terminal_reason: receipt.terminal_reason(),
        }))
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct CandidateDesyncExecutionReceipt {
    pub(crate) disposition: CandidateDesyncExecutionDisposition,
    pub(crate) generation: u64,
    pub(crate) attempt_token: CandidateAttemptCorrelationId,
    pub(crate) connection_ordinal: u64,
    pub(crate) transport: ripdpi_runtime_api::DesyncExecutionTransport,
    pub(crate) configured_family: Option<ripdpi_runtime_api::DesyncStrategyFamily>,
    pub(crate) effective_family: Option<ripdpi_runtime_api::DesyncStrategyFamily>,
    pub(crate) marker_base: Option<ripdpi_runtime_api::DesyncOffsetMarkerBase>,
    pub(crate) marker_delta: Option<i16>,
    pub(crate) resolved_offset: Option<u16>,
    pub(crate) planned_steps: u16,
    pub(crate) attempted_actions: u16,
    pub(crate) completed_actions: u16,
    pub(crate) real_writes_committed: usize,
    pub(crate) completed_awaits: usize,
    pub(crate) payload_bytes_committed: usize,
    pub(crate) tls_record_prelude_applied: bool,
    pub(crate) tls_prelude_configured_count: u16,
    pub(crate) tls_prelude_applied_count: u16,
    pub(crate) tls_prelude_kind: Option<ripdpi_runtime_api::DesyncTlsPreludeKind>,
    pub(crate) tls_prelude_marker_base: Option<ripdpi_runtime_api::DesyncOffsetMarkerBase>,
    pub(crate) tls_prelude_marker_delta: Option<i16>,
    pub(crate) tls_prelude_resolved_offset: Option<u16>,
    pub(crate) udp_ipv6_extension_profile: Option<ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile>,
    pub(crate) fallback_reason: Option<ripdpi_runtime_api::DesyncFallbackReason>,
    pub(crate) terminal_reason: Option<ripdpi_runtime_api::DesyncTerminalReason>,
}

impl std::fmt::Debug for CandidateDesyncExecutionReceipt {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("CandidateDesyncExecutionReceipt")
            .field("disposition", &self.disposition)
            .field("generation", &self.generation)
            .field("attempt_token", &self.attempt_token)
            .field("connection_ordinal", &self.connection_ordinal)
            .field("transport", &self.transport)
            .field("configured_family", &self.configured_family)
            .field("effective_family", &self.effective_family)
            .field("marker_base", &self.marker_base)
            .field("marker_delta", &self.marker_delta)
            .field("resolved_offset", &self.resolved_offset)
            .field("planned_steps", &self.planned_steps)
            .field("attempted_actions", &self.attempted_actions)
            .field("completed_actions", &self.completed_actions)
            .field("real_writes_committed", &self.real_writes_committed)
            .field("completed_awaits", &self.completed_awaits)
            .field("payload_bytes_committed", &self.payload_bytes_committed)
            .field("tls_record_prelude_applied", &self.tls_record_prelude_applied)
            .field("tls_prelude_configured_count", &self.tls_prelude_configured_count)
            .field("tls_prelude_applied_count", &self.tls_prelude_applied_count)
            .field("tls_prelude_kind", &self.tls_prelude_kind)
            .field("tls_prelude_marker_base", &self.tls_prelude_marker_base)
            .field("tls_prelude_marker_delta", &self.tls_prelude_marker_delta)
            .field("tls_prelude_resolved_offset", &self.tls_prelude_resolved_offset)
            .field("udp_ipv6_extension_profile", &self.udp_ipv6_extension_profile)
            .field("fallback_reason", &self.fallback_reason)
            .field("terminal_reason", &self.terminal_reason)
            .finish()
    }
}

impl CandidateDesyncExecutionReceipt {
    pub(crate) fn is_valid(&self) -> bool {
        if self.generation == 0
            || self.connection_ordinal == 0
            || self.completed_actions > self.attempted_actions
            || (self.real_writes_committed == 0) != (self.payload_bytes_committed == 0)
        {
            return false;
        }
        if self.udp_ipv6_extension_profile.is_some()
            && (self.transport != ripdpi_runtime_api::DesyncExecutionTransport::Udp
                || self.configured_family != Some(ripdpi_runtime_api::DesyncStrategyFamily::QuicIpFragment2))
        {
            return false;
        }
        match self.disposition {
            CandidateDesyncExecutionDisposition::Applied => {
                let family_specific_shape = match self.effective_family {
                    Some(
                        ripdpi_runtime_api::DesyncStrategyFamily::Split
                        | ripdpi_runtime_api::DesyncStrategyFamily::TlsRecordSplit,
                    ) => {
                        self.transport == ripdpi_runtime_api::DesyncExecutionTransport::Tcp
                            && self.marker_base.is_some()
                            && self.resolved_offset.is_some()
                            && self.attempted_actions >= 3
                            && self.completed_actions >= 3
                            && self.real_writes_committed >= 2
                            && self.completed_awaits >= 1
                            && self.fallback_reason.is_none()
                            && (self.effective_family != Some(ripdpi_runtime_api::DesyncStrategyFamily::TlsRecordSplit)
                                || (self.tls_record_prelude_applied
                                    && self.tls_prelude_configured_count == 1
                                    && self.tls_prelude_applied_count == 1
                                    && self.tls_prelude_kind.is_some()
                                    && self.tls_prelude_marker_base.is_some()
                                    && self.tls_prelude_marker_delta.is_some()
                                    && self.tls_prelude_resolved_offset.is_some()))
                    }
                    Some(
                        ripdpi_runtime_api::DesyncStrategyFamily::QuicBurst
                        | ripdpi_runtime_api::DesyncStrategyFamily::QuicSniSplit
                        | ripdpi_runtime_api::DesyncStrategyFamily::QuicFakeVersion
                        | ripdpi_runtime_api::DesyncStrategyFamily::QuicCryptoSplit
                        | ripdpi_runtime_api::DesyncStrategyFamily::QuicPaddingLadder
                        | ripdpi_runtime_api::DesyncStrategyFamily::QuicVersionNegotiationDecoy
                        | ripdpi_runtime_api::DesyncStrategyFamily::QuicMultiInitialRealistic
                        | ripdpi_runtime_api::DesyncStrategyFamily::QuicDummyPrepend
                        | ripdpi_runtime_api::DesyncStrategyFamily::QuicIpFragment2,
                    ) => {
                        self.transport == ripdpi_runtime_api::DesyncExecutionTransport::Udp
                            && self.marker_base.is_none()
                            && self.resolved_offset.is_none()
                            && self.completed_awaits == 0
                            && !self.tls_record_prelude_applied
                    }
                    _ => true,
                };
                self.configured_family.is_some()
                    && self.effective_family.is_some()
                    && self.planned_steps > 0
                    && self.attempted_actions > 0
                    && self.completed_actions > 0
                    && self.real_writes_committed > 0
                    && self.payload_bytes_committed > 0
                    && self.terminal_reason.is_none()
                    && family_specific_shape
            }
            CandidateDesyncExecutionDisposition::ActivationSkipped
            | CandidateDesyncExecutionDisposition::PlanFailedPlainFallback => {
                self.effective_family.is_none()
                    && self.real_writes_committed <= 1
                    && self.completed_awaits == 0
                    && self.terminal_reason.is_none()
            }
            CandidateDesyncExecutionDisposition::ExecutionFailed => self.terminal_reason.is_some(),
        }
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct CandidateAttemptCorrelationId {
    value: String,
    role: CandidateAttemptRole,
}

impl CandidateAttemptCorrelationId {
    pub fn evaluated(generation: u64, ordinal: u64) -> Option<Self> {
        (generation != 0 && ordinal != 0).then(|| Self {
            value: format!("p-{generation:016x}-{ordinal:016x}"),
            role: CandidateAttemptRole::Evaluated,
        })
    }

    pub fn warmup(generation: u64) -> Option<Self> {
        (generation != 0).then(|| Self {
            value: format!("w-{generation:016x}-0000000000000000"),
            role: CandidateAttemptRole::Warmup,
        })
    }

    pub fn from_opaque(value: impl Into<String>) -> Option<Self> {
        let value = value.into();
        let role = match value.as_bytes().first().copied() {
            Some(b'p') => CandidateAttemptRole::Evaluated,
            Some(b'w') => CandidateAttemptRole::Warmup,
            _ => return None,
        };
        let valid_shape = value.len() == 35
            && value.as_bytes().get(1) == Some(&b'-')
            && value.as_bytes().get(18) == Some(&b'-')
            && value
                .bytes()
                .enumerate()
                .all(|(index, byte)| matches!(index, 1 | 18) || byte.is_ascii_hexdigit() || index == 0);
        valid_shape.then_some(Self { value, role })
    }

    pub fn as_opaque_str(&self) -> &str {
        &self.value
    }

    pub fn is_evaluable(&self) -> bool {
        self.role == CandidateAttemptRole::Evaluated
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CandidateAttemptRole {
    Warmup,
    Evaluated,
}

impl std::fmt::Debug for CandidateAttemptCorrelationId {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("CandidateAttemptCorrelationId(<redacted>)")
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum CandidateDesyncExecutionDisposition {
    Applied,
    ActivationSkipped,
    PlanFailedPlainFallback,
    ExecutionFailed,
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
