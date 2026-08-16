use std::io;
use std::sync::Arc;

use ripdpi_config::OffsetBase;
use ripdpi_desync::{DesyncAction, DesyncPlan};

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
    pub marker_delta: Option<i64>,
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
            (Some(TcpOffsetMarkerBase::from_offset_base(step.offset().base)), Some(step.offset().delta))
        });
        let summary = TcpActionReceiptSummary::from_actions(&plan.actions);

        Self {
            disposition: TcpExecutionDisposition::Applied,
            configured_family: strategy_family.map(TcpStrategyFamily::from_token),
            effective_family: strategy_family.map(TcpStrategyFamily::from_token),
            marker_base,
            marker_delta,
            resolved_offset: plan.steps.first().and_then(|step| usize::try_from(step.end).ok()),
            planned_steps: plan.steps.len(),
            attempted_actions: summary.actions,
            completed_actions: summary.actions,
            real_writes_committed: summary.real_writes,
            completed_awaits: summary.awaits,
            payload_bytes_committed: summary.payload_bytes,
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
            payload_bytes_committed: bytes_committed,
            fallback_reason: None,
            terminal_reason: None,
        }
    }
}

struct TcpActionReceiptSummary {
    actions: usize,
    real_writes: usize,
    awaits: usize,
    payload_bytes: usize,
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
    Transport(io::Error),
    StrategyExecution {
        action: &'static str,
        strategy_family: &'static str,
        fallback: Option<&'static str>,
        bytes_committed: usize,
        source_errno: Option<i32>,
        source: io::Error,
    },
}

impl OutboundSendError {
    pub fn into_io_error(self) -> io::Error {
        let kind = self.kind();
        io::Error::new(kind, self)
    }

    pub fn kind(&self) -> io::ErrorKind {
        match self {
            Self::Transport(source) => source.kind(),
            Self::StrategyExecution { source, .. } => source.kind(),
        }
    }

    pub fn source_error(&self) -> &io::Error {
        match self {
            Self::Transport(source) => source,
            Self::StrategyExecution { source, .. } => source,
        }
    }
}

impl std::fmt::Display for OutboundSendError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Transport(source) => source.fmt(formatter),
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
        Self::Transport(value)
    }
}
