use crate::types::{StrategyExecutionDisposition, StrategyExecutionFamily, StrategyExecutionTransport};

use super::CandidateDesyncExecutionReceipt;

pub(super) fn is_valid(receipt: &CandidateDesyncExecutionReceipt) -> bool {
    if receipt.generation == 0
        || receipt.receipt.connection_ordinal == 0
        || receipt.receipt.completed_actions > receipt.receipt.attempted_actions
        || (receipt.receipt.real_writes_committed == 0) != (receipt.receipt.payload_bytes_committed == 0)
        || receipt.receipt.configured_family == Some(StrategyExecutionFamily::Unknown)
        || receipt.receipt.effective_family == Some(StrategyExecutionFamily::Unknown)
    {
        return false;
    }
    if receipt.receipt.udp_ipv6_extension_profile.is_some()
        && (receipt.receipt.transport != StrategyExecutionTransport::Udp
            || receipt.receipt.configured_family != Some(StrategyExecutionFamily::QuicIpFragment2))
    {
        return false;
    }
    match receipt.receipt.disposition {
        StrategyExecutionDisposition::Applied => valid_applied(receipt),
        StrategyExecutionDisposition::ActivationSkipped | StrategyExecutionDisposition::PlanFailedPlainFallback => {
            receipt.receipt.effective_family.is_none()
                && receipt.receipt.real_writes_committed <= 1
                && receipt.receipt.completed_awaits == 0
                && receipt.receipt.terminal_reason.is_none()
        }
        StrategyExecutionDisposition::ExecutionFailed => receipt.receipt.terminal_reason.is_some(),
    }
}

fn valid_applied(receipt: &CandidateDesyncExecutionReceipt) -> bool {
    receipt.receipt.configured_family.is_some()
        && receipt.receipt.effective_family.is_some()
        && receipt.receipt.planned_steps > 0
        && receipt.receipt.attempted_actions > 0
        && receipt.receipt.completed_actions == receipt.receipt.attempted_actions
        && receipt.receipt.real_writes_committed > 0
        && receipt.receipt.payload_bytes_committed > 0
        && receipt.receipt.terminal_reason.is_none()
        && valid_family_shape(receipt)
}

fn valid_family_shape(receipt: &CandidateDesyncExecutionReceipt) -> bool {
    match receipt.receipt.effective_family {
        Some(StrategyExecutionFamily::Split | StrategyExecutionFamily::TlsRecordSplit) => valid_split_shape(receipt),
        Some(
            StrategyExecutionFamily::QuicBurst
            | StrategyExecutionFamily::QuicSniSplit
            | StrategyExecutionFamily::QuicFakeVersion
            | StrategyExecutionFamily::QuicCryptoSplit
            | StrategyExecutionFamily::QuicPaddingLadder
            | StrategyExecutionFamily::QuicVersionNegotiationDecoy
            | StrategyExecutionFamily::QuicMultiInitialRealistic
            | StrategyExecutionFamily::QuicDummyPrepend
            | StrategyExecutionFamily::QuicIpFragment2,
        ) => {
            receipt.receipt.transport == StrategyExecutionTransport::Udp
                && receipt.receipt.marker_base.is_none()
                && receipt.receipt.resolved_offset.is_none()
                && receipt.receipt.completed_awaits == 0
                && !receipt.receipt.tls_record_prelude_applied
        }
        _ => true,
    }
}

fn valid_split_shape(receipt: &CandidateDesyncExecutionReceipt) -> bool {
    receipt.receipt.transport == StrategyExecutionTransport::Tcp
        && receipt.receipt.marker_base.is_some()
        && receipt.receipt.resolved_offset.is_some()
        && receipt.receipt.attempted_actions >= 3
        && receipt.receipt.completed_actions >= 3
        && receipt.receipt.real_writes_committed >= 2
        && receipt.receipt.completed_awaits >= 1
        && receipt.receipt.fallback_reason.is_none()
        && (receipt.receipt.effective_family != Some(StrategyExecutionFamily::TlsRecordSplit)
            || (receipt.receipt.tls_record_prelude_applied
                && receipt.receipt.tls_prelude_configured_count == 1
                && receipt.receipt.tls_prelude_applied_count == 1
                && receipt.receipt.tls_prelude_kind.is_some()
                && receipt.receipt.tls_prelude_marker_base.is_some()
                && receipt.receipt.tls_prelude_marker_delta.is_some()
                && receipt.receipt.tls_prelude_resolved_offset.is_some()))
}
