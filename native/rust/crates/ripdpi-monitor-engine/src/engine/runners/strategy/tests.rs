use ripdpi_monitor_adapter::failure::FailureClass;
use ripdpi_monitor_adapter::proxy_config::{
    ProxyConfigPayload, ProxyUiConfig, ProxyUiUdpChainStep, parse_proxy_config_json,
};

use super::FamilyFailureTracker;

#[test]
fn family_tracker_blocks_after_threshold_2() {
    let mut tracker = FamilyFailureTracker::new(2);
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() != Some("hostfake"));
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() == Some("hostfake"));
}

#[test]
fn family_tracker_blocks_after_threshold_4() {
    let mut tracker = FamilyFailureTracker::new(4);
    tracker.record("hostfake", true);
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() != Some("hostfake"));
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() != Some("hostfake"));
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() == Some("hostfake"));
}

#[test]
fn family_tracker_resets_on_success() {
    let mut tracker = FamilyFailureTracker::new(2);
    tracker.record("hostfake", true);
    tracker.record("hostfake", false); // success resets consecutive count and blocked
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() != Some("hostfake")); // only 1 consecutive failure after reset
}

#[test]
fn family_tracker_resets_on_different_family() {
    let mut tracker = FamilyFailureTracker::new(2);
    tracker.record("hostfake", true);
    tracker.record("split", true); // different family resets hostfake consecutive counter
    tracker.record("hostfake", true);
    assert!(tracker.blocked_family() != Some("hostfake")); // only 1 consecutive for hostfake
}

use super::{
    baseline_has_tls_ech_only, baseline_has_tls_version_split, baseline_supports_ech_candidates,
    ordered_follow_up_tcp_candidates, pilot_bucket_label, resolve_recommended_proxy_config_json,
    resolve_strategy_probe_audit_assessment, select_promotable_candidate_index,
    select_safe_or_baseline_candidate_index, stratified_pilot_targets, support::candidate_execution_matches_spec,
};
use crate::candidates::{CandidateEligibility, build_tcp_candidates};
use crate::classification::{interleave_candidate_families, reorder_tcp_candidates_for_failure};
use crate::types::{
    DomainTarget, ProbeDetail, ProbeResult, StrategyDesyncExecutionReceipt, StrategyExecutionDisposition,
    StrategyExecutionFamily, StrategyProbeAttemptExecutionEvidence, StrategyProbeAuditAssessment,
    StrategyProbeAuditConfidenceLevel, StrategyProbeCandidateSummary, StrategyProbeRecommendation,
};
use crate::util::STRATEGY_PROBE_SUITE_FULL_MATRIX_V1;

fn s(v: &str) -> String {
    v.to_string()
}

fn quic_candidate_summary(proxy_config_json: Option<String>) -> StrategyProbeCandidateSummary {
    StrategyProbeCandidateSummary {
        id: s("quic_realistic_burst"),
        label: s("QUIC realistic burst"),
        family: s("quic_burst"),
        emitter_tier: crate::types::StrategyEmitterTier::NonRootProduction,
        exact_emitter_requires_root: false,
        emitter_downgraded: false,
        quic_layout_family: None,
        outcome: s("success"),
        rationale: s("Recovered QUIC"),
        succeeded_targets: 1,
        total_targets: 1,
        weighted_success_score: 2,
        total_weight: 2,
        quality_score: 4,
        proxy_config_json,
        notes: Vec::new(),
        average_latency_ms: Some(220),
        skipped: false,
        domain_outcomes: vec![],
        observation_role: crate::types::StrategyProbeObservationRole::EphemeralCandidateRawPath,
        active_snapshot_faithful: true,
        desync_execution_required: true,
        runtime_terminal_status: crate::types::StrategyProbeRuntimeTerminalStatus::Unavailable,
        execution_evidence_complete: false,
        execution_attempts: vec![],
        route_features: vec![],
    }
}

fn strategy_candidate_summary(
    id: &str,
    family: &str,
    weighted_success_score: usize,
    total_weight: usize,
    succeeded_targets: usize,
    total_targets: usize,
    skipped: bool,
    outcome: &str,
) -> StrategyProbeCandidateSummary {
    let execution_attempts = test_execution_family(family).map_or_else(Vec::new, |execution_family| {
        vec![StrategyProbeAttemptExecutionEvidence {
            probe_succeeded: succeeded_targets > 0,
            complete: true,
            response_stage: if succeeded_targets > 0 {
                crate::types::StrategyProbeResponseStage::ResponseObserved
            } else {
                crate::types::StrategyProbeResponseStage::ResponseNotObserved
            },
            receipts: vec![StrategyDesyncExecutionReceipt {
                connection_ordinal: 1,
                transport: crate::types::StrategyExecutionTransport::Tcp,
                disposition: StrategyExecutionDisposition::Applied,
                configured_family: Some(execution_family),
                effective_family: Some(execution_family),
                marker_base: matches!(
                    execution_family,
                    StrategyExecutionFamily::Split | StrategyExecutionFamily::TlsRecordSplit
                )
                .then_some(crate::types::StrategyOffsetMarkerBase::Host),
                marker_delta: matches!(
                    execution_family,
                    StrategyExecutionFamily::Split | StrategyExecutionFamily::TlsRecordSplit
                )
                .then_some(2),
                resolved_offset: matches!(
                    execution_family,
                    StrategyExecutionFamily::Split | StrategyExecutionFamily::TlsRecordSplit
                )
                .then_some(16),
                planned_steps: 1,
                attempted_actions: if matches!(
                    execution_family,
                    StrategyExecutionFamily::Split | StrategyExecutionFamily::TlsRecordSplit
                ) {
                    3
                } else {
                    1
                },
                completed_actions: if matches!(
                    execution_family,
                    StrategyExecutionFamily::Split | StrategyExecutionFamily::TlsRecordSplit
                ) {
                    3
                } else {
                    1
                },
                real_writes_committed: if matches!(
                    execution_family,
                    StrategyExecutionFamily::Split | StrategyExecutionFamily::TlsRecordSplit
                ) {
                    2
                } else {
                    1
                },
                completed_awaits: u16::from(matches!(
                    execution_family,
                    StrategyExecutionFamily::Split | StrategyExecutionFamily::TlsRecordSplit
                )),
                payload_bytes_committed: 1,
                tls_record_prelude_applied: execution_family == StrategyExecutionFamily::TlsRecordSplit,
                tls_prelude_configured_count: u16::from(execution_family == StrategyExecutionFamily::TlsRecordSplit),
                tls_prelude_applied_count: u16::from(execution_family == StrategyExecutionFamily::TlsRecordSplit),
                tls_prelude_kind: (execution_family == StrategyExecutionFamily::TlsRecordSplit)
                    .then_some(crate::types::StrategyTlsPreludeKind::TlsRec),
                tls_prelude_marker_base: (execution_family == StrategyExecutionFamily::TlsRecordSplit)
                    .then_some(crate::types::StrategyOffsetMarkerBase::ExtLen),
                tls_prelude_marker_delta: (execution_family == StrategyExecutionFamily::TlsRecordSplit).then_some(0),
                tls_prelude_resolved_offset: (execution_family == StrategyExecutionFamily::TlsRecordSplit)
                    .then_some(42),
                udp_ipv6_extension_profile: None,
                fallback_reason: None,
                terminal_reason: None,
            }],
        }]
    });
    StrategyProbeCandidateSummary {
        id: s(id),
        label: id.replace('_', " "),
        family: s(family),
        emitter_tier: crate::types::StrategyEmitterTier::NonRootProduction,
        exact_emitter_requires_root: false,
        emitter_downgraded: false,
        quic_layout_family: None,
        outcome: s(outcome),
        rationale: s("candidate result"),
        succeeded_targets,
        total_targets,
        weighted_success_score,
        total_weight,
        quality_score: weighted_success_score.saturating_mul(2),
        proxy_config_json: None,
        notes: Vec::new(),
        average_latency_ms: Some(200),
        skipped,
        domain_outcomes: vec![],
        observation_role: crate::types::StrategyProbeObservationRole::EphemeralCandidateRawPath,
        active_snapshot_faithful: true,
        desync_execution_required: true,
        runtime_terminal_status: crate::types::StrategyProbeRuntimeTerminalStatus::Unavailable,
        execution_evidence_complete: true,
        execution_attempts,
        route_features: vec![],
    }
}

fn test_execution_family(family: &str) -> Option<StrategyExecutionFamily> {
    match family {
        "split" => Some(StrategyExecutionFamily::Split),
        "tlsrec_split" => Some(StrategyExecutionFamily::TlsRecordSplit),
        "tlsrec_seqovl" => Some(StrategyExecutionFamily::TlsRecordSeqOverlap),
        "disorder" => Some(StrategyExecutionFamily::Disorder),
        "hostfake" => Some(StrategyExecutionFamily::HostFake),
        _ => None,
    }
}

fn recommendation() -> StrategyProbeRecommendation {
    StrategyProbeRecommendation {
        tcp_candidate_id: s("tcp_winner"),
        tcp_candidate_label: s("tcp winner"),
        quic_candidate_id: s("quic_winner"),
        quic_candidate_label: s("quic winner"),
        quic_candidate_layout_family: None,
        rationale: s("best"),
        recommended_proxy_config_json: s("{}"),
        transport_pivot: None,
    }
}

fn baseline_https_result(outcome: &str, tls_ech_resolution_detail: &str) -> ProbeResult {
    ProbeResult {
        probe_type: s("strategy_https"),
        target: s("baseline_current · example.com"),
        outcome: s(outcome),
        details: vec![
            ProbeDetail { key: s("candidateId"), value: s("baseline_current") },
            ProbeDetail { key: s("tlsEchResolutionDetail"), value: s(tls_ech_resolution_detail) },
        ],
    }
}

fn baseline_https_result_with_cdn(outcome: &str, cdn_provider: &str) -> ProbeResult {
    ProbeResult {
        probe_type: s("strategy_https"),
        target: s("baseline_current · example.com"),
        outcome: s(outcome),
        details: vec![
            ProbeDetail { key: s("candidateId"), value: s("baseline_current") },
            ProbeDetail { key: s("tlsEchResolutionDetail"), value: s("none") },
            ProbeDetail { key: s("cdnProvider"), value: s(cdn_provider) },
        ],
    }
}

#[test]
fn resolve_recommended_proxy_config_json_prefers_winning_quic_summary_config() {
    let mut composed_config = ProxyUiConfig::default();
    composed_config.chains.tcp_steps = vec![crate::candidates::tcp_step("tlsrec", "extlen")];
    composed_config.quic.fake_profile = "realistic_initial".to_string();

    let mut fallback_config = ProxyUiConfig::default();
    fallback_config.quic.fake_profile = "compat_default".to_string();
    let fallback_quic_spec = crate::candidates::candidate_spec(
        "quic_realistic_burst",
        "QUIC realistic burst",
        "quic_burst",
        fallback_config,
    );

    let winning_quic_candidate =
        quic_candidate_summary(Some(crate::candidates::strategy_probe_config_json(&composed_config)));

    let recommended_proxy_config_json =
        resolve_recommended_proxy_config_json(&winning_quic_candidate, &fallback_quic_spec);

    match parse_proxy_config_json(&recommended_proxy_config_json).expect("parse ui config") {
        ProxyConfigPayload::Ui { config, .. } => {
            assert_eq!(config.chains.tcp_steps.len(), 1);
            assert_eq!(config.chains.tcp_steps[0].kind, "tlsrec");
            assert_eq!(config.quic.fake_profile, "realistic_initial");
        }
        ProxyConfigPayload::CommandLine { .. } => panic!("expected UI proxy config"),
    }
}

#[test]
fn resolve_recommended_proxy_config_json_falls_back_to_quic_winner_spec_config() {
    let mut fallback_config = ProxyUiConfig::default();
    fallback_config.quic.fake_profile = "compat_default".to_string();
    let fallback_quic_spec =
        crate::candidates::candidate_spec("quic_compat_burst", "QUIC compat burst", "quic_burst", fallback_config);

    let winning_quic_candidate = quic_candidate_summary(None);

    let recommended_proxy_config_json =
        resolve_recommended_proxy_config_json(&winning_quic_candidate, &fallback_quic_spec);

    match parse_proxy_config_json(&recommended_proxy_config_json).expect("parse ui config") {
        ProxyConfigPayload::Ui { config, .. } => {
            assert_eq!(config.chains.tcp_steps, fallback_quic_spec.config.chains.tcp_steps);
            assert_eq!(config.quic.fake_profile, "compat_default");
        }
        ProxyConfigPayload::CommandLine { .. } => panic!("expected UI proxy config"),
    }
}

#[test]
fn promotable_winner_selection_ignores_successful_candidate_when_path_capability_missing() {
    let specs = build_tcp_candidates(&ProxyUiConfig::default());
    let summaries = vec![
        strategy_candidate_summary("disorder_host", "disorder", 12, 12, 2, 2, false, "success"),
        strategy_candidate_summary("split_host", "split", 6, 12, 1, 2, false, "partial"),
    ];
    let ipfrag_caps = ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities::default();

    let selected = select_promotable_candidate_index(&summaries, &specs, false, false, ipfrag_caps)
        .expect("split candidate should remain promotable");

    assert_eq!(summaries[selected].id, "split_host");
}

#[test]
fn promotable_winner_selection_rejects_lab_only_candidate_even_when_it_scores_highest() {
    let specs = crate::candidates::build_full_matrix_tcp_candidates(&ProxyUiConfig::default());
    let summaries = vec![
        strategy_candidate_summary("adaptive_fake_ttl", "fake", 12, 12, 2, 2, false, "success"),
        strategy_candidate_summary("split_host", "split", 6, 12, 1, 2, false, "partial"),
    ];
    let ipfrag_caps = ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities {
        raw_ipv4: true,
        raw_ipv6: true,
        tcp_repair: true,
    };

    let selected = select_promotable_candidate_index(&summaries, &specs, true, true, ipfrag_caps)
        .expect("split candidate should remain promotable");

    assert_eq!(summaries[selected].id, "split_host");
}

#[test]
fn promotable_winner_selection_rejects_seqovl_when_replacement_socket_is_missing() {
    let specs = build_tcp_candidates(&ProxyUiConfig::default());
    let summaries = vec![
        strategy_candidate_summary("tlsrec_seqovl_midsld", "tlsrec_seqovl", 12, 12, 2, 2, false, "success"),
        strategy_candidate_summary("split_host", "split", 6, 12, 1, 2, false, "partial"),
    ];
    let ipfrag_caps = ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities {
        raw_ipv4: true,
        raw_ipv6: true,
        tcp_repair: false,
    };

    let selected = select_promotable_candidate_index(&summaries, &specs, true, false, ipfrag_caps)
        .expect("capability-free split candidate should remain promotable");

    assert_eq!(summaries[selected].id, "split_host");
}

#[test]
fn promotable_winner_selection_rejects_failed_and_eliminated_candidates() {
    let specs = build_tcp_candidates(&ProxyUiConfig::default());
    let summaries = vec![
        strategy_candidate_summary("split_host", "split", 6, 12, 1, 2, false, "partial"),
        strategy_candidate_summary("tlsrec_split_host", "tlsrec_split", 24, 24, 0, 2, false, "failed"),
        strategy_candidate_summary("tlsrec_hostfake_split", "hostfake", 18, 24, 1, 1, false, "eliminated"),
    ];
    let ipfrag_caps = ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities {
        raw_ipv4: true,
        raw_ipv6: true,
        tcp_repair: true,
    };

    let selected = select_promotable_candidate_index(&summaries, &specs, true, true, ipfrag_caps)
        .expect("partial split candidate should be the only safe promotable result");

    assert_eq!(summaries[selected].id, "split_host");
}

#[test]
fn safe_or_baseline_selection_does_not_fall_back_to_unsafe_generic_winner() {
    let specs = build_tcp_candidates(&ProxyUiConfig::default());
    let summaries = vec![
        strategy_candidate_summary("baseline_current", "baseline", 1, 12, 0, 2, false, "failed"),
        strategy_candidate_summary("disorder_host", "disorder", 24, 24, 2, 2, false, "success"),
        strategy_candidate_summary("tlsrec_hostfake_split", "hostfake", 18, 24, 1, 1, false, "eliminated"),
    ];
    let ipfrag_caps = ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities::default();

    let selected = select_safe_or_baseline_candidate_index(&summaries, &specs, false, false, ipfrag_caps);

    assert_eq!(selected, None, "a failed baseline is not execution evidence and must not be promoted");
}

#[test]
fn promotable_selection_rejects_success_without_execution_evidence() {
    let specs = build_tcp_candidates(&ProxyUiConfig::default());
    let mut summaries = vec![strategy_candidate_summary("split_host", "split", 6, 12, 1, 2, false, "partial")];
    summaries[0].execution_evidence_complete = false;

    let selected = select_promotable_candidate_index(
        &summaries,
        &specs,
        true,
        true,
        ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities::default(),
    );

    assert_eq!(selected, None);
}

#[test]
fn promotable_selection_rejects_split_receipt_with_wrong_marker_or_partial_actions() {
    let specs = build_tcp_candidates(&ProxyUiConfig::default());
    let mut wrong_marker = strategy_candidate_summary("split_host", "split", 6, 12, 1, 2, false, "partial");
    wrong_marker.execution_attempts[0].receipts[0].marker_delta = Some(1);
    let mut partial_actions = strategy_candidate_summary("split_host", "split", 6, 12, 1, 2, false, "partial");
    partial_actions.execution_attempts[0].receipts[0].completed_actions = 1;
    partial_actions.execution_attempts[0].receipts[0].real_writes_committed = 1;
    partial_actions.execution_attempts[0].receipts[0].completed_awaits = 0;

    for summaries in [vec![wrong_marker], vec![partial_actions]] {
        let selected = select_promotable_candidate_index(
            &summaries,
            &specs,
            true,
            true,
            ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities::default(),
        );
        assert_eq!(selected, None);
    }
}

#[test]
fn safe_or_baseline_selection_uses_quic_disabled_as_conservative_quic_fallback() {
    let mut base = ProxyUiConfig::default();
    base.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "ipfrag2_udp".to_string(),
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];
    let specs = crate::candidates::build_quic_candidates_for_suite("quick_v1", &base).expect("quick quic suite");
    let summaries = vec![
        strategy_candidate_summary("quic_ipfrag2", "quic_ipfrag2", 24, 24, 2, 2, false, "success"),
        strategy_candidate_summary("quic_disabled", "quic_disabled", 1, 4, 0, 2, false, "failed"),
    ];
    let ipfrag_caps = ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities::default();

    let selected = select_safe_or_baseline_candidate_index(&summaries, &specs, false, false, ipfrag_caps);

    assert_eq!(selected, None, "a failed QUIC-disabled candidate is not a verified fallback");
}

#[test]
fn promotable_selection_accepts_successful_quic_disabled_without_desync_receipts() {
    let specs = crate::candidates::build_quic_candidates_for_suite("quick_v1", &ProxyUiConfig::default())
        .expect("quick quic suite");
    let mut summary = strategy_candidate_summary("quic_disabled", "quic_disabled", 4, 4, 2, 2, false, "success");
    summary.desync_execution_required = false;
    summary.execution_evidence_complete = false;

    let selected = select_promotable_candidate_index(
        std::slice::from_ref(&summary),
        &specs,
        false,
        false,
        ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities::default(),
    );

    assert_eq!(selected, Some(0));
}

#[test]
fn promotable_selection_accepts_matching_tcp_and_quic_sni_split_execution_receipts() {
    let tcp_specs = build_tcp_candidates(&ProxyUiConfig::default());
    let quic_specs = crate::candidates::build_quic_candidates_for_suite("quick_v1", &ProxyUiConfig::default())
        .expect("quick quic suite");
    let tcp = strategy_candidate_summary("split_host", "split", 12, 12, 2, 2, false, "success");
    let mut quic = strategy_candidate_summary("quic_sni_split", "quic_sni_split", 12, 12, 2, 2, false, "success");
    quic.execution_attempts = tcp.execution_attempts.clone();
    let quic_receipt = &mut quic.execution_attempts[0].receipts[0];
    quic_receipt.configured_family = Some(StrategyExecutionFamily::QuicSniSplit);
    quic_receipt.effective_family = Some(StrategyExecutionFamily::QuicSniSplit);
    quic_receipt.transport = crate::types::StrategyExecutionTransport::Udp;
    quic_receipt.marker_base = None;
    quic_receipt.marker_delta = None;
    quic_receipt.resolved_offset = None;
    quic_receipt.attempted_actions = 2;
    quic_receipt.completed_actions = 2;
    quic_receipt.real_writes_committed = 1;
    quic_receipt.completed_awaits = 0;

    let capabilities = ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities::default();
    let tcp_winner = select_promotable_candidate_index(&[tcp], &tcp_specs, true, true, capabilities);
    let quic_winner = select_promotable_candidate_index(&[quic], &quic_specs, true, true, capabilities);

    assert_eq!(tcp_winner, Some(0));
    assert_eq!(quic_winner, Some(0));
}

#[test]
fn promotable_selection_accepts_exact_quic_ipv6_extension_profile_receipt() {
    let mut config = ProxyUiConfig::default();
    config.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "ipfrag2_udp".to_string(),
        count: 1,
        split_bytes: 8,
        activation_filter: None,
        ipv6_extension_profile: "hopByHopDestOpt".to_string(),
    }];
    let spec = crate::candidates::candidate_spec(
        "quic_ipfrag2_profiled",
        "QUIC IP fragmentation + HBH + Dest Opt",
        "quic_ipfrag2_ipv6_ext",
        config,
    );
    let mut summary = strategy_candidate_summary("quic_ipfrag2_profiled", "split", 12, 12, 2, 2, false, "success");
    let receipt = &mut summary.execution_attempts[0].receipts[0];
    receipt.configured_family = Some(StrategyExecutionFamily::QuicIpFragment2);
    receipt.effective_family = Some(StrategyExecutionFamily::QuicIpFragment2);
    receipt.transport = crate::types::StrategyExecutionTransport::Udp;
    receipt.marker_base = None;
    receipt.marker_delta = None;
    receipt.resolved_offset = None;
    receipt.attempted_actions = 1;
    receipt.completed_actions = 1;
    receipt.real_writes_committed = 1;
    receipt.completed_awaits = 0;
    receipt.udp_ipv6_extension_profile =
        Some(crate::types::StrategyUdpIpv6ExtensionProfile::HopByHopDestinationOptions);
    assert!(candidate_execution_matches_spec(&summary, &spec));

    let selected = select_promotable_candidate_index(
        std::slice::from_ref(&summary),
        std::slice::from_ref(&spec),
        true,
        true,
        ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities {
            raw_ipv4: true,
            raw_ipv6: true,
            tcp_repair: true,
        },
    );

    assert_eq!(selected, Some(0));

    let mut mismatched = summary;
    mismatched.execution_attempts[0].receipts[0].udp_ipv6_extension_profile =
        Some(crate::types::StrategyUdpIpv6ExtensionProfile::HopByHop);
    assert!(!candidate_execution_matches_spec(&mismatched, &spec));
}

#[test]
fn promotable_selection_accepts_exact_tls_record_split_receipt() {
    let specs = build_tcp_candidates(&ProxyUiConfig::default());
    let summary = strategy_candidate_summary("tlsrec_split_host", "tlsrec_split", 12, 12, 2, 2, false, "success");

    let selected = select_promotable_candidate_index(
        &[summary],
        &specs,
        true,
        true,
        ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities::default(),
    );

    assert_eq!(selected, Some(0));
}

#[test]
fn promotable_selection_accepts_exact_tls_rand_record_split_receipt() {
    let specs = build_tcp_candidates(&ProxyUiConfig::default());
    let mut summary = strategy_candidate_summary("tlsrandrec_split", "tlsrec_split", 12, 12, 2, 2, false, "success");
    let receipt = &mut summary.execution_attempts[0].receipts[0];
    receipt.tls_prelude_kind = Some(crate::types::StrategyTlsPreludeKind::TlsRandRec);
    receipt.tls_prelude_marker_base = Some(crate::types::StrategyOffsetMarkerBase::SniExt);
    receipt.tls_prelude_marker_delta = Some(4);

    let selected = select_promotable_candidate_index(
        &[summary],
        &specs,
        true,
        true,
        ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities::default(),
    );

    assert_eq!(selected, Some(0));
}

#[test]
fn baseline_ech_detection_recognizes_resolution_detail_and_tls_ech_only() {
    assert!(baseline_supports_ech_candidates(&[baseline_https_result("tls_ok", "ech_config_available")]));
    assert!(baseline_supports_ech_candidates(&[baseline_https_result("tls_ech_only", "none")]));
    assert!(baseline_supports_ech_candidates(&[baseline_https_result_with_cdn("tls_ok", "Cloudflare")]));
    assert!(baseline_has_tls_ech_only(&[baseline_https_result("tls_ech_only", "none")]));
    assert!(!baseline_supports_ech_candidates(&[baseline_https_result("tls_ok", "none")]));
    assert!(!baseline_has_tls_ech_only(&[baseline_https_result("tls_ok", "ech_config_available")]));
}

#[test]
fn stratified_pilot_targets_prefers_distinct_bucket_mix() {
    let selected = stratified_pilot_targets(&[
        DomainTarget {
            host: "control.test".to_string(),
            connect_ip: None,
            connect_ips: vec![],
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: true,
            concurrency_probe: None,
        },
        DomainTarget {
            host: "video.cloudflare.com".to_string(),
            connect_ip: None,
            connect_ips: vec![],
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: None,
        },
        DomainTarget {
            host: "portal.gov.ru".to_string(),
            connect_ip: None,
            connect_ips: vec![],
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: None,
        },
        DomainTarget {
            host: "example.com".to_string(),
            connect_ip: None,
            connect_ips: vec![],
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: None,
        },
    ]);

    assert_eq!(selected.len(), 3);
    assert!(selected.iter().any(|target| target.is_control));
    assert!(selected.iter().any(|target| target.host == "video.cloudflare.com"));
    assert!(selected.iter().any(|target| target.host == "portal.gov.ru"));
}

#[test]
fn pilot_bucket_label_captures_reachability_hosting_and_ech_bias() {
    let label = pilot_bucket_label(&DomainTarget {
        host: "cdn.cloudflare.com".to_string(),
        connect_ip: None,
        connect_ips: vec![],
        https_port: None,
        http_port: None,
        http_path: "/".to_string(),
        is_control: false,
        concurrency_probe: None,
    });

    assert_eq!(label, "foreign:cloudflare:ech=yes");
}

#[test]
fn ordered_follow_up_tcp_candidates_prioritize_ech_candidates_after_tls_ech_only_baseline() {
    let candidates = build_tcp_candidates(&ProxyUiConfig::default());

    let ordered = ordered_follow_up_tcp_candidates(
        &candidates,
        Some(FailureClass::TlsAlert),
        &[baseline_https_result("tls_ech_only", "ech_config_available")],
        7,
        true,
    );
    let ids = ordered.iter().take(2).map(|candidate| candidate.id).collect::<Vec<_>>();

    assert_eq!(ids, vec!["ech_split", "ech_tlsrec"]);
    assert!(
        ordered.iter().take(2).all(|candidate| candidate.eligibility == CandidateEligibility::RequiresEchCapability)
    );
}

#[test]
fn ordered_follow_up_tcp_candidates_keep_normal_order_without_ech_promotable_baseline() {
    let candidates = build_tcp_candidates(&ProxyUiConfig::default());
    let expected = interleave_candidate_families(
        reorder_tcp_candidates_for_failure(&candidates, Some(FailureClass::TlsAlert), true)
            .into_iter()
            .filter(|candidate| candidate.id != "baseline_plain_direct")
            .collect(),
        7,
    );

    let ordered = ordered_follow_up_tcp_candidates(
        &candidates,
        Some(FailureClass::TlsAlert),
        &[baseline_https_result("tls_ok", "none")],
        7,
        true,
    );

    assert_eq!(
        ordered.iter().map(|candidate| candidate.id).collect::<Vec<_>>(),
        expected.iter().map(|candidate| candidate.id).collect::<Vec<_>>()
    );
}

#[test]
fn baseline_version_split_detection_matches_only_strategy_https_version_split() {
    assert!(baseline_has_tls_version_split(&[baseline_https_result("tls_version_split", "none")]));
    assert!(!baseline_has_tls_version_split(&[baseline_https_result("tls_ok", "none")]));
    assert!(!baseline_has_tls_version_split(&[baseline_https_result("tls_ech_only", "none")]));
    // Only the strategy_https probe type carries the differential signal.
    assert!(!baseline_has_tls_version_split(&[ProbeResult {
        probe_type: s("domain_reachability"),
        target: s("example.com"),
        outcome: s("tls_version_split"),
        details: vec![],
    }]));
}

#[test]
fn ordered_follow_up_tcp_candidates_promote_split_families_after_tls_version_split() {
    // Split probing reported one TLS version reachable, the other blocked, and
    // the transport layer has no opinion (failure_class = None). The SNI-split
    // families must be floated to the front, ahead of the seed-shuffled
    // interleave that would otherwise scatter them.
    let candidates = build_tcp_candidates(&ProxyUiConfig::default());

    let ordered = ordered_follow_up_tcp_candidates(
        &candidates,
        None,
        &[baseline_https_result("tls_version_split", "none")],
        7,
        true,
    );

    let ids = ordered.iter().take(3).map(|candidate| candidate.id).collect::<Vec<_>>();
    assert_eq!(ids, vec!["tlsrec_split_host", "tlsrec_hostfake_split", "split_host"]);
}

#[test]
fn ordered_follow_up_tcp_candidates_promote_split_families_within_ech_remainder() {
    // ECH-capable baseline AND tls_version_split, no transport failure class:
    // ECH-eligible candidates keep precedence (lead the list), and the
    // SNI-split families are floated to the front of the non-ECH remainder.
    let candidates = build_tcp_candidates(&ProxyUiConfig::default());

    let ordered = ordered_follow_up_tcp_candidates(
        &candidates,
        None,
        &[baseline_https_result("tls_version_split", "ech_config_available")],
        7,
        true,
    );

    // ECH-eligible specs lead.
    let first_non_ech = ordered
        .iter()
        .position(|candidate| candidate.eligibility != CandidateEligibility::RequiresEchCapability)
        .expect("at least one non-ECH candidate is present");
    assert!(first_non_ech > 0, "ECH-eligible candidates should lead the ECH path");
    assert!(
        ordered
            .iter()
            .take(first_non_ech)
            .all(|candidate| candidate.eligibility == CandidateEligibility::RequiresEchCapability)
    );

    // The split families lead the non-ECH remainder (robust to the ECH-spec count).
    let remainder_ids = ordered.iter().skip(first_non_ech).take(3).map(|candidate| candidate.id).collect::<Vec<_>>();
    assert_eq!(remainder_ids, vec!["tlsrec_split_host", "tlsrec_hostfake_split", "split_host"]);
}

#[test]
fn ordered_follow_up_tcp_candidates_version_split_never_overrides_transport_failure_order() {
    // A transport failure class is the higher-confidence signal and already
    // orders toward split itself; the L7 version-split bias must NOT fire when
    // one is present. With TlsAlert set, the result equals the plain ordering
    // even though the baseline also reports tls_version_split.
    let candidates = build_tcp_candidates(&ProxyUiConfig::default());
    let expected = interleave_candidate_families(
        reorder_tcp_candidates_for_failure(&candidates, Some(FailureClass::TlsAlert), true)
            .into_iter()
            .filter(|candidate| candidate.id != "baseline_plain_direct")
            .collect(),
        7,
    );

    let ordered = ordered_follow_up_tcp_candidates(
        &candidates,
        Some(FailureClass::TlsAlert),
        &[baseline_https_result("tls_version_split", "none")],
        7,
        true,
    );

    assert_eq!(
        ordered.iter().map(|candidate| candidate.id).collect::<Vec<_>>(),
        expected.iter().map(|candidate| candidate.id).collect::<Vec<_>>()
    );
}

#[test]
fn ordered_follow_up_tcp_candidates_keep_normal_order_without_version_split() {
    // No version-split signal and no transport failure class: ordering is the
    // unbiased interleave. Guards against the promotion becoming always-on.
    let candidates = build_tcp_candidates(&ProxyUiConfig::default());
    let expected = interleave_candidate_families(
        reorder_tcp_candidates_for_failure(&candidates, None, true)
            .into_iter()
            .filter(|candidate| candidate.id != "baseline_plain_direct")
            .collect(),
        7,
    );

    let ordered =
        ordered_follow_up_tcp_candidates(&candidates, None, &[baseline_https_result("tls_ok", "none")], 7, true);

    assert_eq!(
        ordered.iter().map(|candidate| candidate.id).collect::<Vec<_>>(),
        expected.iter().map(|candidate| candidate.id).collect::<Vec<_>>()
    );
}

fn audit(
    tcp: &[StrategyProbeCandidateSummary],
    quic: &[StrategyProbeCandidateSummary],
    tcp_planned: usize,
    quic_planned: usize,
    dns_sc: bool,
) -> Option<StrategyProbeAuditAssessment> {
    resolve_strategy_probe_audit_assessment(
        STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
        tcp,
        quic,
        &recommendation(),
        tcp_planned,
        quic_planned,
        dns_sc,
    )
}

#[test]
fn resolve_strategy_probe_audit_assessment_high_when_matrix_is_consistent() {
    let tcp = vec![
        strategy_candidate_summary("tcp_runner_up", "split", 40, 100, 2, 5, false, "partial"),
        strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
    ];
    let quic = vec![
        strategy_candidate_summary("quic_runner_up", "quic_disabled", 45, 100, 1, 2, false, "partial"),
        strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
    ];
    let a = audit(&tcp, &quic, 2, 2, false).expect("audit assessment");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::High);
    assert_eq!(a.confidence.score, 100);
    assert_eq!(a.coverage.matrix_coverage_percent, 100);
    assert_eq!(a.coverage.winner_coverage_percent, 100);
    assert!(a.confidence.warnings.is_empty());
}

#[test]
fn resolve_strategy_probe_audit_assessment_low_when_dns_short_circuited() {
    let tcp = vec![strategy_candidate_summary("tcp_winner", "baseline", 0, 0, 0, 5, true, "skipped")];
    let quic = vec![strategy_candidate_summary("quic_winner", "quic_disabled", 0, 0, 0, 2, true, "skipped")];
    let a = audit(&tcp, &quic, 3, 2, true).expect("audit assessment");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::Low);
    assert!(a.dns_short_circuited);
    assert_eq!(
        a.confidence.rationale,
        "Baseline DNS tampering short-circuited the audit before fallback candidates ran"
    );
    assert!(
        a.confidence
            .warnings
            .contains(&s("Baseline DNS tampering short-circuited the audit before fallback candidates ran."))
    );
}

#[test]
fn resolve_strategy_probe_audit_assessment_preserves_dns_fallback_evidence_confidence() {
    let tcp = vec![
        strategy_candidate_summary("tcp_runner_up", "split", 40, 100, 2, 5, false, "partial"),
        strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
    ];
    let quic = vec![
        strategy_candidate_summary("quic_runner_up", "quic_disabled", 45, 100, 1, 2, false, "partial"),
        strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
    ];
    let a = audit(&tcp, &quic, 2, 2, true).expect("audit assessment");
    assert!(!a.dns_short_circuited);
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::High);
    assert_eq!(a.confidence.score, 100);
    assert_eq!(a.confidence.rationale, "Baseline DNS tampering was detected, but fallback strategy candidates ran");
    assert!(
        a.confidence
            .warnings
            .contains(&s("Baseline DNS tampering was detected; confidence reflects fallback strategy candidates."))
    );
}

#[test]
fn resolve_strategy_probe_audit_assessment_penalizes_incomplete_lane_execution() {
    let tcp = vec![
        strategy_candidate_summary("tcp_runner_up", "split", 50, 100, 2, 5, false, "partial"),
        strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
    ];
    let quic = vec![
        strategy_candidate_summary("quic_runner_up", "quic_disabled", 55, 100, 1, 2, false, "partial"),
        strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
    ];
    let a = audit(&tcp, &quic, 4, 4, false).expect("audit assessment");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::Medium);
    assert_eq!(a.confidence.score, 70);
    assert!(a.confidence.warnings.contains(&s("TCP matrix coverage stayed below 75% of applicable candidates.")));
    assert!(a.confidence.warnings.contains(&s("QUIC matrix coverage stayed below 75% of applicable candidates.")));
}

#[test]
fn resolve_strategy_probe_audit_assessment_excludes_not_applicable_candidates_from_coverage() {
    let tcp = vec![
        strategy_candidate_summary("tcp_runner_up", "split", 90, 100, 4, 5, false, "success"),
        strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
        strategy_candidate_summary("tcp_not_applicable", "split", 0, 0, 0, 0, false, "not_applicable"),
    ];
    let quic = vec![
        strategy_candidate_summary("quic_runner_up", "quic_disabled", 90, 100, 1, 2, false, "success"),
        strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
        strategy_candidate_summary("quic_not_applicable", "quic_burst", 0, 0, 0, 0, false, "not_applicable"),
    ];
    let a = audit(&tcp, &quic, 3, 3, false).expect("audit assessment");
    assert_eq!(a.coverage.tcp_candidates_planned, 3);
    assert_eq!(a.coverage.tcp_candidates_not_applicable, 1);
    assert_eq!(a.coverage.quic_candidates_planned, 3);
    assert_eq!(a.coverage.quic_candidates_not_applicable, 1);
    assert_eq!(a.coverage.matrix_coverage_percent, 100);
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::High);
    assert!(!a.confidence.warnings.iter().any(|w| w.contains("applicable candidates")));
}

#[test]
fn resolve_strategy_probe_audit_assessment_does_not_penalize_non_ech_baselines_for_ech_candidates() {
    let tcp = vec![
        strategy_candidate_summary("tcp_runner_up", "split", 90, 100, 4, 5, false, "success"),
        strategy_candidate_summary("tcp_winner", "hostfake", 100, 100, 5, 5, false, "success"),
        strategy_candidate_summary("ech_split", "ech_split", 0, 0, 0, 0, false, "not_applicable"),
        strategy_candidate_summary("ech_tlsrec", "ech_tlsrec", 0, 0, 0, 0, false, "not_applicable"),
    ];
    let quic = vec![
        strategy_candidate_summary("quic_runner_up", "quic_disabled", 90, 100, 1, 2, false, "success"),
        strategy_candidate_summary("quic_winner", "quic_burst", 100, 100, 2, 2, false, "success"),
    ];
    let a = audit(&tcp, &quic, 4, 2, false).expect("audit assessment");
    assert_eq!(a.coverage.tcp_candidates_not_applicable, 2);
    assert_eq!(a.coverage.matrix_coverage_percent, 100);
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::High);
    assert_eq!(a.confidence.score, 100);
    assert_eq!(a.confidence.rationale, "Matrix coverage and winner strength are consistent");
}

#[test]
fn resolve_strategy_probe_audit_assessment_penalizes_narrow_winner_margin() {
    let tcp = vec![
        strategy_candidate_summary("tcp_runner_up", "split", 92, 100, 4, 5, false, "success"),
        strategy_candidate_summary("tcp_winner", "hostfake", 96, 100, 5, 5, false, "success"),
    ];
    let quic = vec![
        strategy_candidate_summary("quic_runner_up", "quic_disabled", 89, 100, 2, 2, false, "success"),
        strategy_candidate_summary("quic_winner", "quic_burst", 95, 100, 2, 2, false, "success"),
    ];
    let a = audit(&tcp, &quic, 2, 2, false).expect("audit assessment");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::High);
    assert_eq!(a.confidence.score, 80);
    assert!(a.confidence.warnings.contains(&s("TCP winner margin stayed below 10 points over the next candidate.")));
    assert!(a.confidence.warnings.contains(&s("QUIC winner margin stayed below 10 points over the next candidate.")));
}

#[test]
fn test_audit_assessment_penalizes_all_tied_candidates() {
    let tcp: Vec<_> = (0..15)
        .map(|i| strategy_candidate_summary(&format!("tcp_{i}"), "split", 2, 9, 1, 6, false, "partial"))
        .collect();
    let quic: Vec<_> =
        (0..3).map(|i| strategy_candidate_summary(&format!("quic_{i}"), "quic", 0, 4, 0, 2, false, "failed")).collect();
    let rec = StrategyProbeRecommendation {
        tcp_candidate_id: s("tcp_0"),
        tcp_candidate_label: s("tcp 0"),
        quic_candidate_id: s("quic_0"),
        quic_candidate_label: s("quic 0"),
        quic_candidate_layout_family: None,
        rationale: s("all tied"),
        recommended_proxy_config_json: String::new(),
        transport_pivot: None,
    };
    let a =
        resolve_strategy_probe_audit_assessment(STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, &tcp, &quic, &rec, 15, 3, false)
            .expect("should produce assessment for full_matrix_v1");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::Low);
    assert!(a.confidence.warnings.iter().any(|w| w.contains("TCP candidates produced identical results")));
    assert!(a.confidence.warnings.iter().any(|w| w.contains("QUIC candidates produced identical results")));
}

#[test]
fn test_audit_assessment_high_when_baseline_tied_high_coverage() {
    let tcp = vec![
        strategy_candidate_summary("baseline_current", "baseline", 90, 100, 4, 5, false, "success"),
        strategy_candidate_summary("tcp_split", "split", 90, 100, 4, 5, false, "success"),
        strategy_candidate_summary("tcp_hostfake", "hostfake", 90, 100, 4, 5, false, "success"),
    ];
    let quic = vec![
        strategy_candidate_summary("baseline_current", "baseline", 80, 100, 2, 2, false, "success"),
        strategy_candidate_summary("quic_burst", "quic_burst", 80, 100, 2, 2, false, "success"),
    ];
    let rec = StrategyProbeRecommendation {
        tcp_candidate_id: s("baseline_current"),
        tcp_candidate_label: s("Current strategy"),
        quic_candidate_id: s("baseline_current"),
        quic_candidate_label: s("Current QUIC strategy"),
        quic_candidate_layout_family: None,
        rationale: s("all tied"),
        recommended_proxy_config_json: String::new(),
        transport_pivot: None,
    };
    let a =
        resolve_strategy_probe_audit_assessment(STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, &tcp, &quic, &rec, 3, 2, false)
            .expect("should produce assessment");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::High);
    assert_eq!(a.confidence.score, 100);
    assert!(a.confidence.rationale.contains("no evasion needed"));
    assert!(a.confidence.warnings.is_empty());
}

#[test]
fn test_audit_assessment_low_when_baseline_tied_low_coverage() {
    let tcp = vec![
        strategy_candidate_summary("baseline_current", "baseline", 20, 100, 1, 5, false, "partial"),
        strategy_candidate_summary("tcp_split", "split", 20, 100, 1, 5, false, "partial"),
    ];
    let quic = vec![
        strategy_candidate_summary("baseline_current", "baseline", 20, 100, 0, 2, false, "failed"),
        strategy_candidate_summary("quic_burst", "quic_burst", 20, 100, 0, 2, false, "failed"),
    ];
    let rec = StrategyProbeRecommendation {
        tcp_candidate_id: s("baseline_current"),
        tcp_candidate_label: s("Current strategy"),
        quic_candidate_id: s("baseline_current"),
        quic_candidate_label: s("Current QUIC strategy"),
        quic_candidate_layout_family: None,
        rationale: s("all tied"),
        recommended_proxy_config_json: String::new(),
        transport_pivot: None,
    };
    let a =
        resolve_strategy_probe_audit_assessment(STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, &tcp, &quic, &rec, 2, 2, false)
            .expect("should produce assessment");
    assert_eq!(a.confidence.level, StrategyProbeAuditConfidenceLevel::Low);
    assert!(a.confidence.warnings.iter().any(|w| w.contains("identical results")));
}
