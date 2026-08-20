use ripdpi_diagnostics_contracts::types::{
    StrategyDesyncExecutionReceipt, StrategyExecutionDisposition, StrategyExecutionFamily, StrategyExecutionTransport,
    StrategyFallbackReason, StrategyOffsetMarkerBase, StrategyTerminalReason, StrategyTlsPreludeKind,
    StrategyUdpIpv6ExtensionProfile,
};
use ripdpi_monitor_engine::{CandidateAttemptCorrelationId, CandidateRuntimeExecutionEvidence};

pub(super) struct CandidateExecutionEvidenceProjection {
    pub(super) evidence: Vec<CandidateRuntimeExecutionEvidence>,
    pub(super) rejected: bool,
}

pub(super) fn project_runtime_execution_evidence_batch(
    source: &[ripdpi_runtime_api::DesyncExecutionEvidence],
) -> CandidateExecutionEvidenceProjection {
    let mut rejected = false;
    let evidence = source
        .iter()
        .filter_map(|item| {
            let projected = project_runtime_execution_evidence(item);
            rejected |= projected.is_none();
            projected
        })
        .collect();
    CandidateExecutionEvidenceProjection { evidence, rejected }
}

fn project_runtime_execution_evidence(
    evidence: &ripdpi_runtime_api::DesyncExecutionEvidence,
) -> Option<CandidateRuntimeExecutionEvidence> {
    let attempt_token = CandidateAttemptCorrelationId::from_opaque(evidence.attempt_token().as_opaque_str())?;
    let receipt = project_runtime_execution_receipt(evidence.connection_ordinal(), evidence.receipt())?;
    CandidateRuntimeExecutionEvidence::checked_desync(evidence.generation(), attempt_token, receipt)
}

fn project_runtime_execution_receipt(
    connection_ordinal: u64,
    receipt: &ripdpi_runtime_api::DesyncExecutionReceipt,
) -> Option<StrategyDesyncExecutionReceipt> {
    Some(StrategyDesyncExecutionReceipt {
        connection_ordinal: connection_ordinal.min(u16::MAX as u64) as u16,
        transport: project_transport(receipt.transport())?,
        disposition: project_disposition(receipt.disposition())?,
        configured_family: project_optional(receipt.configured_family(), project_family)?,
        effective_family: project_optional(receipt.effective_family(), project_family)?,
        marker_base: project_optional(receipt.marker_base(), project_marker_base)?,
        marker_delta: receipt.marker_delta(),
        resolved_offset: receipt.resolved_offset(),
        planned_steps: receipt.planned_steps(),
        attempted_actions: receipt.attempted_actions(),
        completed_actions: receipt.completed_actions(),
        real_writes_committed: receipt.real_writes_committed().min(u16::MAX as usize) as u16,
        completed_awaits: receipt.completed_awaits().min(u16::MAX as usize) as u16,
        payload_bytes_committed: receipt.payload_bytes_committed().min(u16::MAX as usize) as u16,
        tls_record_prelude_applied: receipt.tls_record_prelude_applied(),
        tls_prelude_configured_count: receipt
            .tls_prelude()
            .map_or(0, ripdpi_runtime_api::DesyncTlsPreludeEvidence::configured_count),
        tls_prelude_applied_count: receipt
            .tls_prelude()
            .map_or(0, ripdpi_runtime_api::DesyncTlsPreludeEvidence::applied_count),
        tls_prelude_kind: project_optional(
            receipt.tls_prelude().and_then(ripdpi_runtime_api::DesyncTlsPreludeEvidence::kind),
            project_tls_prelude_kind,
        )?,
        tls_prelude_marker_base: project_optional(
            receipt.tls_prelude().and_then(ripdpi_runtime_api::DesyncTlsPreludeEvidence::marker_base),
            project_marker_base,
        )?,
        tls_prelude_marker_delta: receipt
            .tls_prelude()
            .and_then(ripdpi_runtime_api::DesyncTlsPreludeEvidence::marker_delta),
        tls_prelude_resolved_offset: receipt
            .tls_prelude()
            .and_then(ripdpi_runtime_api::DesyncTlsPreludeEvidence::resolved_offset),
        udp_ipv6_extension_profile: project_optional(receipt.udp_ipv6_extension_profile(), project_udp_profile)?,
        fallback_reason: project_optional(receipt.fallback_reason(), project_fallback_reason)?,
        terminal_reason: project_optional(receipt.terminal_reason(), project_terminal_reason)?,
    })
}

fn project_optional<T, U>(source: Option<T>, project: fn(T) -> Option<U>) -> Option<Option<U>> {
    match source {
        Some(value) => project(value).map(Some),
        None => Some(None),
    }
}

fn project_transport(source: ripdpi_runtime_api::DesyncExecutionTransport) -> Option<StrategyExecutionTransport> {
    match source {
        ripdpi_runtime_api::DesyncExecutionTransport::Tcp => Some(StrategyExecutionTransport::Tcp),
        ripdpi_runtime_api::DesyncExecutionTransport::Udp => Some(StrategyExecutionTransport::Udp),
        _ => None,
    }
}

fn project_disposition(source: ripdpi_runtime_api::DesyncExecutionDisposition) -> Option<StrategyExecutionDisposition> {
    match source {
        ripdpi_runtime_api::DesyncExecutionDisposition::Applied => Some(StrategyExecutionDisposition::Applied),
        ripdpi_runtime_api::DesyncExecutionDisposition::ActivationSkipped => {
            Some(StrategyExecutionDisposition::ActivationSkipped)
        }
        ripdpi_runtime_api::DesyncExecutionDisposition::PlanFailedPlainFallback => {
            Some(StrategyExecutionDisposition::PlanFailedPlainFallback)
        }
        ripdpi_runtime_api::DesyncExecutionDisposition::ExecutionFailed => {
            Some(StrategyExecutionDisposition::ExecutionFailed)
        }
        _ => None,
    }
}

fn project_family(source: ripdpi_runtime_api::DesyncStrategyFamily) -> Option<StrategyExecutionFamily> {
    use ripdpi_runtime_api::DesyncStrategyFamily as Source;
    Some(match source {
        Source::Split => StrategyExecutionFamily::Split,
        Source::TlsRecordSplit => StrategyExecutionFamily::TlsRecordSplit,
        Source::SeqOverlap => StrategyExecutionFamily::SeqOverlap,
        Source::TlsRecordSeqOverlap => StrategyExecutionFamily::TlsRecordSeqOverlap,
        Source::MultiDisorder => StrategyExecutionFamily::MultiDisorder,
        Source::TlsRecordMultiDisorder => StrategyExecutionFamily::TlsRecordMultiDisorder,
        Source::Disorder => StrategyExecutionFamily::Disorder,
        Source::OutOfBand => StrategyExecutionFamily::OutOfBand,
        Source::DisorderedOutOfBand => StrategyExecutionFamily::DisorderedOutOfBand,
        Source::Fake => StrategyExecutionFamily::Fake,
        Source::FakeSplit => StrategyExecutionFamily::FakeSplit,
        Source::FakeDisorder => StrategyExecutionFamily::FakeDisorder,
        Source::HostFake => StrategyExecutionFamily::HostFake,
        Source::IpFragment2 => StrategyExecutionFamily::IpFragment2,
        Source::FakeRst => StrategyExecutionFamily::FakeRst,
        Source::TlsRecord => StrategyExecutionFamily::TlsRecord,
        Source::QuicBurst => StrategyExecutionFamily::QuicBurst,
        Source::QuicSniSplit => StrategyExecutionFamily::QuicSniSplit,
        Source::QuicFakeVersion => StrategyExecutionFamily::QuicFakeVersion,
        Source::QuicCryptoSplit => StrategyExecutionFamily::QuicCryptoSplit,
        Source::QuicPaddingLadder => StrategyExecutionFamily::QuicPaddingLadder,
        Source::QuicVersionNegotiationDecoy => StrategyExecutionFamily::QuicVersionNegotiationDecoy,
        Source::QuicMultiInitialRealistic => StrategyExecutionFamily::QuicMultiInitialRealistic,
        Source::QuicDummyPrepend => StrategyExecutionFamily::QuicDummyPrepend,
        Source::QuicIpFragment2 => StrategyExecutionFamily::QuicIpFragment2,
        Source::Unknown => return None,
        _ => return None,
    })
}

fn project_marker_base(source: ripdpi_runtime_api::DesyncOffsetMarkerBase) -> Option<StrategyOffsetMarkerBase> {
    use ripdpi_runtime_api::DesyncOffsetMarkerBase as Source;
    Some(match source {
        Source::Absolute => StrategyOffsetMarkerBase::Absolute,
        Source::PayloadEnd => StrategyOffsetMarkerBase::PayloadEnd,
        Source::PayloadMid => StrategyOffsetMarkerBase::PayloadMid,
        Source::PayloadRand => StrategyOffsetMarkerBase::PayloadRand,
        Source::Host => StrategyOffsetMarkerBase::Host,
        Source::EndHost => StrategyOffsetMarkerBase::EndHost,
        Source::HostMid => StrategyOffsetMarkerBase::HostMid,
        Source::HostRand => StrategyOffsetMarkerBase::HostRand,
        Source::Sld => StrategyOffsetMarkerBase::Sld,
        Source::MidSld => StrategyOffsetMarkerBase::MidSld,
        Source::EndSld => StrategyOffsetMarkerBase::EndSld,
        Source::Method => StrategyOffsetMarkerBase::Method,
        Source::ExtLen => StrategyOffsetMarkerBase::ExtLen,
        Source::EchExt => StrategyOffsetMarkerBase::EchExt,
        Source::SniExt => StrategyOffsetMarkerBase::SniExt,
        Source::Adaptive => StrategyOffsetMarkerBase::Adaptive,
        _ => return None,
    })
}

fn project_tls_prelude_kind(source: ripdpi_runtime_api::DesyncTlsPreludeKind) -> Option<StrategyTlsPreludeKind> {
    match source {
        ripdpi_runtime_api::DesyncTlsPreludeKind::TlsRec => Some(StrategyTlsPreludeKind::TlsRec),
        ripdpi_runtime_api::DesyncTlsPreludeKind::TlsRandRec => Some(StrategyTlsPreludeKind::TlsRandRec),
        _ => None,
    }
}

fn project_udp_profile(
    source: ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile,
) -> Option<StrategyUdpIpv6ExtensionProfile> {
    match source {
        ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile::HopByHop => Some(StrategyUdpIpv6ExtensionProfile::HopByHop),
        ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile::HopByHop2 => {
            Some(StrategyUdpIpv6ExtensionProfile::HopByHop2)
        }
        ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile::DestinationOptions => {
            Some(StrategyUdpIpv6ExtensionProfile::DestinationOptions)
        }
        ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile::HopByHopDestinationOptions => {
            Some(StrategyUdpIpv6ExtensionProfile::HopByHopDestinationOptions)
        }
        ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile::Unknown => None,
        _ => None,
    }
}

fn project_fallback_reason(source: ripdpi_runtime_api::DesyncFallbackReason) -> Option<StrategyFallbackReason> {
    match source {
        ripdpi_runtime_api::DesyncFallbackReason::AndroidTtlUnavailable => {
            Some(StrategyFallbackReason::AndroidTtlUnavailable)
        }
        ripdpi_runtime_api::DesyncFallbackReason::StrategyFamilyFallback => {
            Some(StrategyFallbackReason::StrategyFamilyFallback)
        }
        _ => None,
    }
}

fn project_terminal_reason(source: ripdpi_runtime_api::DesyncTerminalReason) -> Option<StrategyTerminalReason> {
    match source {
        ripdpi_runtime_api::DesyncTerminalReason::Transport => Some(StrategyTerminalReason::Transport),
        ripdpi_runtime_api::DesyncTerminalReason::StrategyExecution => Some(StrategyTerminalReason::StrategyExecution),
        ripdpi_runtime_api::DesyncTerminalReason::Planning => Some(StrategyTerminalReason::Planning),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_runtime_api::{
        AttemptCorrelationId, DesyncExecutionDisposition, DesyncExecutionEvidence, DesyncExecutionReceipt,
        DesyncExecutionTransport, DesyncStrategyFamily,
    };

    #[test]
    fn unknown_runtime_family_is_not_projected_as_strategy_evidence() {
        let receipt = DesyncExecutionReceipt::try_new(
            DesyncExecutionTransport::Tcp,
            DesyncExecutionDisposition::Applied,
            Some(DesyncStrategyFamily::Unknown),
            Some(DesyncStrategyFamily::Unknown),
            None,
            None,
            None,
            1,
            1,
            1,
            1,
            0,
            1,
            false,
            None,
            None,
            None,
            None,
        )
        .expect("runtime receipt accepts forward-compatible family");
        let evidence = DesyncExecutionEvidence::new(
            1,
            AttemptCorrelationId::new("p-0000000000000001-0000000000000001").expect("attempt token"),
            1,
            receipt,
        )
        .expect("generation-bound evidence");

        let projection = project_runtime_execution_evidence_batch(&[evidence]);

        assert!(projection.evidence.is_empty());
        assert!(projection.rejected);
    }

    #[test]
    fn split_runtime_receipt_preserves_bounded_strategy_fields() {
        let receipt = DesyncExecutionReceipt::try_new(
            DesyncExecutionTransport::Tcp,
            DesyncExecutionDisposition::Applied,
            Some(DesyncStrategyFamily::Split),
            Some(DesyncStrategyFamily::Split),
            Some(ripdpi_runtime_api::DesyncOffsetMarkerBase::Host),
            Some(1),
            Some(17),
            1,
            3,
            3,
            2,
            1,
            128,
            false,
            None,
            None,
            None,
            None,
        )
        .expect("valid split receipt");

        let projected = project_runtime_execution_receipt(2, &receipt).expect("known receipt projection");

        assert_eq!(projected.connection_ordinal, 2);
        assert_eq!(projected.transport, StrategyExecutionTransport::Tcp);
        assert_eq!(projected.disposition, StrategyExecutionDisposition::Applied);
        assert_eq!(projected.configured_family, Some(StrategyExecutionFamily::Split));
        assert_eq!(projected.effective_family, Some(StrategyExecutionFamily::Split));
        assert_eq!(projected.marker_base, Some(StrategyOffsetMarkerBase::Host));
        assert_eq!(projected.marker_delta, Some(1));
        assert_eq!(projected.resolved_offset, Some(17));
        assert_eq!(projected.planned_steps, 1);
        assert_eq!(projected.attempted_actions, 3);
        assert_eq!(projected.completed_actions, 3);
        assert_eq!(projected.real_writes_committed, 2);
        assert_eq!(projected.completed_awaits, 1);
        assert_eq!(projected.payload_bytes_committed, 128);
        assert!(projected.fallback_reason.is_none());
        assert!(projected.terminal_reason.is_none());
    }
}
