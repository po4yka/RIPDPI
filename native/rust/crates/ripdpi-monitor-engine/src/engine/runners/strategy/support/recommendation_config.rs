use crate::candidates::{StrategyCandidateSpec, strategy_probe_config_json};
use crate::types::StrategyProbeCandidateSummary as CandidateSummary;

use super::capabilities::capability_available;

pub(in crate::engine::runners::strategy) fn resolve_recommended_proxy_config_json(
    quic_candidate: &CandidateSummary,
    fallback_quic_spec: &StrategyCandidateSpec,
) -> String {
    quic_candidate
        .proxy_config_json
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .map_or_else(|| strategy_probe_config_json(&fallback_quic_spec.config), str::to_owned)
}

pub(in crate::engine::runners::strategy) fn select_promotable_candidate_index(
    candidates: &[CandidateSummary],
    specs: &[StrategyCandidateSpec],
    fake_ttl_available: bool,
    tcp_fast_open_available: bool,
    ipfrag_caps: ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities,
) -> Option<usize> {
    crate::execution::winning_candidate_index_with(candidates, |candidate| {
        if !candidate_has_promotable_result(candidate) {
            return false;
        }
        let Some(spec) = specs.iter().find(|spec| spec.id == candidate.id) else {
            return false;
        };
        if candidate.desync_execution_required && !candidate_execution_matches_spec(candidate, spec) {
            return false;
        }
        candidate_is_promotable_for_path(spec, fake_ttl_available, tcp_fast_open_available, ipfrag_caps)
    })
}

pub(in crate::engine::runners::strategy) fn candidate_execution_matches_spec(
    candidate: &CandidateSummary,
    spec: &StrategyCandidateSpec,
) -> bool {
    let Some(expected_family) = expected_execution_family(spec) else {
        return false;
    };
    !candidate.execution_attempts.is_empty()
        && candidate.execution_attempts.iter().all(|attempt| {
            attempt.complete
                && !attempt.receipts.is_empty()
                && attempt.receipts.iter().all(|receipt| receipt_matches_spec(receipt, spec, expected_family))
        })
}

fn receipt_matches_spec(
    receipt: &crate::types::StrategyDesyncExecutionReceipt,
    spec: &StrategyCandidateSpec,
    expected_family: crate::types::StrategyExecutionFamily,
) -> bool {
    if receipt.disposition != crate::types::StrategyExecutionDisposition::Applied
        || receipt.configured_family != Some(expected_family)
        || receipt.effective_family != Some(expected_family)
        || receipt.fallback_reason.is_some()
        || receipt.terminal_reason.is_some()
    {
        return false;
    }
    let expected_transport = if is_quic_execution_family(expected_family) {
        crate::types::StrategyExecutionTransport::Udp
    } else {
        crate::types::StrategyExecutionTransport::Tcp
    };
    if receipt.transport != expected_transport {
        return false;
    }
    if receipt.planned_steps == 0
        || receipt.attempted_actions == 0
        || receipt.completed_actions != receipt.attempted_actions
        || receipt.real_writes_committed == 0
        || receipt.payload_bytes_committed == 0
        || (!is_quic_execution_family(expected_family) && !receipt_matches_tls_prelude(receipt, spec))
    {
        return false;
    }
    if !matches!(
        expected_family,
        crate::types::StrategyExecutionFamily::Split | crate::types::StrategyExecutionFamily::TlsRecordSplit
    ) {
        return if is_quic_execution_family(expected_family) {
            receipt.marker_base.is_none()
                && receipt.resolved_offset.is_none()
                && receipt.completed_awaits == 0
                && receipt_has_no_tls_prelude(receipt)
                && receipt.real_writes_committed > 0
                && receipt.payload_bytes_committed > 0
                && expected_udp_ipv6_extension_profile(spec)
                    .is_some_and(|expected| receipt.udp_ipv6_extension_profile == expected)
        } else {
            receipt_matches_primary_tcp_marker(receipt, spec)
        };
    }
    let Some(split_step) = spec.config.chains.tcp_steps.iter().find(|step| step.kind == "split") else {
        return false;
    };
    let Some((marker_base, marker_delta)) = parse_expected_marker(&split_step.marker) else {
        return false;
    };
    receipt.marker_base == Some(marker_base)
        && receipt.marker_delta == Some(marker_delta)
        && receipt.resolved_offset.is_some()
        && receipt.planned_steps > 0
        && receipt.attempted_actions >= 3
        && receipt.completed_actions >= 3
        && receipt.real_writes_committed >= 2
        && receipt.completed_awaits >= 1
        && receipt.payload_bytes_committed > 0
}

fn receipt_has_no_tls_prelude(receipt: &crate::types::StrategyDesyncExecutionReceipt) -> bool {
    !receipt.tls_record_prelude_applied
        && receipt.tls_prelude_configured_count == 0
        && receipt.tls_prelude_applied_count == 0
        && receipt.tls_prelude_kind.is_none()
        && receipt.tls_prelude_marker_base.is_none()
        && receipt.tls_prelude_marker_delta.is_none()
        && receipt.tls_prelude_resolved_offset.is_none()
}

fn expected_udp_ipv6_extension_profile(
    spec: &StrategyCandidateSpec,
) -> Option<Option<crate::types::StrategyUdpIpv6ExtensionProfile>> {
    use crate::types::StrategyUdpIpv6ExtensionProfile as Profile;
    match spec.config.chains.udp_steps.first()?.ipv6_extension_profile.as_str() {
        "" | "none" => Some(None),
        "hopByHop" => Some(Some(Profile::HopByHop)),
        "hopByHop2" => Some(Some(Profile::HopByHop2)),
        "destOpt" => Some(Some(Profile::DestinationOptions)),
        "hopByHopDestOpt" => Some(Some(Profile::HopByHopDestinationOptions)),
        _ => None,
    }
}

fn receipt_matches_tls_prelude(
    receipt: &crate::types::StrategyDesyncExecutionReceipt,
    spec: &StrategyCandidateSpec,
) -> bool {
    let mut preludes =
        spec.config.chains.tcp_steps.iter().filter(|step| matches!(step.kind.as_str(), "tlsrec" | "tlsrandrec"));
    let Some(prelude) = preludes.next() else {
        return receipt_has_no_tls_prelude(receipt);
    };
    if preludes.next().is_some() {
        return false;
    }
    let Some((marker_base, marker_delta)) = parse_expected_marker(&prelude.marker) else {
        return false;
    };
    let expected_kind = match prelude.kind.as_str() {
        "tlsrec" => crate::types::StrategyTlsPreludeKind::TlsRec,
        "tlsrandrec" => crate::types::StrategyTlsPreludeKind::TlsRandRec,
        _ => return false,
    };
    receipt.tls_record_prelude_applied
        && receipt.tls_prelude_configured_count == 1
        && receipt.tls_prelude_applied_count == 1
        && receipt.tls_prelude_kind == Some(expected_kind)
        && receipt.tls_prelude_marker_base == Some(marker_base)
        && receipt.tls_prelude_marker_delta == Some(marker_delta)
        && receipt.tls_prelude_resolved_offset.is_some()
}

fn receipt_matches_primary_tcp_marker(
    receipt: &crate::types::StrategyDesyncExecutionReceipt,
    spec: &StrategyCandidateSpec,
) -> bool {
    let primary =
        spec.config.chains.tcp_steps.iter().find(|step| !matches!(step.kind.as_str(), "tlsrec" | "tlsrandrec"));
    let Some(primary) = primary else {
        return receipt.marker_base.is_none() && receipt.resolved_offset.is_none();
    };
    if primary.marker.is_empty() {
        return receipt.marker_base.is_none() && receipt.resolved_offset.is_none();
    }
    let Some((marker_base, marker_delta)) = parse_expected_marker(&primary.marker) else {
        return false;
    };
    receipt.marker_base == Some(marker_base)
        && receipt.marker_delta == Some(marker_delta)
        && receipt.resolved_offset.is_some()
}

fn parse_expected_marker(marker: &str) -> Option<(crate::types::StrategyOffsetMarkerBase, i16)> {
    use crate::types::StrategyOffsetMarkerBase as Base;
    if let Ok(absolute) = marker.parse::<i16>() {
        return Some((Base::Absolute, absolute));
    }
    let split_index = marker.char_indices().skip(1).find_map(|(index, ch)| matches!(ch, '+' | '-').then_some(index));
    let (base, delta) = if let Some(index) = split_index {
        let (base, delta) = marker.split_at(index);
        (base, delta.parse::<i16>().ok()?)
    } else {
        (marker, 0)
    };
    let base = match base {
        "abs" => Base::Absolute,
        "end" => Base::PayloadEnd,
        "mid" => Base::PayloadMid,
        "rand" => Base::PayloadRand,
        "host" => Base::Host,
        "endhost" => Base::EndHost,
        "midhost" => Base::HostMid,
        "randhost" => Base::HostRand,
        "sld" => Base::Sld,
        "midsld" => Base::MidSld,
        "endsld" => Base::EndSld,
        "method" => Base::Method,
        "extlen" => Base::ExtLen,
        "echext" => Base::EchExt,
        "sniext" => Base::SniExt,
        "adaptive" => Base::Adaptive,
        _ => return None,
    };
    Some((base, delta))
}

fn expected_execution_family(spec: &StrategyCandidateSpec) -> Option<crate::types::StrategyExecutionFamily> {
    use crate::types::StrategyExecutionFamily as Family;
    if let Some(step) = spec.config.chains.udp_steps.first() {
        return match step.kind.as_str() {
            "fake_burst" => Some(Family::QuicBurst),
            "quic_sni_split" => Some(Family::QuicSniSplit),
            "quic_fake_version" => Some(Family::QuicFakeVersion),
            "quic_crypto_split" => Some(Family::QuicCryptoSplit),
            "quic_padding_ladder" => Some(Family::QuicPaddingLadder),
            "quic_version_negotiation_decoy" => Some(Family::QuicVersionNegotiationDecoy),
            "quic_multi_initial_realistic" => Some(Family::QuicMultiInitialRealistic),
            "dummy_prepend" => Some(Family::QuicDummyPrepend),
            "ipfrag2_udp" => Some(Family::QuicIpFragment2),
            _ => None,
        };
    }
    let has_tls_prelude =
        spec.config.chains.tcp_steps.iter().any(|step| matches!(step.kind.as_str(), "tlsrec" | "tlsrandrec"));
    let primary =
        spec.config.chains.tcp_steps.iter().find(|step| !matches!(step.kind.as_str(), "tlsrec" | "tlsrandrec"));
    match primary.map(|step| step.kind.as_str()) {
        Some("split" | "syndata") => Some(if has_tls_prelude { Family::TlsRecordSplit } else { Family::Split }),
        Some("seqovl") => Some(if has_tls_prelude { Family::TlsRecordSeqOverlap } else { Family::SeqOverlap }),
        Some("multidisorder") => {
            Some(if has_tls_prelude { Family::TlsRecordMultiDisorder } else { Family::MultiDisorder })
        }
        Some("disorder") => Some(Family::Disorder),
        Some("oob") => Some(Family::OutOfBand),
        Some("disoob") => Some(Family::DisorderedOutOfBand),
        Some("fake") => Some(Family::Fake),
        Some("fakedsplit") => Some(Family::FakeSplit),
        Some("fakeddisorder") => Some(Family::FakeDisorder),
        Some("hostfake") => Some(Family::HostFake),
        Some("ipfrag2") => Some(Family::IpFragment2),
        Some("fakerst") => Some(Family::FakeRst),
        None if has_tls_prelude => Some(Family::TlsRecord),
        _ => None,
    }
}

fn is_quic_execution_family(family: crate::types::StrategyExecutionFamily) -> bool {
    use crate::types::StrategyExecutionFamily as Family;
    matches!(
        family,
        Family::QuicBurst
            | Family::QuicSniSplit
            | Family::QuicFakeVersion
            | Family::QuicCryptoSplit
            | Family::QuicPaddingLadder
            | Family::QuicVersionNegotiationDecoy
            | Family::QuicMultiInitialRealistic
            | Family::QuicDummyPrepend
            | Family::QuicIpFragment2
    )
}

pub(in crate::engine::runners::strategy) fn select_safe_or_baseline_candidate_index(
    candidates: &[CandidateSummary],
    specs: &[StrategyCandidateSpec],
    fake_ttl_available: bool,
    tcp_fast_open_available: bool,
    ipfrag_caps: ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities,
) -> Option<usize> {
    select_promotable_candidate_index(candidates, specs, fake_ttl_available, tcp_fast_open_available, ipfrag_caps)
}

fn candidate_has_promotable_result(candidate: &CandidateSummary) -> bool {
    if candidate.skipped
        || (candidate.desync_execution_required && !candidate.execution_evidence_complete)
        || candidate.succeeded_targets == 0
        || candidate.total_targets == 0
    {
        return false;
    }
    !matches!(
        candidate.outcome.as_str(),
        "failed" | "skipped" | "not_applicable" | "eliminated" | "unverified_execution"
    )
}

fn candidate_is_promotable_for_path(
    spec: &StrategyCandidateSpec,
    fake_ttl_available: bool,
    tcp_fast_open_available: bool,
    ipfrag_caps: ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities,
) -> bool {
    if spec.requires_fake_ttl && !fake_ttl_available {
        return false;
    }
    if spec.requires_tcp_fast_open && !tcp_fast_open_available {
        return false;
    }
    if matches!(spec.emitter_tier, crate::types::StrategyEmitterTier::LabDiagnosticsOnly) {
        return false;
    }
    spec.requires_capabilities
        .iter()
        .all(|&capability| capability_available(capability, fake_ttl_available, ipfrag_caps))
}

#[cfg(test)]
mod marker_tests {
    use super::parse_expected_marker;
    use crate::types::StrategyOffsetMarkerBase;

    #[test]
    fn numeric_markers_are_absolute_offsets() {
        assert_eq!(parse_expected_marker("0"), Some((StrategyOffsetMarkerBase::Absolute, 0)));
        assert_eq!(parse_expected_marker("1"), Some((StrategyOffsetMarkerBase::Absolute, 1)));
        assert_eq!(parse_expected_marker("-1"), Some((StrategyOffsetMarkerBase::Absolute, -1)));
    }
}
