use crate::candidates::StrategyCandidateSpec;
use crate::types::{
    ProbeResult, StrategyDesyncExecutionReceipt, StrategyExecutionDisposition, StrategyExecutionFamily,
    StrategyExecutionTransport, StrategyFallbackReason, StrategyOffsetMarkerBase,
    StrategyProbeAttemptExecutionEvidence, StrategyProbeCandidateSummary, StrategyProbeObservationRole,
    StrategyProbeResponseStage, StrategyProbeRouteFeature, StrategyProbeRuntimeTerminalStatus, StrategyTerminalReason,
    StrategyTlsPreludeKind,
};
use crate::{CandidateAttemptCorrelationId, CandidateDesyncExecutionReceipt, CandidateRuntimeExecutionEvidence};

use super::score_state::CandidateScore;
use super::{candidate_notes, candidate_proxy_config_json};

#[derive(Debug)]
pub struct CandidateExecution {
    pub summary: StrategyProbeCandidateSummary,
    pub results: Vec<ProbeResult>,
    pub cancelled: bool,
    pub(crate) attempts: Vec<CandidateAttemptExecution>,
    pub(crate) execution_evidence_complete: bool,
}

#[derive(Debug)]
pub(crate) struct CandidateAttemptExecution {
    pub(crate) token: CandidateAttemptCorrelationId,
    pub(crate) success: bool,
    pub(crate) receipts: Vec<CandidateDesyncExecutionReceipt>,
}

impl CandidateExecution {
    pub(crate) fn attach_terminal_evidence(
        &mut self,
        generation: u64,
        receipt: &crate::CandidateRuntimeTerminalReceipt,
    ) {
        self.summary.runtime_terminal_status = project_terminal_status(receipt.terminal_status());
        if receipt.generation() != generation {
            self.execution_evidence_complete = false;
            self.summary.execution_evidence_complete = false;
            return;
        }
        if !self.summary.desync_execution_required {
            self.execution_evidence_complete = !receipt.execution_evidence_overflowed()
                && matches!(receipt.terminal_status(), crate::CandidateRuntimeTerminalStatus::CleanShutdown);
            self.summary.execution_evidence_complete = self.execution_evidence_complete;
            return;
        }
        if receipt.execution_evidence_overflowed()
            || !matches!(
                receipt.terminal_status(),
                crate::CandidateRuntimeTerminalStatus::CleanShutdown
                    | crate::CandidateRuntimeTerminalStatus::ForcedAbort
            )
        {
            self.execution_evidence_complete = false;
            return;
        }
        let mut invalid_or_unknown_evidence = false;
        for evidence in receipt.execution_evidence() {
            let CandidateRuntimeExecutionEvidence::Desync(evidence) = evidence;
            if evidence.generation != generation
                || !evidence.attempt_token.is_evaluable()
                || !evidence.is_valid()
                || project_execution_receipt(evidence).is_none()
            {
                invalid_or_unknown_evidence = true;
                continue;
            }
            if let Some(attempt) = self.attempts.iter_mut().find(|attempt| attempt.token == evidence.attempt_token) {
                attempt.receipts.push(evidence.clone());
            } else {
                invalid_or_unknown_evidence = true;
            }
        }
        for attempt in &mut self.attempts {
            attempt.receipts.sort_by_key(|evidence| evidence.connection_ordinal);
        }
        self.execution_evidence_complete = !invalid_or_unknown_evidence
            && !self.attempts.is_empty()
            && self.attempts.iter().all(|attempt| !attempt.receipts.is_empty())
            && self.attempts.iter().all(|attempt| {
                attempt
                    .receipts
                    .iter()
                    .enumerate()
                    .all(|(index, evidence)| evidence.connection_ordinal == (index + 1) as u64)
            })
            && self
                .attempts
                .iter()
                .all(|attempt| attempt.receipts.iter().all(|evidence| evidence.generation == generation));
        self.summary.execution_evidence_complete = self.execution_evidence_complete;
        self.summary.execution_attempts = self
            .attempts
            .iter()
            .map(|attempt| StrategyProbeAttemptExecutionEvidence {
                probe_succeeded: attempt.success,
                complete: !attempt.receipts.is_empty(),
                response_stage: if attempt.success {
                    StrategyProbeResponseStage::ResponseObserved
                } else {
                    StrategyProbeResponseStage::ResponseNotObserved
                },
                receipts: attempt.receipts.iter().filter_map(project_execution_receipt).collect(),
            })
            .collect();
    }

    pub(crate) fn has_applied_success_evidence(&self) -> bool {
        self.execution_evidence_complete
            && self.attempts.iter().any(|attempt| {
                attempt.success
                    && !attempt.receipts.is_empty()
                    && attempt
                        .receipts
                        .iter()
                        .all(|evidence| evidence.disposition == crate::CandidateDesyncExecutionDisposition::Applied)
            })
    }
}

pub fn build_candidate_execution(
    spec: &StrategyCandidateSpec,
    score: CandidateScore,
    quality_floor: usize,
) -> CandidateExecution {
    let outcome = if score.is_full_success() {
        "success"
    } else if score.succeeded_targets > 0 && score.quality_score >= quality_floor {
        "partial"
    } else {
        "failed"
    };
    let rationale = format!("{} of {} targets succeeded", score.succeeded_targets, score.total_targets);
    let domain_outcomes = score.domain_outcomes();

    let attempts = score
        .attempts
        .iter()
        .map(|(token, success)| CandidateAttemptExecution {
            token: token.clone(),
            success: *success,
            receipts: Vec::new(),
        })
        .collect();
    let desync_execution_required = candidate_requires_desync_execution_evidence(spec);
    let execution_evidence_complete = !desync_execution_required;
    CandidateExecution {
        summary: StrategyProbeCandidateSummary {
            id: spec.id.to_string(),
            label: spec.label.to_string(),
            family: spec.family.to_string(),
            emitter_tier: spec.emitter_tier,
            exact_emitter_requires_root: spec.exact_emitter_requires_root,
            emitter_downgraded: false,
            quic_layout_family: spec.quic_layout_family.map(str::to_string),
            outcome: outcome.to_string(),
            rationale,
            succeeded_targets: score.succeeded_targets,
            total_targets: score.total_targets,
            weighted_success_score: score.weighted_success_score,
            total_weight: score.total_weight,
            quality_score: score.quality_score,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, &[]),
            average_latency_ms: score.average_latency_ms(),
            skipped: false,
            domain_outcomes,
            observation_role: StrategyProbeObservationRole::EphemeralCandidateRawPath,
            active_snapshot_faithful: spec.active_snapshot_faithful,
            desync_execution_required,
            runtime_terminal_status: StrategyProbeRuntimeTerminalStatus::Unavailable,
            execution_evidence_complete,
            execution_attempts: Vec::new(),
            route_features: candidate_route_features(spec),
        },
        results: score.results,
        cancelled: false,
        attempts,
        execution_evidence_complete,
    }
}

pub(in crate::execution) fn cancelled_candidate_execution(
    spec: &StrategyCandidateSpec,
    score: CandidateScore,
    quality_floor: usize,
) -> CandidateExecution {
    let mut execution = build_candidate_execution(spec, score, quality_floor);
    execution.cancelled = true;
    execution
}

pub fn failed_candidate_execution(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    err: String,
) -> CandidateExecution {
    CandidateExecution {
        summary: StrategyProbeCandidateSummary {
            id: spec.id.to_string(),
            label: spec.label.to_string(),
            family: spec.family.to_string(),
            emitter_tier: spec.emitter_tier,
            exact_emitter_requires_root: spec.exact_emitter_requires_root,
            emitter_downgraded: false,
            quic_layout_family: spec.quic_layout_family.map(str::to_string),
            outcome: "failed".to_string(),
            rationale: err,
            succeeded_targets: 0,
            total_targets,
            weighted_success_score: 0,
            total_weight: total_targets * total_weight_per_target,
            quality_score: 0,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, &[]),
            average_latency_ms: None,
            skipped: false,
            domain_outcomes: vec![],
            observation_role: StrategyProbeObservationRole::EphemeralCandidateRawPath,
            active_snapshot_faithful: spec.active_snapshot_faithful,
            desync_execution_required: candidate_requires_desync_execution_evidence(spec),
            runtime_terminal_status: StrategyProbeRuntimeTerminalStatus::Unavailable,
            execution_evidence_complete: false,
            execution_attempts: Vec::new(),
            route_features: candidate_route_features(spec),
        },
        results: Vec::new(),
        cancelled: false,
        attempts: Vec::new(),
        execution_evidence_complete: false,
    }
}

pub fn not_applicable_candidate_execution(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    rationale: &str,
) -> CandidateExecution {
    CandidateExecution {
        summary: StrategyProbeCandidateSummary {
            id: spec.id.to_string(),
            label: spec.label.to_string(),
            family: spec.family.to_string(),
            emitter_tier: spec.emitter_tier,
            exact_emitter_requires_root: spec.exact_emitter_requires_root,
            emitter_downgraded: false,
            quic_layout_family: spec.quic_layout_family.map(str::to_string),
            outcome: "not_applicable".to_string(),
            rationale: rationale.to_string(),
            succeeded_targets: 0,
            total_targets,
            weighted_success_score: 0,
            total_weight: total_targets * total_weight_per_target,
            quality_score: 0,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, &[rationale]),
            average_latency_ms: None,
            skipped: false,
            domain_outcomes: vec![],
            observation_role: StrategyProbeObservationRole::EphemeralCandidateRawPath,
            active_snapshot_faithful: spec.active_snapshot_faithful,
            desync_execution_required: candidate_requires_desync_execution_evidence(spec),
            runtime_terminal_status: StrategyProbeRuntimeTerminalStatus::Unavailable,
            execution_evidence_complete: false,
            execution_attempts: Vec::new(),
            route_features: candidate_route_features(spec),
        },
        results: Vec::new(),
        cancelled: false,
        attempts: Vec::new(),
        execution_evidence_complete: false,
    }
}

pub fn skipped_candidate_summary(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    rationale: &str,
) -> StrategyProbeCandidateSummary {
    StrategyProbeCandidateSummary {
        id: spec.id.to_string(),
        label: spec.label.to_string(),
        family: spec.family.to_string(),
        emitter_tier: spec.emitter_tier,
        exact_emitter_requires_root: spec.exact_emitter_requires_root,
        emitter_downgraded: false,
        quic_layout_family: spec.quic_layout_family.map(str::to_string),
        outcome: "skipped".to_string(),
        rationale: rationale.to_string(),
        succeeded_targets: 0,
        total_targets,
        weighted_success_score: 0,
        total_weight: total_targets * total_weight_per_target,
        quality_score: 0,
        proxy_config_json: candidate_proxy_config_json(spec),
        notes: candidate_notes(spec, &[rationale]),
        average_latency_ms: None,
        skipped: true,
        domain_outcomes: vec![],
        observation_role: StrategyProbeObservationRole::EphemeralCandidateRawPath,
        active_snapshot_faithful: spec.active_snapshot_faithful,
        desync_execution_required: candidate_requires_desync_execution_evidence(spec),
        runtime_terminal_status: StrategyProbeRuntimeTerminalStatus::Unavailable,
        execution_evidence_complete: false,
        execution_attempts: Vec::new(),
        route_features: candidate_route_features(spec),
    }
}

pub fn eliminated_candidate_summary(
    spec: &StrategyCandidateSpec,
    qualifier_succeeded: usize,
    qualifier_total: usize,
    total_weight_per_target: usize,
) -> StrategyProbeCandidateSummary {
    let rationale = format!("Eliminated in qualifier: {qualifier_succeeded}/{qualifier_total} succeeded");

    StrategyProbeCandidateSummary {
        id: spec.id.to_string(),
        label: spec.label.to_string(),
        family: spec.family.to_string(),
        emitter_tier: spec.emitter_tier,
        exact_emitter_requires_root: spec.exact_emitter_requires_root,
        emitter_downgraded: false,
        quic_layout_family: spec.quic_layout_family.map(str::to_string),
        outcome: "eliminated".to_string(),
        rationale: rationale.clone(),
        succeeded_targets: qualifier_succeeded,
        total_targets: qualifier_total,
        weighted_success_score: 0,
        total_weight: qualifier_total * total_weight_per_target,
        quality_score: 0,
        proxy_config_json: candidate_proxy_config_json(spec),
        notes: candidate_notes(spec, &[&rationale]),
        average_latency_ms: None,
        skipped: false,
        domain_outcomes: vec![],
        observation_role: StrategyProbeObservationRole::EphemeralCandidateRawPath,
        active_snapshot_faithful: spec.active_snapshot_faithful,
        desync_execution_required: candidate_requires_desync_execution_evidence(spec),
        runtime_terminal_status: StrategyProbeRuntimeTerminalStatus::Unavailable,
        execution_evidence_complete: false,
        execution_attempts: Vec::new(),
        route_features: candidate_route_features(spec),
    }
}

fn candidate_requires_desync_execution_evidence(spec: &StrategyCandidateSpec) -> bool {
    !matches!(spec.id, "baseline_plain_direct" | "quic_disabled")
}

fn project_terminal_status(status: crate::CandidateRuntimeTerminalStatus) -> StrategyProbeRuntimeTerminalStatus {
    match status {
        crate::CandidateRuntimeTerminalStatus::CleanShutdown => StrategyProbeRuntimeTerminalStatus::CleanShutdown,
        crate::CandidateRuntimeTerminalStatus::ForcedAbort => StrategyProbeRuntimeTerminalStatus::ForcedAbort,
        crate::CandidateRuntimeTerminalStatus::RuntimeFailed => StrategyProbeRuntimeTerminalStatus::RuntimeFailed,
        crate::CandidateRuntimeTerminalStatus::RuntimePanicked => StrategyProbeRuntimeTerminalStatus::RuntimePanicked,
        crate::CandidateRuntimeTerminalStatus::AlreadyJoined => StrategyProbeRuntimeTerminalStatus::Unavailable,
    }
}

fn candidate_route_features(spec: &StrategyCandidateSpec) -> Vec<StrategyProbeRouteFeature> {
    let config = &spec.config;
    let mut features = Vec::new();
    if config.upstream_relay.enabled {
        features.push(StrategyProbeRouteFeature::UpstreamRelay);
    }
    if config.warp.enabled {
        features.push(StrategyProbeRouteFeature::Warp);
    }
    if config.ws_tunnel.enabled {
        features.push(StrategyProbeRouteFeature::WebSocketTunnel);
    }
    if config.destination_routing != <_>::default() {
        features.push(StrategyProbeRouteFeature::DestinationRouting);
    }
    if config.chains.tcp_rotation.is_some() {
        features.push(StrategyProbeRouteFeature::TcpRotation);
    }
    if config.adaptive_fallback.enabled || config.adaptive_fallback.strategy_evolution {
        features.push(StrategyProbeRouteFeature::AdaptiveFallback);
    }
    if config.chains.group_activation_filter.is_some() {
        features.push(StrategyProbeRouteFeature::GroupActivationFilter);
    }
    features
}

fn project_execution_receipt(receipt: &CandidateDesyncExecutionReceipt) -> Option<StrategyDesyncExecutionReceipt> {
    let configured_family = match receipt.configured_family {
        Some(family) => Some(project_strategy_family(family)?),
        None => None,
    };
    let effective_family = match receipt.effective_family {
        Some(family) => Some(project_strategy_family(family)?),
        None => None,
    };
    let marker_base = match receipt.marker_base {
        Some(base) => Some(project_marker_base(base)?),
        None => None,
    };
    let tls_prelude_kind = match receipt.tls_prelude_kind {
        Some(kind) => Some(project_tls_prelude_kind(kind)?),
        None => None,
    };
    let tls_prelude_marker_base = match receipt.tls_prelude_marker_base {
        Some(base) => Some(project_marker_base(base)?),
        None => None,
    };
    let fallback_reason = match receipt.fallback_reason {
        Some(reason) => Some(project_fallback_reason(reason)?),
        None => None,
    };
    let udp_ipv6_extension_profile = match receipt.udp_ipv6_extension_profile {
        Some(profile) => Some(project_udp_ipv6_extension_profile(profile)?),
        None => None,
    };
    let terminal_reason = match receipt.terminal_reason {
        Some(reason) => Some(project_terminal_reason(reason)?),
        None => None,
    };
    Some(StrategyDesyncExecutionReceipt {
        connection_ordinal: receipt.connection_ordinal.min(u16::MAX as u64) as u16,
        transport: match receipt.transport {
            ripdpi_runtime_api::DesyncExecutionTransport::Tcp => StrategyExecutionTransport::Tcp,
            ripdpi_runtime_api::DesyncExecutionTransport::Udp => StrategyExecutionTransport::Udp,
            _ => return None,
        },
        disposition: match receipt.disposition {
            crate::CandidateDesyncExecutionDisposition::Applied => StrategyExecutionDisposition::Applied,
            crate::CandidateDesyncExecutionDisposition::ActivationSkipped => {
                StrategyExecutionDisposition::ActivationSkipped
            }
            crate::CandidateDesyncExecutionDisposition::PlanFailedPlainFallback => {
                StrategyExecutionDisposition::PlanFailedPlainFallback
            }
            crate::CandidateDesyncExecutionDisposition::ExecutionFailed => {
                StrategyExecutionDisposition::ExecutionFailed
            }
        },
        configured_family,
        effective_family,
        marker_base,
        marker_delta: receipt.marker_delta,
        resolved_offset: receipt.resolved_offset,
        planned_steps: receipt.planned_steps,
        attempted_actions: receipt.attempted_actions,
        completed_actions: receipt.completed_actions,
        real_writes_committed: receipt.real_writes_committed.min(u16::MAX as usize) as u16,
        completed_awaits: receipt.completed_awaits.min(u16::MAX as usize) as u16,
        payload_bytes_committed: receipt.payload_bytes_committed.min(u16::MAX as usize) as u16,
        tls_record_prelude_applied: receipt.tls_record_prelude_applied,
        tls_prelude_configured_count: receipt.tls_prelude_configured_count,
        tls_prelude_applied_count: receipt.tls_prelude_applied_count,
        tls_prelude_kind,
        tls_prelude_marker_base,
        tls_prelude_marker_delta: receipt.tls_prelude_marker_delta,
        tls_prelude_resolved_offset: receipt.tls_prelude_resolved_offset,
        udp_ipv6_extension_profile,
        fallback_reason,
        terminal_reason,
    })
}

fn project_strategy_family(family: ripdpi_runtime_api::DesyncStrategyFamily) -> Option<StrategyExecutionFamily> {
    use ripdpi_runtime_api::DesyncStrategyFamily as Source;
    Some(match family {
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

fn project_marker_base(base: ripdpi_runtime_api::DesyncOffsetMarkerBase) -> Option<StrategyOffsetMarkerBase> {
    use ripdpi_runtime_api::DesyncOffsetMarkerBase as Source;
    Some(match base {
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

fn project_tls_prelude_kind(kind: ripdpi_runtime_api::DesyncTlsPreludeKind) -> Option<StrategyTlsPreludeKind> {
    match kind {
        ripdpi_runtime_api::DesyncTlsPreludeKind::TlsRec => Some(StrategyTlsPreludeKind::TlsRec),
        ripdpi_runtime_api::DesyncTlsPreludeKind::TlsRandRec => Some(StrategyTlsPreludeKind::TlsRandRec),
        _ => None,
    }
}

fn project_udp_ipv6_extension_profile(
    profile: ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile,
) -> Option<crate::types::StrategyUdpIpv6ExtensionProfile> {
    use crate::types::StrategyUdpIpv6ExtensionProfile as Target;
    match profile {
        ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile::HopByHop => Some(Target::HopByHop),
        ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile::HopByHop2 => Some(Target::HopByHop2),
        ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile::DestinationOptions => Some(Target::DestinationOptions),
        ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile::HopByHopDestinationOptions => {
            Some(Target::HopByHopDestinationOptions)
        }
        ripdpi_runtime_api::DesyncUdpIpv6ExtensionProfile::Unknown => None,
        _ => None,
    }
}

fn project_fallback_reason(reason: ripdpi_runtime_api::DesyncFallbackReason) -> Option<StrategyFallbackReason> {
    match reason {
        ripdpi_runtime_api::DesyncFallbackReason::AndroidTtlUnavailable => {
            Some(StrategyFallbackReason::AndroidTtlUnavailable)
        }
        ripdpi_runtime_api::DesyncFallbackReason::StrategyFamilyFallback => {
            Some(StrategyFallbackReason::StrategyFamilyFallback)
        }
        _ => None,
    }
}

fn project_terminal_reason(reason: ripdpi_runtime_api::DesyncTerminalReason) -> Option<StrategyTerminalReason> {
    match reason {
        ripdpi_runtime_api::DesyncTerminalReason::Transport => Some(StrategyTerminalReason::Transport),
        ripdpi_runtime_api::DesyncTerminalReason::StrategyExecution => Some(StrategyTerminalReason::StrategyExecution),
        ripdpi_runtime_api::DesyncTerminalReason::Planning => Some(StrategyTerminalReason::Planning),
        _ => None,
    }
}
