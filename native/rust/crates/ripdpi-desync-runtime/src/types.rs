use std::io;
use std::sync::Arc;

use ripdpi_config::OffsetBase;
use ripdpi_desync::{DesyncAction, DesyncPlan};

const RECEIPT_COUNTER_MAX: usize = u16::MAX as usize;
const RECEIPT_DELTA_MIN: i64 = -4096;
const RECEIPT_DELTA_MAX: i64 = 4096;

/// Callback invoked for each packet written during desync execution.
/// The bool parameter is `true` for outbound packets.
pub type PcapHook = Arc<dyn Fn(&[u8], bool) + Send + Sync>;

#[derive(Debug)]
pub struct OutboundSendOutcome {
    pub bytes_committed: usize,
    pub strategy_family: Option<&'static str>,
    pub execution_receipt: TcpExecutionReceipt,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum TcpExecutionDisposition {
    Applied,
    ActivationSkipped,
    PlanFailedPlainFallback,
    ExecutionFailed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum TcpStrategyFamily {
    Split,
    TlsRecordSplit,
    SeqOverlap,
    TlsRecordSeqOverlap,
    MultiDisorder,
    TlsRecordMultiDisorder,
    Disorder,
    OutOfBand,
    DisorderedOutOfBand,
    Fake,
    FakeSplit,
    FakeDisorder,
    HostFake,
    IpFragment2,
    FakeRst,
    TlsRecord,
    Unknown,
}

impl TcpStrategyFamily {
    pub fn from_token(token: &'static str) -> Self {
        match token {
            "split" | "seg_pre_sni" | "seg_mid_sni" | "seg_post_sni" | "two_phase_send" => Self::Split,
            "tlsrec_split" => Self::TlsRecordSplit,
            "seqovl" => Self::SeqOverlap,
            "tlsrec_seqovl" => Self::TlsRecordSeqOverlap,
            "multidisorder" => Self::MultiDisorder,
            "tlsrec_multidisorder" => Self::TlsRecordMultiDisorder,
            "disorder" => Self::Disorder,
            "oob" => Self::OutOfBand,
            "disoob" => Self::DisorderedOutOfBand,
            "fake" => Self::Fake,
            "fakedsplit" => Self::FakeSplit,
            "fakeddisorder" => Self::FakeDisorder,
            "hostfake" => Self::HostFake,
            "ipfrag2" => Self::IpFragment2,
            "fakerst" => Self::FakeRst,
            "tlsrec" | "rec_pre_sni" | "rec_mid_sni" => Self::TlsRecord,
            _ => Self::Unknown,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum TcpOffsetMarkerBase {
    Absolute,
    PayloadEnd,
    PayloadMid,
    PayloadRand,
    Host,
    EndHost,
    HostMid,
    HostRand,
    Sld,
    MidSld,
    EndSld,
    Method,
    ExtLen,
    EchExt,
    SniExt,
    Adaptive,
}

impl TcpOffsetMarkerBase {
    pub fn from_offset_base(base: OffsetBase) -> Self {
        match base {
            OffsetBase::Abs => Self::Absolute,
            OffsetBase::PayloadEnd => Self::PayloadEnd,
            OffsetBase::PayloadMid => Self::PayloadMid,
            OffsetBase::PayloadRand => Self::PayloadRand,
            OffsetBase::Host => Self::Host,
            OffsetBase::EndHost => Self::EndHost,
            OffsetBase::HostMid => Self::HostMid,
            OffsetBase::HostRand => Self::HostRand,
            OffsetBase::Sld => Self::Sld,
            OffsetBase::MidSld => Self::MidSld,
            OffsetBase::EndSld => Self::EndSld,
            OffsetBase::Method => Self::Method,
            OffsetBase::ExtLen => Self::ExtLen,
            OffsetBase::EchExt => Self::EchExt,
            OffsetBase::SniExt => Self::SniExt,
            OffsetBase::AutoBalanced
            | OffsetBase::AutoHost
            | OffsetBase::AutoMidSld
            | OffsetBase::AutoEndHost
            | OffsetBase::AutoMethod
            | OffsetBase::AutoSniExt
            | OffsetBase::AutoExtLen => Self::Adaptive,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum TcpTerminalReason {
    Transport,
    StrategyExecution,
    Planning,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum TcpFallbackReason {
    AndroidTtlUnavailable,
    StrategyFamilyFallback,
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[non_exhaustive]
pub struct TcpExecutionReceipt {
    pub disposition: TcpExecutionDisposition,
    pub configured_family: Option<TcpStrategyFamily>,
    pub effective_family: Option<TcpStrategyFamily>,
    pub marker_base: Option<TcpOffsetMarkerBase>,
    pub marker_delta: Option<i16>,
    pub resolved_offset: Option<usize>,
    pub planned_steps: usize,
    pub attempted_actions: usize,
    pub completed_actions: usize,
    pub real_writes_committed: usize,
    pub completed_awaits: usize,
    pub payload_bytes_committed: usize,
    pub tls_record_prelude_applied: bool,
    pub tls_prelude_configured_count: usize,
    pub tls_prelude_applied_count: usize,
    pub tls_prelude_kind: Option<ripdpi_config::TcpChainStepKind>,
    pub tls_prelude_marker_base: Option<TcpOffsetMarkerBase>,
    pub tls_prelude_marker_delta: Option<i16>,
    pub tls_prelude_resolved_offset: Option<usize>,
    pub fallback_reason: Option<TcpFallbackReason>,
    pub terminal_reason: Option<TcpTerminalReason>,
}

impl TcpExecutionReceipt {
    pub fn tls_prelude_kind_token(&self) -> Option<&'static str> {
        self.tls_prelude_kind.and_then(|kind| match kind {
            ripdpi_config::TcpChainStepKind::TlsRec => Some("tlsrec"),
            ripdpi_config::TcpChainStepKind::TlsRandRec => Some("tlsrandrec"),
            _ => None,
        })
    }

    pub(crate) fn applied(
        group: &ripdpi_config::DesyncGroup,
        plan: &DesyncPlan,
        configured_family: Option<&'static str>,
        effective_family: Option<&'static str>,
        fallback_reason: Option<TcpFallbackReason>,
        tls_record_prelude_applied: bool,
    ) -> Self {
        let summary = TcpActionReceiptSummary::from_actions(&plan.actions);
        Self::applied_with_counters(
            group,
            plan,
            configured_family,
            effective_family,
            fallback_reason,
            summary.actions,
            summary.real_writes,
            summary.awaits,
            summary.payload_bytes,
            tls_record_prelude_applied,
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub(crate) fn applied_with_counters(
        group: &ripdpi_config::DesyncGroup,
        plan: &DesyncPlan,
        configured_family: Option<&'static str>,
        effective_family: Option<&'static str>,
        fallback_reason: Option<TcpFallbackReason>,
        completed_actions: usize,
        real_writes_committed: usize,
        completed_awaits: usize,
        payload_bytes_committed: usize,
        tls_record_prelude_applied: bool,
    ) -> Self {
        let (marker_base, marker_delta) = plan_marker(group, plan);
        Self {
            disposition: TcpExecutionDisposition::Applied,
            configured_family: configured_family.map(TcpStrategyFamily::from_token),
            effective_family: effective_family.map(TcpStrategyFamily::from_token),
            marker_base,
            marker_delta,
            resolved_offset: bounded_offset(plan.steps.first().and_then(|step| usize::try_from(step.end).ok())),
            planned_steps: bounded_count(plan.steps.len().saturating_add(plan.tls_prelude.applied_count)),
            attempted_actions: bounded_count(completed_actions),
            completed_actions: bounded_count(completed_actions),
            real_writes_committed: bounded_count(real_writes_committed),
            completed_awaits: bounded_count(completed_awaits),
            payload_bytes_committed: bounded_count(payload_bytes_committed),
            tls_record_prelude_applied,
            tls_prelude_configured_count: bounded_count(plan.tls_prelude.configured_count),
            tls_prelude_applied_count: bounded_count(plan.tls_prelude.applied_count),
            tls_prelude_kind: plan.tls_prelude.kind,
            tls_prelude_marker_base: plan.tls_prelude.marker_base.map(TcpOffsetMarkerBase::from_offset_base),
            tls_prelude_marker_delta: plan.tls_prelude.marker_delta,
            tls_prelude_resolved_offset: bounded_offset(plan.tls_prelude.resolved_offset),
            fallback_reason,
            terminal_reason: None,
        }
    }

    pub(crate) fn plain(
        disposition: TcpExecutionDisposition,
        group: &ripdpi_config::DesyncGroup,
        configured_family: Option<&'static str>,
        bytes_committed: usize,
    ) -> Self {
        debug_assert!(matches!(
            disposition,
            TcpExecutionDisposition::ActivationSkipped | TcpExecutionDisposition::PlanFailedPlainFallback
        ));
        let (marker_base, marker_delta) = configured_marker(group);
        Self {
            disposition,
            configured_family: configured_family.map(TcpStrategyFamily::from_token),
            effective_family: None,
            marker_base,
            marker_delta,
            resolved_offset: None,
            planned_steps: 0,
            attempted_actions: 1,
            completed_actions: 1,
            real_writes_committed: 1,
            completed_awaits: 0,
            payload_bytes_committed: bounded_count(bytes_committed),
            tls_record_prelude_applied: false,
            tls_prelude_configured_count: 0,
            tls_prelude_applied_count: 0,
            tls_prelude_kind: None,
            tls_prelude_marker_base: None,
            tls_prelude_marker_delta: None,
            tls_prelude_resolved_offset: None,
            fallback_reason: None,
            terminal_reason: None,
        }
    }

    pub fn failed_strategy_execution(
        strategy_family: Option<&'static str>,
        attempted_actions: usize,
        completed_actions: usize,
        real_writes_committed: usize,
        completed_awaits: usize,
        payload_bytes_committed: usize,
        terminal_reason: TcpTerminalReason,
    ) -> Self {
        Self {
            disposition: TcpExecutionDisposition::ExecutionFailed,
            configured_family: strategy_family.map(TcpStrategyFamily::from_token),
            effective_family: strategy_family.map(TcpStrategyFamily::from_token),
            marker_base: None,
            marker_delta: None,
            resolved_offset: None,
            planned_steps: 0,
            attempted_actions: bounded_count(attempted_actions),
            completed_actions: bounded_count(completed_actions),
            real_writes_committed: bounded_count(real_writes_committed),
            completed_awaits: bounded_count(completed_awaits),
            payload_bytes_committed: bounded_count(payload_bytes_committed),
            tls_record_prelude_applied: false,
            tls_prelude_configured_count: 0,
            tls_prelude_applied_count: 0,
            tls_prelude_kind: None,
            tls_prelude_marker_base: None,
            tls_prelude_marker_delta: None,
            tls_prelude_resolved_offset: None,
            fallback_reason: None,
            terminal_reason: Some(terminal_reason),
        }
    }

    pub(crate) fn failed_with_plan(
        group: &ripdpi_config::DesyncGroup,
        plan: &DesyncPlan,
        configured_family: Option<&'static str>,
        effective_family: Option<&'static str>,
        fallback_reason: Option<TcpFallbackReason>,
        source: Option<&Self>,
        terminal_reason: TcpTerminalReason,
    ) -> Self {
        let (marker_base, marker_delta) = plan_marker(group, plan);
        let source = source
            .cloned()
            .unwrap_or_else(|| Self::failed_strategy_execution(configured_family, 0, 0, 0, 0, 0, terminal_reason));
        let observed_write = usize::from(source.payload_bytes_committed > 0);
        Self {
            disposition: TcpExecutionDisposition::ExecutionFailed,
            configured_family: configured_family.map(TcpStrategyFamily::from_token),
            effective_family: effective_family.map(TcpStrategyFamily::from_token),
            marker_base,
            marker_delta,
            resolved_offset: bounded_offset(plan.steps.first().and_then(|step| usize::try_from(step.end).ok())),
            planned_steps: bounded_count(plan.steps.len().saturating_add(plan.tls_prelude.applied_count)),
            attempted_actions: bounded_count(source.attempted_actions.max(observed_write)),
            completed_actions: bounded_count(source.completed_actions.max(observed_write)),
            real_writes_committed: bounded_count(source.real_writes_committed.max(observed_write)),
            completed_awaits: bounded_count(source.completed_awaits),
            payload_bytes_committed: bounded_count(source.payload_bytes_committed),
            tls_record_prelude_applied: plan.tls_prelude.applied_count > 0,
            tls_prelude_configured_count: bounded_count(plan.tls_prelude.configured_count),
            tls_prelude_applied_count: bounded_count(plan.tls_prelude.applied_count),
            tls_prelude_kind: plan.tls_prelude.kind,
            tls_prelude_marker_base: plan.tls_prelude.marker_base.map(TcpOffsetMarkerBase::from_offset_base),
            tls_prelude_marker_delta: plan.tls_prelude.marker_delta,
            tls_prelude_resolved_offset: bounded_offset(plan.tls_prelude.resolved_offset),
            fallback_reason,
            terminal_reason: Some(terminal_reason),
        }
    }
}

fn configured_marker(group: &ripdpi_config::DesyncGroup) -> (Option<TcpOffsetMarkerBase>, Option<i16>) {
    group.actions.tcp_chain.iter().find(|step| !step.kind().is_tls_prelude()).map_or((None, None), |step| {
        (Some(TcpOffsetMarkerBase::from_offset_base(step.offset().base)), Some(bounded_delta(step.offset().delta)))
    })
}

fn plan_marker(group: &ripdpi_config::DesyncGroup, plan: &DesyncPlan) -> (Option<TcpOffsetMarkerBase>, Option<i16>) {
    let Some(source_index) = plan.steps.first().and_then(|step| step.source_send_step_index) else {
        return (None, None);
    };
    group.effective_tcp_chain().into_iter().filter(|step| !step.kind().is_tls_prelude()).nth(source_index).map_or(
        (None, None),
        |step| {
            (Some(TcpOffsetMarkerBase::from_offset_base(step.offset().base)), Some(bounded_delta(step.offset().delta)))
        },
    )
}

struct TcpActionReceiptSummary {
    actions: usize,
    real_writes: usize,
    awaits: usize,
    payload_bytes: usize,
}

fn bounded_count(value: usize) -> usize {
    value.min(RECEIPT_COUNTER_MAX)
}

fn bounded_offset(value: Option<usize>) -> Option<usize> {
    value.map(bounded_count)
}

fn bounded_delta(value: i64) -> i16 {
    value.clamp(RECEIPT_DELTA_MIN, RECEIPT_DELTA_MAX) as i16
}

impl TcpActionReceiptSummary {
    fn from_actions(actions: &[DesyncAction]) -> Self {
        let mut summary = Self { actions: 0, real_writes: 0, awaits: 0, payload_bytes: 0 };
        for action in actions {
            summary.actions = summary.actions.saturating_add(1);
            match action {
                DesyncAction::Write(bytes) => {
                    summary.real_writes = summary.real_writes.saturating_add(1);
                    summary.payload_bytes = summary.payload_bytes.saturating_add(bytes.len());
                }
                DesyncAction::WriteUrgent { prefix, .. } => {
                    summary.real_writes = summary.real_writes.saturating_add(1);
                    summary.payload_bytes = summary.payload_bytes.saturating_add(prefix.len().saturating_add(1));
                }
                DesyncAction::WriteSeqOverlap { real_chunk, remainder, .. } => {
                    summary.real_writes = summary.real_writes.saturating_add(2);
                    summary.payload_bytes =
                        summary.payload_bytes.saturating_add(real_chunk.len().saturating_add(remainder.len()));
                }
                DesyncAction::WriteIpFragmentedTcp { bytes, .. } => {
                    summary.real_writes = summary.real_writes.saturating_add(1);
                    summary.payload_bytes = summary.payload_bytes.saturating_add(bytes.len());
                }
                DesyncAction::AwaitWritable => {
                    summary.awaits = summary.awaits.saturating_add(1);
                }
                DesyncAction::SetTtl(_)
                | DesyncAction::RestoreDefaultTtl
                | DesyncAction::SetMd5Sig { .. }
                | DesyncAction::AttachDropSack
                | DesyncAction::DetachDropSack
                | DesyncAction::WriteIpFragmentedUdp { .. }
                | DesyncAction::SetWindowClamp(_)
                | DesyncAction::RestoreWindowClamp
                | DesyncAction::SetWsize { .. }
                | DesyncAction::RestoreWsize
                | DesyncAction::SendFakeRst
                | DesyncAction::Delay(_) => {}
            }
        }
        summary
    }
}

#[derive(Debug)]
pub enum OutboundSendError {
    Transport {
        source: io::Error,
        execution_receipt: Option<Box<TcpExecutionReceipt>>,
    },
    StrategyExecution {
        action: &'static str,
        strategy_family: &'static str,
        fallback: Option<&'static str>,
        bytes_committed: usize,
        source_errno: Option<i32>,
        execution_receipt: Box<TcpExecutionReceipt>,
        source: io::Error,
    },
}

impl OutboundSendError {
    pub fn transport(source: io::Error) -> Self {
        Self::Transport { source, execution_receipt: None }
    }

    pub fn into_io_error(self) -> io::Error {
        let kind = self.kind();
        io::Error::new(kind, self)
    }

    pub fn kind(&self) -> io::ErrorKind {
        match self {
            Self::Transport { source, .. } => source.kind(),
            Self::StrategyExecution { source, .. } => source.kind(),
        }
    }

    pub fn source_error(&self) -> &io::Error {
        match self {
            Self::Transport { source, .. } => source,
            Self::StrategyExecution { source, .. } => source,
        }
    }

    pub fn with_execution_receipt(self, execution_receipt: TcpExecutionReceipt) -> Self {
        match self {
            Self::StrategyExecution {
                action,
                strategy_family,
                fallback,
                bytes_committed,
                source_errno,
                source,
                ..
            } => Self::StrategyExecution {
                action,
                strategy_family,
                fallback,
                bytes_committed,
                source_errno,
                execution_receipt: Box::new(execution_receipt),
                source,
            },
            Self::Transport { source, .. } => {
                Self::Transport { source, execution_receipt: Some(Box::new(execution_receipt)) }
            }
        }
    }

    pub fn execution_receipt(&self) -> Option<&TcpExecutionReceipt> {
        match self {
            Self::Transport { execution_receipt, .. } => execution_receipt.as_deref(),
            Self::StrategyExecution { execution_receipt, .. } => Some(execution_receipt.as_ref()),
        }
    }

    pub fn bytes_committed(&self) -> usize {
        match self {
            Self::Transport { execution_receipt, .. } => {
                execution_receipt.as_deref().map_or(0, |receipt| receipt.payload_bytes_committed)
            }
            Self::StrategyExecution { bytes_committed, .. } => *bytes_committed,
        }
    }
}

impl std::fmt::Display for OutboundSendError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Transport { source, .. } => source.fmt(formatter),
            Self::StrategyExecution { action, strategy_family, fallback, bytes_committed, source, .. } => {
                write!(
                    formatter,
                    "desync action={action} strategy_family={strategy_family} bytes_committed={bytes_committed}"
                )?;
                if let Some(fallback) = fallback {
                    write!(formatter, " fallback={fallback}")?;
                }
                write!(formatter, ": {source}")
            }
        }
    }
}

impl std::error::Error for OutboundSendError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(self.source_error())
    }
}

impl From<io::Error> for OutboundSendError {
    fn from(value: io::Error) -> Self {
        Self::Transport { source: value, execution_receipt: None }
    }
}
