use std::collections::BTreeMap;

use crate::types::{
    ConfirmGoodDpiEvidence, ConfirmGoodDpiEvidenceSource, ConfirmGoodDpiVerdict, ConfirmGoodDpiVerdictStatus,
    ConnectionConcurrencyAssessment, ConnectionConcurrencyCellStatus, ConnectionConcurrencyObservationFact,
    ConnectionConcurrencyVerdict, DiagnosticProfileFamily, ExecutionPlanSnapshot, ExecutionPlanTargetCounts,
    ObservationKind, ProbeObservation, ProbeTaskFamily, STRATEGY_PROBE_METHODOLOGY_VERSION, ScanKind, ScanPathMode,
    StrategyCandidatePlanSnapshot, StrategyEmitterTier, StrategyExecutionPlanSnapshot, StrategyProbeAuditAssessment,
    StrategyProbeAuditConfidence, StrategyProbeAuditConfidenceLevel, StrategyProbeAuditCoverage,
    StrategyProbeCandidateSummary, StrategyProbeCompletionKind, StrategyProbeRecommendation, StrategyProbeReport,
    StrategyProbeTargetSelection, TransportFamily, TransportPivotRecommendation, TransportPivotViability,
};

use super::{
    DIAGNOSTICS_ENGINE_SCHEMA_VERSION, EngineProbeResultWire, EngineProgressWire, EngineScanReportWire,
    EngineScanRequestWire, ResolverRecommendationWire,
};

#[test]
fn diagnostics_schema_version_matches_contract_fixture() {
    use golden_test_support::assert_contract_fixture;
    use serde_json::json;

    let fixture = json!({
        "schemaVersion": DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
    });
    let actual = serde_json::to_string_pretty(&fixture).expect("serialize");
    assert_contract_fixture("diagnostics_schema_version.json", &actual);
}

#[test]
fn diagnostics_wire_payloads_require_schema_version() {
    let request = serde_json::from_value::<EngineScanRequestWire>(serde_json::json!({}));
    let report = serde_json::from_value::<EngineScanReportWire>(serde_json::json!({}));
    let progress = serde_json::from_value::<EngineProgressWire>(serde_json::json!({}));

    for error in [request.unwrap_err(), report.unwrap_err(), progress.unwrap_err()] {
        assert!(error.to_string().contains("schemaVersion"), "error should name schemaVersion: {error}");
    }
}

#[test]
fn scan_completion_defaults_are_backward_compatible() {
    let report: EngineScanReportWire = serde_json::from_value(serde_json::json!({
        "schemaVersion": DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
        "sessionId": "legacy-session",
        "profileId": "default",
        "pathMode": "RAW_PATH",
        "startedAt": 1,
        "finishedAt": 2,
        "summary": "done"
    }))
    .expect("legacy report");

    assert_eq!(report.completion_kind, crate::types::ScanCompletionKind::Normal);
    assert!(report.termination_reason.is_none());
    let encoded = serde_json::to_value(report).expect("encoded report");
    assert!(encoded.get("completionKind").is_none());
    assert!(encoded.get("terminationReason").is_none());
}

#[test]
fn terminated_scan_serializes_typed_reason() {
    let mut report: EngineScanReportWire = serde_json::from_value(serde_json::json!({
        "schemaVersion": DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
        "sessionId": "offline-session",
        "profileId": "default",
        "pathMode": "RAW_PATH",
        "startedAt": 1,
        "finishedAt": 2,
        "summary": "offline"
    }))
    .expect("report");
    report.completion_kind = crate::types::ScanCompletionKind::Terminated;
    report.termination_reason = Some(crate::types::ScanTerminationReason::NetworkUnavailable);

    let encoded = serde_json::to_value(report).expect("encoded report");
    assert_eq!(encoded["completionKind"], "TERMINATED");
    assert_eq!(encoded["terminationReason"], "NETWORK_UNAVAILABLE");
}

#[test]
fn diagnostics_progress_field_manifest_matches_contract_fixture() {
    use golden_test_support::{assert_contract_fixture, extract_field_paths};

    let progress = EngineProgressWire {
        schema_version: DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
        session_id: "test-session".to_string(),
        phase: "probing".to_string(),
        completed_steps: 5,
        total_steps: 10,
        message: "Running probes".to_string(),
        is_finished: false,
        latest_probe_target: Some("example.org".to_string()),
        latest_probe_outcome: Some("reachable".to_string()),
        strategy_probe_progress: None,
    };

    let json = serde_json::to_value(&progress).expect("serialize progress");
    let paths = extract_field_paths(&json);
    let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
    assert_contract_fixture("diagnostics_progress_fields.json", &manifest);
}

#[test]
fn diagnostics_scan_report_field_manifest_matches_contract_fixture() {
    use golden_test_support::{assert_contract_fixture, extract_field_paths};

    let report = EngineScanReportWire {
        schema_version: DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
        session_id: "test-session".to_string(),
        profile_id: "connectivity-basic".to_string(),
        path_mode: ScanPathMode::RawPath,
        started_at: 1000,
        finished_at: 2000,
        summary: "All probes passed".to_string(),
        completion_kind: crate::types::ScanCompletionKind::Normal,
        termination_reason: None,
        results: vec![EngineProbeResultWire {
            probe_type: "dns".to_string(),
            target: "example.org".to_string(),
            outcome: "reachable".to_string(),
            details: vec![],
            probe_retry_count: Some(0),
        }],
        resolver_recommendation: Some(ResolverRecommendationWire {
            trigger_outcome: "dns_tampering".to_string(),
            selected_resolver_id: "cloudflare".to_string(),
            selected_protocol: "doh".to_string(),
            selected_endpoint: "1.1.1.1:443".to_string(),
            selected_bootstrap_ips: vec!["1.1.1.1".to_string()],
            selected_host: "cloudflare-dns.com".to_string(),
            selected_port: 443,
            selected_tls_server_name: "cloudflare-dns.com".to_string(),
            selected_doh_url: "https://cloudflare-dns.com/dns-query".to_string(),
            selected_dnscrypt_provider_name: String::new(),
            selected_dnscrypt_public_key: String::new(),
            rationale: "DNS tampering detected".to_string(),
            applied_temporarily: false,
            persistable: true,
        }),
        strategy_probe_report: Some(StrategyProbeReport {
            suite_id: "full_matrix_v1".to_string(),
            methodology_version: STRATEGY_PROBE_METHODOLOGY_VERSION.to_string(),
            tcp_candidates: vec![StrategyProbeCandidateSummary {
                id: "baseline_current".to_string(),
                label: "Current strategy".to_string(),
                family: "baseline_current".to_string(),
                emitter_tier: crate::types::StrategyEmitterTier::NonRootProduction,
                exact_emitter_requires_root: false,
                emitter_downgraded: false,
                quic_layout_family: None,
                outcome: "skipped".to_string(),
                rationale: "DNS tampering detected before fallback; TCP strategy escalation skipped".to_string(),
                succeeded_targets: 0,
                total_targets: 6,
                weighted_success_score: 0,
                total_weight: 18,
                quality_score: 0,
                proxy_config_json: None,
                notes: vec![],
                average_latency_ms: None,
                skipped: true,
                domain_outcomes: vec![],
            }],
            quic_candidates: vec![StrategyProbeCandidateSummary {
                id: "quic_disabled".to_string(),
                label: "Current QUIC strategy".to_string(),
                family: "quic_disabled".to_string(),
                emitter_tier: crate::types::StrategyEmitterTier::NonRootProduction,
                exact_emitter_requires_root: false,
                emitter_downgraded: false,
                quic_layout_family: None,
                outcome: "skipped".to_string(),
                rationale: "DNS tampering detected before fallback; QUIC strategy escalation skipped".to_string(),
                succeeded_targets: 0,
                total_targets: 2,
                weighted_success_score: 0,
                total_weight: 4,
                quality_score: 0,
                proxy_config_json: None,
                notes: vec![],
                average_latency_ms: None,
                skipped: true,
                domain_outcomes: vec![],
            }],
            recommendation: StrategyProbeRecommendation {
                tcp_candidate_id: "baseline_current".to_string(),
                tcp_candidate_label: "Current strategy".to_string(),
                quic_candidate_id: "quic_disabled".to_string(),
                quic_candidate_label: "Current QUIC strategy".to_string(),
                quic_candidate_layout_family: None,
                rationale:
                    "dns_tampering classified before fallback; keep current strategy and prefer resolver override"
                        .to_string(),
                recommended_proxy_config_json: "{}".to_string(),
                transport_pivot: Some(TransportPivotRecommendation {
                    reason_code: "confirm_good_dpi_suspected".to_string(),
                    preferred_family: TransportFamily::UdpQuic,
                    viability: TransportPivotViability::Confirmed,
                    selected_relay_role: Some("hysteria2".to_string()),
                }),
            },
            completion_kind: StrategyProbeCompletionKind::DnsShortCircuited,
            audit_assessment: Some(StrategyProbeAuditAssessment {
                dns_short_circuited: true,
                coverage: StrategyProbeAuditCoverage {
                    tcp_candidates_planned: 11,
                    tcp_candidates_executed: 0,
                    tcp_candidates_skipped: 1,
                    tcp_candidates_not_applicable: 0,
                    quic_candidates_planned: 2,
                    quic_candidates_executed: 0,
                    quic_candidates_skipped: 1,
                    quic_candidates_not_applicable: 0,
                    tcp_winner_succeeded_targets: 0,
                    tcp_winner_total_targets: 6,
                    quic_winner_succeeded_targets: 0,
                    quic_winner_total_targets: 2,
                    matrix_coverage_percent: 0,
                    winner_coverage_percent: 0,
                    tcp_winner_coverage_percent: 0,
                    quic_winner_coverage_percent: 0,
                },
                confidence: StrategyProbeAuditConfidence {
                    level: StrategyProbeAuditConfidenceLevel::Low,
                    score: 35,
                    rationale: "Baseline DNS tampering short-circuited the audit before fallback candidates ran"
                        .to_string(),
                    warnings: vec![
                        "Baseline DNS tampering short-circuited the audit before fallback candidates ran.".to_string(),
                    ],
                },
            }),
            connection_concurrency_assessment: Some(ConnectionConcurrencyAssessment {
                classifier_version: "connection_concurrency_v1".to_string(),
                verdict: ConnectionConcurrencyVerdict::ConjunctionConfirmed,
                selected_profile_id: Some("firefox_stable".to_string()),
                safe_cap: Some(4),
                planned_cells: 36,
                clean_cells: 34,
                affected_targets: 2,
                healthy_caps_by_profile: BTreeMap::from([("firefox_stable".to_string(), 4)]),
                warnings: vec!["Proxy-mode browser TLS is not controlled.".to_string()],
            }),
            target_selection: Some(StrategyProbeTargetSelection {
                cohort_id: "global-core".to_string(),
                cohort_label: "Global core".to_string(),
                domain_hosts: vec!["www.youtube.com".to_string(), "discord.com".to_string()],
                quic_hosts: vec!["www.youtube.com".to_string()],
            }),
            pilot_bucket_labels: vec!["foreign:google:ech=yes".to_string(), "foreign:direct:ech=no".to_string()],
            domain_strategy_seeds: vec![],
        }),
        confirm_good_dpi_verdict: Some(ConfirmGoodDpiVerdict {
            status: ConfirmGoodDpiVerdictStatus::Suspected,
            evidence: ConfirmGoodDpiEvidence {
                source: ConfirmGoodDpiEvidenceSource::Mixed,
                stalled_flow_count: 2,
                distinct_target_count: 2,
                catalog_profile_validated: true,
                reality_handshake_confirmed: true,
                application_response_bytes: 0,
                quic_control_succeeded: true,
            },
        }),
        observations: vec![ProbeObservation {
            kind: ObservationKind::ConnectionConcurrency,
            target: "control-target".to_string(),
            dns: None,
            domain: None,
            tcp: None,
            quic: None,
            service: None,
            circumvention: None,
            telegram: None,
            throughput: None,
            strategy: None,
            connection_concurrency: Some(ConnectionConcurrencyObservationFact {
                cohort_id: "global-platform-control-v1".to_string(),
                tls_profile_id: "firefox_stable".to_string(),
                requested_parallelism: 4,
                observed_peak_parallelism: 4,
                launch_spread_ms: 50,
                burst_window_ms: 800,
                successes: 4,
                failures: 0,
                block_signals: vec![],
                status: ConnectionConcurrencyCellStatus::Healthy,
                contaminated: false,
                skip_reason: None,
            }),
            evidence: vec![],
        }],
        engine_analysis_version: Some("1.0".to_string()),
        diagnoses: vec![],
        classifier_version: Some("1.0".to_string()),
        pack_versions: BTreeMap::from([("core".to_string(), 1)]),
        execution_plan: Some(execution_plan_fixture()),
    };

    let json = serde_json::to_value(&report).expect("serialize report");
    let paths = extract_field_paths(&json);
    let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
    assert_contract_fixture("diagnostics_scan_report_fields.json", &manifest);
}

fn execution_plan_fixture() -> ExecutionPlanSnapshot {
    let candidate = StrategyCandidatePlanSnapshot {
        id: "baseline_current".to_string(),
        label: "Current strategy".to_string(),
        family: "baseline_current".to_string(),
        emitter_tier: StrategyEmitterTier::NonRootProduction,
        exact_emitter_requires_root: false,
        approximate_fallback_family: None,
        quic_layout_family: None,
        eligibility: "always".to_string(),
        warmup: "none".to_string(),
        preserve_adaptive_fake_ttl: false,
        requires_fake_ttl: false,
        requires_tcp_fast_open: false,
        required_capabilities: vec![],
    };
    ExecutionPlanSnapshot {
        plan_version: "execution_plan_v1".to_string(),
        scan_kind: ScanKind::StrategyProbe,
        profile_family: DiagnosticProfileFamily::AutomaticAudit,
        path_mode: ScanPathMode::RawPath,
        transport_kind: "direct".to_string(),
        stage_order: vec!["environment".to_string(), "strategy_tcp_candidates".to_string()],
        total_steps: 2,
        scan_deadline_ms: 270_000,
        pack_refs: vec!["ru-global-platforms@1".to_string()],
        probe_task_families: vec![ProbeTaskFamily::Tcp],
        target_counts: ExecutionPlanTargetCounts {
            domain_target_count: 6,
            dns_target_count: 2,
            tcp_target_count: 3,
            quic_target_count: 2,
            service_target_count: 0,
            circumvention_target_count: 0,
            throughput_target_count: 0,
            whitelist_sni_count: 2,
            telegram_target_count: 0,
            strategy_selected_domain_count: 2,
            strategy_selected_quic_count: 1,
        },
        strategy: Some(StrategyExecutionPlanSnapshot {
            suite_id: "full_matrix_v1".to_string(),
            inventory_semantics: "ordered_pre_runtime_filter_pool".to_string(),
            probe_seed: u64::MAX.to_string(),
            max_candidates: Some(44),
            tcp_candidates: vec![candidate.clone()],
            quic_candidates: vec![candidate],
            short_circuit_hostfake: true,
            short_circuit_quic_burst: true,
            family_failure_threshold: 3,
        }),
    }
}
