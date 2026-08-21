use ripdpi_monitor_adapter::proxy_config::{ProxyUiActivationFilter, ProxyUiConfig, ProxyUiTcpRotationConfig};

use crate::candidates::StrategyCandidateSpec;
use crate::types::{
    ProbeResult, StrategyEmitterTier, StrategyProbeCandidateSummary, StrategyProbeObservationRole,
    StrategyProbeRouteFeature,
};

use super::*;

#[test]
fn winning_candidate_index_selects_highest_weighted_success_score() {
    let candidates = vec![
        summary_with("a", 2, 4, 3, false, None),
        summary_with("b", 4, 4, 6, false, None),
        summary_with("c", 3, 4, 5, false, None),
    ];

    assert_eq!(winning_candidate_index(&candidates), Some(1));
}

#[test]
fn winning_candidate_index_breaks_tie_with_quality_score() {
    let candidates = vec![summary_with("a", 3, 4, 5, false, None), summary_with("b", 3, 4, 8, false, None)];

    assert_eq!(winning_candidate_index(&candidates), Some(1));
}

#[test]
fn winning_candidate_index_skips_skipped_candidates() {
    let candidates = vec![
        summary_with("a", 2, 4, 3, false, None),
        summary_with("b", 10, 10, 20, true, None),
        summary_with("c", 3, 4, 5, false, None),
    ];

    assert_eq!(winning_candidate_index(&candidates), Some(2));
}

#[test]
fn winning_candidate_index_skips_not_applicable_candidates() {
    let mut candidates = vec![summary_with("a", 2, 4, 3, false, None), summary_with("b", 10, 10, 20, false, None)];
    candidates[1].outcome = "not_applicable".to_string();

    assert_eq!(winning_candidate_index(&candidates), Some(0));
}

#[test]
fn winning_candidate_index_returns_none_for_empty_list() {
    assert_eq!(winning_candidate_index(&[]), None);
}

#[test]
fn winning_candidate_index_prefers_lower_latency_on_tie() {
    let candidates = vec![summary_with("a", 3, 4, 5, false, Some(200)), summary_with("b", 3, 4, 5, false, Some(100))];

    assert_eq!(winning_candidate_index(&candidates), Some(1));
}

#[test]
fn candidate_score_add_accumulates_weighted_success() {
    let mut score = CandidateScore::default();
    score.add(ProbeSample {
        result: ProbeResult {
            probe_type: "test".to_string(),
            target: "t".to_string(),
            outcome: "ok".to_string(),
            details: vec![],
        },
        success: true,
        weight: 2,
        quality: 4,
        latency_ms: 50,
        domain: None,
        is_control: false,
        attempt_token: None,
    });
    score.add(ProbeSample {
        result: ProbeResult {
            probe_type: "test".to_string(),
            target: "t".to_string(),
            outcome: "fail".to_string(),
            details: vec![],
        },
        success: false,
        weight: 1,
        quality: 0,
        latency_ms: 100,
        domain: None,
        is_control: false,
        attempt_token: None,
    });

    assert_eq!(score.succeeded_targets, 1);
    assert_eq!(score.total_targets, 2);
    assert_eq!(score.weighted_success_score, 2);
    assert_eq!(score.total_weight, 3);
    assert_eq!(score.quality_score, 8); // 4*2 + 0*1
    assert_eq!(score.average_latency_ms(), Some(50));
    assert!(!score.is_full_success());
}

#[test]
fn candidate_score_full_success_when_all_targets_succeed() {
    let mut score = CandidateScore::default();
    score.add(ProbeSample {
        result: ProbeResult {
            probe_type: "test".to_string(),
            target: "t".to_string(),
            outcome: "ok".to_string(),
            details: vec![],
        },
        success: true,
        weight: 1,
        quality: 3,
        latency_ms: 100,
        domain: None,
        is_control: false,
        attempt_token: None,
    });

    assert!(score.is_full_success());
}

#[test]
fn candidate_score_preserves_control_classification_in_domain_outcome() {
    let mut score = CandidateScore::default();
    score.add(ProbeSample {
        result: ProbeResult {
            probe_type: "test".to_string(),
            target: "control.example".to_string(),
            outcome: "ok".to_string(),
            details: vec![],
        },
        success: true,
        weight: 1,
        quality: 3,
        latency_ms: 25,
        domain: Some("control.example".to_string()),
        is_control: true,
        attempt_token: None,
    });

    let outcomes = score.domain_outcomes();
    assert_eq!(outcomes.len(), 1);
    assert_eq!(outcomes[0].domain, "control.example");
    assert!(outcomes[0].succeeded);
    assert!(outcomes[0].is_control);
}

#[test]
fn terminal_receipts_join_attempts_by_token_when_completion_order_is_reversed() {
    let first = crate::CandidateAttemptCorrelationId::evaluated(7, 1).expect("first id");
    let second = crate::CandidateAttemptCorrelationId::evaluated(7, 2).expect("second id");
    let mut score = CandidateScore::default();
    for (token, success) in [(first.clone(), true), (second.clone(), false)] {
        score.add(ProbeSample {
            result: ProbeResult {
                probe_type: "test".to_string(),
                target: "redacted".to_string(),
                outcome: if success { "ok" } else { "failed" }.to_string(),
                details: vec![],
            },
            success,
            weight: 1,
            quality: usize::from(success),
            latency_ms: 1,
            domain: None,
            is_control: false,
            attempt_token: Some(token),
        });
    }
    let mut execution = build_candidate_execution(&test_spec(), score, 1);
    let evidence = |token: crate::CandidateAttemptCorrelationId| test_desync_evidence(7, token);
    let terminal = crate::CandidateRuntimeTerminalReceipt::clean_shutdown(
        7,
        crate::CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0, ..Default::default() },
        vec![evidence(second.clone()), evidence(first.clone())],
    )
    .expect("terminal receipt");

    execution.attach_terminal_evidence(7, &terminal);

    assert!(execution.execution_evidence_complete);
    assert_eq!(execution.attempts[0].token, first);
    assert_eq!(execution.attempts[0].receipts[0].attempt_token(), &execution.attempts[0].token);
    assert!(execution.attempts[0].success);
    assert_eq!(execution.attempts[1].token, second);
    assert!(!execution.attempts[1].success);
}

#[test]
fn terminal_constructor_rejects_mixed_generation_evidence() {
    let token = crate::CandidateAttemptCorrelationId::evaluated(8, 1).expect("id");
    let mut score = CandidateScore::default();
    score.add(ProbeSample {
        result: ProbeResult {
            probe_type: "test".to_string(),
            target: "redacted".to_string(),
            outcome: "ok".to_string(),
            details: vec![],
        },
        success: true,
        weight: 1,
        quality: 1,
        latency_ms: 1,
        domain: None,
        is_control: false,
        attempt_token: Some(token.clone()),
    });
    let execution = build_candidate_execution(&test_spec(), score, 1);
    let terminal = crate::CandidateRuntimeTerminalReceipt::clean_shutdown(
        8,
        crate::CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0, ..Default::default() },
        vec![test_desync_evidence(8, token.clone()), test_desync_evidence(7, token)],
    );

    assert!(terminal.is_none());
    assert!(!execution.execution_evidence_complete);
    assert!(execution.attempts[0].receipts.is_empty());
}

#[test]
fn terminal_receipt_from_another_generation_is_rejected_before_joining() {
    let token = crate::CandidateAttemptCorrelationId::evaluated(8, 1).expect("id");
    let mut score = CandidateScore::default();
    score.add(ProbeSample {
        result: ProbeResult {
            probe_type: "test".to_string(),
            target: "redacted".to_string(),
            outcome: "ok".to_string(),
            details: vec![],
        },
        success: true,
        weight: 1,
        quality: 1,
        latency_ms: 1,
        domain: None,
        is_control: false,
        attempt_token: Some(token),
    });
    let mut execution = build_candidate_execution(&test_spec(), score, 1);
    let terminal = crate::CandidateRuntimeTerminalReceipt::clean_shutdown(
        7,
        crate::CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0, ..Default::default() },
        Vec::new(),
    )
    .expect("terminal receipt");

    execution.attach_terminal_evidence(8, &terminal);

    assert!(!execution.execution_evidence_complete);
    assert!(execution.attempts[0].receipts.is_empty());
}

#[test]
fn unknown_same_generation_attempt_receipt_makes_candidate_unverified() {
    let known = crate::CandidateAttemptCorrelationId::evaluated(7, 1).expect("known id");
    let unknown = crate::CandidateAttemptCorrelationId::evaluated(7, 2).expect("unknown id");
    let mut score = CandidateScore::default();
    score.add(ProbeSample {
        result: ProbeResult {
            probe_type: "test".to_string(),
            target: "redacted".to_string(),
            outcome: "ok".to_string(),
            details: vec![],
        },
        success: true,
        weight: 1,
        quality: 1,
        latency_ms: 1,
        domain: None,
        is_control: false,
        attempt_token: Some(known.clone()),
    });
    let mut execution = build_candidate_execution(&test_spec(), score, 1);
    let terminal = crate::CandidateRuntimeTerminalReceipt::clean_shutdown(
        7,
        crate::CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0, ..Default::default() },
        vec![test_desync_evidence(7, known), test_desync_evidence(7, unknown)],
    )
    .expect("terminal receipt");

    execution.attach_terminal_evidence(7, &terminal);

    assert!(!execution.execution_evidence_complete);
    assert!(!execution.has_applied_success_evidence());
}

#[test]
fn duplicate_or_gapped_connection_ordinals_make_candidate_unverified() {
    for second_ordinal in [1, 3] {
        let token = crate::CandidateAttemptCorrelationId::evaluated(7, 1).expect("attempt id");
        let mut score = CandidateScore::default();
        score.add(ProbeSample {
            result: ProbeResult {
                probe_type: "test".to_string(),
                target: "redacted".to_string(),
                outcome: "ok".to_string(),
                details: vec![],
            },
            success: true,
            weight: 1,
            quality: 1,
            latency_ms: 1,
            domain: None,
            is_control: false,
            attempt_token: Some(token.clone()),
        });
        let first = test_desync_evidence_with_ordinal(7, token.clone(), 1);
        let second = test_desync_evidence_with_ordinal(7, token, second_ordinal);
        let terminal = crate::CandidateRuntimeTerminalReceipt::clean_shutdown(
            7,
            crate::CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0, ..Default::default() },
            vec![first, second],
        )
        .expect("same-generation terminal receipt");
        let mut execution = build_candidate_execution(&test_spec(), score, 1);

        execution.attach_terminal_evidence(7, &terminal);

        assert!(!execution.execution_evidence_complete);
    }
}

#[test]
fn forged_zero_write_applied_receipt_is_rejected_at_the_runtime_boundary() {
    let token = crate::CandidateAttemptCorrelationId::evaluated(7, 1).expect("id");
    let mut forged = test_strategy_desync_receipt(1);
    forged.attempted_actions = 0;
    forged.completed_actions = 0;
    forged.real_writes_committed = 0;
    forged.payload_bytes_committed = 0;

    assert!(crate::CandidateRuntimeExecutionEvidence::checked_desync(7, token, forged).is_none());
}

#[test]
fn unknown_execution_family_is_rejected_at_the_runtime_boundary() {
    let token = crate::CandidateAttemptCorrelationId::evaluated(7, 1).expect("attempt id");
    let mut unknown = test_strategy_desync_receipt(1);
    unknown.configured_family = Some(crate::types::StrategyExecutionFamily::Unknown);
    unknown.effective_family = Some(crate::types::StrategyExecutionFamily::Unknown);

    assert!(crate::CandidateRuntimeExecutionEvidence::checked_desync(7, token, unknown).is_none());
}

#[test]
fn partially_completed_applied_receipt_is_rejected_at_the_runtime_boundary() {
    let token = crate::CandidateAttemptCorrelationId::evaluated(7, 1).expect("attempt id");
    let mut partial = test_strategy_desync_receipt(1);
    partial.attempted_actions = 4;
    partial.completed_actions = 3;

    assert!(crate::CandidateRuntimeExecutionEvidence::checked_desync(7, token, partial).is_none());
}

fn test_desync_evidence(
    generation: u64,
    attempt_token: crate::CandidateAttemptCorrelationId,
) -> crate::CandidateRuntimeExecutionEvidence {
    test_desync_evidence_with_ordinal(generation, attempt_token, 1)
}

fn test_desync_evidence_with_ordinal(
    generation: u64,
    attempt_token: crate::CandidateAttemptCorrelationId,
    connection_ordinal: u16,
) -> crate::CandidateRuntimeExecutionEvidence {
    crate::CandidateRuntimeExecutionEvidence::checked_desync(
        generation,
        attempt_token,
        test_strategy_desync_receipt(connection_ordinal),
    )
    .expect("valid test evidence")
}

fn test_strategy_desync_receipt(connection_ordinal: u16) -> crate::types::StrategyDesyncExecutionReceipt {
    crate::types::StrategyDesyncExecutionReceipt {
        connection_ordinal,
        transport: crate::types::StrategyExecutionTransport::Tcp,
        disposition: crate::types::StrategyExecutionDisposition::Applied,
        configured_family: Some(crate::types::StrategyExecutionFamily::Split),
        effective_family: Some(crate::types::StrategyExecutionFamily::Split),
        marker_base: Some(crate::types::StrategyOffsetMarkerBase::Host),
        marker_delta: Some(1),
        resolved_offset: Some(16),
        planned_steps: 1,
        attempted_actions: 3,
        completed_actions: 3,
        real_writes_committed: 2,
        completed_awaits: 1,
        payload_bytes_committed: 32,
        tls_record_prelude_applied: false,
        tls_prelude_configured_count: 0,
        tls_prelude_applied_count: 0,
        tls_prelude_kind: None,
        tls_prelude_marker_base: None,
        tls_prelude_marker_delta: None,
        tls_prelude_resolved_offset: None,
        udp_ipv6_extension_profile: None,
        fallback_reason: None,
        terminal_reason: None,
    }
}

#[test]
fn candidate_score_average_latency_none_when_no_success() {
    let score = CandidateScore::default();

    assert_eq!(score.average_latency_ms(), None);
}

#[test]
fn not_applicable_candidate_execution_keeps_ech_notes_and_rationale() {
    let spec = crate::candidates::build_tcp_candidates(&test_ui_config())
        .into_iter()
        .find(|candidate| candidate.id == "ech_split")
        .expect("ech_split candidate");

    let execution = not_applicable_candidate_execution(&spec, 4, 3, "No baseline HTTPS target exposed ECH capability");

    assert_eq!(execution.summary.outcome, "not_applicable");
    assert_eq!(execution.summary.rationale, "No baseline HTTPS target exposed ECH capability");
    assert_eq!(execution.summary.total_targets, 4);
    assert_eq!(execution.summary.total_weight, 12);
    assert!(
        execution
            .summary
            .notes
            .iter()
            .any(|note| note.contains("Runs only when the baseline proves an ECH-capable HTTPS path"))
    );
    assert!(execution.summary.notes.iter().any(|note| note == "No baseline HTTPS target exposed ECH capability"));
}

#[test]
fn failed_execution_sets_outcome_and_rationale() {
    let exec = failed_candidate_execution(&test_spec(), 4, 3, "proxy startup failed".to_string());

    assert_eq!(exec.summary.outcome, "failed");
    assert_eq!(exec.summary.rationale, "proxy startup failed");
    assert_eq!(exec.summary.succeeded_targets, 0);
    assert_eq!(exec.summary.total_targets, 4);
    assert_eq!(exec.summary.total_weight, 12);
    assert!(!exec.cancelled);
    assert!(exec.results.is_empty());
}

#[test]
fn cancelled_execution_marks_cancelled_flag() {
    let score = CandidateScore { total_targets: 2, total_weight: 6, ..Default::default() };
    let exec = cancelled_candidate_execution(&test_spec(), score, 0);

    assert!(exec.cancelled);
    assert_eq!(exec.summary.outcome, "failed"); // no succeeded targets
}

#[test]
fn skipped_summary_sets_skipped_flag_and_rationale() {
    let summary = skipped_candidate_summary(&test_spec(), 4, 3, "prerequisite not met");

    assert!(summary.skipped);
    assert_eq!(summary.outcome, "skipped");
    assert_eq!(summary.rationale, "prerequisite not met");
    assert_eq!(summary.total_weight, 12);
    assert!(summary.notes.iter().any(|n| n == "prerequisite not met"));
}

#[test]
fn candidate_proxy_config_json_serializes() {
    let json = candidate_proxy_config_json(&test_spec());

    assert!(json.is_some(), "should produce valid JSON");
    let json_str = json.unwrap();
    let _: serde_json::Value = serde_json::from_str(&json_str).expect("should be valid JSON");
}

#[test]
fn candidate_notes_collects_extra_notes() {
    let notes = candidate_notes(&test_spec(), &["extra note 1", "extra note 2"]);

    assert!(notes.iter().any(|n| n == "extra note 1"));
    assert!(notes.iter().any(|n| n == "extra note 2"));
}

#[test]
fn candidate_notes_empty_when_no_notes() {
    let spec = crate::candidates::candidate_spec("bare", "Bare", "bare", test_ui_config());
    let notes = candidate_notes(&spec, &[]);

    assert!(notes.iter().all(|n| n != "extra"), "should not contain extras");
}

#[test]
fn build_execution_computes_outcome_success() {
    let mut score = CandidateScore { total_targets: 2, total_weight: 6, ..Default::default() };
    score.succeeded_targets = 2;
    score.weighted_success_score = 6;
    score.quality_score = 10;
    let exec = build_candidate_execution(&test_spec(), score, 0);

    assert_eq!(exec.summary.outcome, "success");
}

#[test]
fn build_execution_computes_outcome_partial() {
    let mut score = CandidateScore { total_targets: 4, total_weight: 12, ..Default::default() };
    score.succeeded_targets = 2;
    score.weighted_success_score = 6;
    score.quality_score = 5;
    let exec = build_candidate_execution(&test_spec(), score, 3);

    assert_eq!(exec.summary.outcome, "partial");
}

#[test]
fn build_execution_computes_outcome_failed() {
    let score = CandidateScore { total_targets: 4, total_weight: 12, ..Default::default() };
    let exec = build_candidate_execution(&test_spec(), score, 0);

    assert_eq!(exec.summary.outcome, "failed");
}

#[test]
fn build_execution_records_privacy_safe_compound_route_features() {
    let mut config = test_ui_config();
    config.upstream_relay.enabled = true;
    config.warp.enabled = true;
    config.ws_tunnel.enabled = true;
    config.destination_routing.canonical_digest = "typed-route-policy".to_string();
    config.chains.tcp_rotation = Some(ProxyUiTcpRotationConfig::default());
    config.chains.group_activation_filter = Some(ProxyUiActivationFilter::default());
    let spec = crate::candidates::candidate_spec("compound", "Compound", "baseline", config);

    let execution = build_candidate_execution(&spec, CandidateScore::default(), 0);

    assert_eq!(
        execution.summary.route_features,
        vec![
            StrategyProbeRouteFeature::UpstreamRelay,
            StrategyProbeRouteFeature::Warp,
            StrategyProbeRouteFeature::WebSocketTunnel,
            StrategyProbeRouteFeature::DestinationRouting,
            StrategyProbeRouteFeature::TcpRotation,
            StrategyProbeRouteFeature::AdaptiveFallback,
            StrategyProbeRouteFeature::GroupActivationFilter,
        ],
    );
}

fn summary_with(
    id: &str,
    weighted_success_score: usize,
    total_weight: usize,
    quality_score: usize,
    skipped: bool,
    average_latency_ms: Option<u64>,
) -> StrategyProbeCandidateSummary {
    StrategyProbeCandidateSummary {
        id: id.to_string(),
        label: id.to_string(),
        family: "test".to_string(),
        emitter_tier: StrategyEmitterTier::NonRootProduction,
        exact_emitter_requires_root: false,
        emitter_downgraded: false,
        quic_layout_family: None,
        outcome: if skipped { "skipped" } else { "success" }.to_string(),
        rationale: String::new(),
        succeeded_targets: weighted_success_score,
        total_targets: total_weight,
        weighted_success_score,
        total_weight,
        quality_score,
        proxy_config_json: None,
        notes: vec![],
        average_latency_ms,
        skipped,
        domain_outcomes: vec![],
        observation_role: StrategyProbeObservationRole::EphemeralCandidateRawPath,
        active_snapshot_faithful: true,
        desync_execution_required: true,
        runtime_terminal_status: crate::types::StrategyProbeRuntimeTerminalStatus::Unavailable,
        execution_evidence_complete: false,
        execution_attempts: vec![],
        route_features: vec![],
    }
}

fn test_ui_config() -> ProxyUiConfig {
    let mut config = ProxyUiConfig::default();
    config.protocols.desync_udp = true;
    config.chains.tcp_steps = vec![];
    config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
    config
}

fn test_spec() -> StrategyCandidateSpec {
    crate::candidates::candidate_spec("test_id", "Test Label", "test_family", test_ui_config())
}
