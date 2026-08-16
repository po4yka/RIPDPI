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
            "tlsrec" | "tlsrec_split" | "rec_pre_sni" | "rec_mid_sni" => Self::TlsRecord,
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
    pub fallback_reason: Option<TcpFallbackReason>,
    pub terminal_reason: Option<TcpTerminalReason>,
}

impl TcpExecutionReceipt {
    pub fn applied(
        group: &ripdpi_config::DesyncGroup,
        plan: &DesyncPlan,
        strategy_family: Option<&'static str>,
    ) -> Self {
        let first_send_step = group.effective_tcp_chain().into_iter().find(|step| !step.kind().is_tls_prelude());
        let (marker_base, marker_delta) = first_send_step.map_or((None, None), |step| {
            (Some(TcpOffsetMarkerBase::from_offset_base(step.offset().base)), Some(bounded_delta(step.offset().delta)))
        });
        let summary = TcpActionReceiptSummary::from_actions(&plan.actions);

        Self {
            disposition: TcpExecutionDisposition::Applied,
            configured_family: strategy_family.map(TcpStrategyFamily::from_token),
            effective_family: strategy_family.map(TcpStrategyFamily::from_token),
            marker_base,
            marker_delta,
            resolved_offset: plan.steps.first().and_then(|step| usize::try_from(step.end).ok()),
            planned_steps: bounded_count(plan.steps.len()),
            attempted_actions: bounded_count(summary.actions),
            completed_actions: bounded_count(summary.actions),
            real_writes_committed: bounded_count(summary.real_writes),
            completed_awaits: bounded_count(summary.awaits),
            payload_bytes_committed: bounded_count(summary.payload_bytes),
            fallback_reason: None,
            terminal_reason: None,
        }
    }

    pub fn plain(disposition: TcpExecutionDisposition, bytes_committed: usize) -> Self {
        Self {
            disposition,
            configured_family: None,
            effective_family: None,
            marker_base: None,
            marker_delta: None,
            resolved_offset: None,
            planned_steps: 0,
            attempted_actions: 1,
            completed_actions: 1,
            real_writes_committed: 1,
            completed_awaits: 0,
            payload_bytes_committed: bounded_count(bytes_committed),
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
            fallback_reason: None,
            terminal_reason: Some(terminal_reason),
        }
    }
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

fn bounded_delta(value: i64) -> i16 {
    value.clamp(RECEIPT_DELTA_MIN, RECEIPT_DELTA_MAX) as i16
}

impl TcpActionReceiptSummary {
    fn from_actions(actions: &[DesyncAction]) -> Self {
        let mut summary = Self { actions: 0, real_writes: 0, awaits: 0, payload_bytes: 0 };
        for action in actions {
            summary.actions += 1;
            match action {
                DesyncAction::Write(bytes) => {
                    summary.real_writes += 1;
                    summary.payload_bytes += bytes.len();
                }
                DesyncAction::WriteUrgent { prefix, .. } => {
                    summary.real_writes += 1;
                    summary.payload_bytes += prefix.len().saturating_add(1);
                }
                DesyncAction::WriteSeqOverlap { real_chunk, remainder, .. } => {
                    summary.real_writes += 2;
                    summary.payload_bytes += real_chunk.len().saturating_add(remainder.len());
                }
                DesyncAction::WriteIpFragmentedTcp { bytes, .. } => {
                    summary.real_writes += 1;
                    summary.payload_bytes += bytes.len();
                }
                DesyncAction::AwaitWritable => {
                    summary.awaits += 1;
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
