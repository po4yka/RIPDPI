mod contract_fixture;
mod plan;
mod report;
mod runners;
mod runtime;
mod scan;
mod strategy_plan;

pub use runners::probe_descriptors_as_json;
pub use scan::run_engine_scan;

pub(crate) use contract_fixture::connectivity_partial_report_contract_fixture;
pub use contract_fixture::{RunnerParityRecord, RunnerStepSnapshot, connectivity_runner_parity_snapshot};
pub(crate) use report::{ReportBuildContext, build_report};
pub(crate) use runtime::panic_recovery::panic_payload_message;

#[cfg(test)]
mod tests {
    use std::sync::atomic::AtomicBool;
    use std::sync::{Arc, Mutex};

    use super::*;
    use crate::types::{
        ConfirmGoodDpiEvidence, ConfirmGoodDpiEvidenceSource, ProbeDetail, ProbeResult, ProbeTaskFamily, ScanKind,
        ScanRequest, SharedState,
    };

    use plan::{connectivity_stage_order, strategy_stage_order};
    use report::{connectivity_analytics_summary, connectivity_summary};
    use runtime::{ExecutionStageId, cancelled_run_summary};

    #[test]
    fn connectivity_summary_reports_bucket_breakdown() {
        let mut results = vec![probe("network_environment", "wifi", "network_available")];
        results.extend((0..24).map(|index| probe("dns_integrity", format!("dns-{index}"), "dns_match")));
        results.extend((0..4).map(|index| probe("domain_reachability", format!("tls-{index}"), "tls_ok")));
        results.push(probe("tcp_fat_header", "8.8.8.8:443 (Google DNS)", "whitelist_sni_ok"));
        results.push(probe("tcp_fat_header", "172.67.70.222:443 (Cloudflare)", "whitelist_sni_failed"));

        assert_eq!(
            connectivity_summary(&results, &crate::types::ScanPathMode::RawPath),
            "31 completed · 30 healthy · 1 failed",
        );
    }

    #[test]
    fn connectivity_summary_omits_zero_value_non_healthy_buckets() {
        assert_eq!(connectivity_summary(&[], &crate::types::ScanPathMode::RawPath), "0 completed · 0 healthy");
    }

    #[test]
    fn connectivity_analytics_summary_captures_remaining_attention_surface() {
        let results = vec![
            probe("network_environment", "wifi", "network_available"),
            probe("dns_integrity", "google.com", "dns_compatible_divergence"),
            ProbeResult {
                probe_type: "tcp_fat_header".to_string(),
                target: "8.8.8.8:443 (Google DNS)".to_string(),
                outcome: "tcp_reset".to_string(),
                details: vec![ProbeDetail { key: "tcpBlockMethod".to_string(), value: "rst_injection".to_string() }],
            },
            ProbeResult {
                probe_type: "tcp_fat_header".to_string(),
                target: "172.67.70.222:443 (Cloudflare)".to_string(),
                outcome: "tcp_16kb_blocked".to_string(),
                details: vec![ProbeDetail { key: "tcpBlockMethod".to_string(), value: "window_cap".to_string() }],
            },
            ProbeResult {
                probe_type: "domain_reachability".to_string(),
                target: "cloudflare.com".to_string(),
                outcome: "tls_ok".to_string(),
                details: vec![ProbeDetail { key: "httpStatusClass".to_string(), value: "redirect".to_string() }],
            },
        ];

        let summary = connectivity_analytics_summary(&results, &crate::types::ScanPathMode::RawPath);

        assert!(summary.contains("dns_compatible_divergence=1"));
        assert!(summary.contains("dns_contextual_downgraded=0"));
        assert!(summary.contains("attention=1"));
        assert!(summary.contains("tcp_attention=2"));
        assert!(summary.contains("tcp_contextual_downgraded=2"));
        assert!(summary.contains("tcp_resets=1"));
        assert!(summary.contains("tcp_window_cap=1"));
        assert!(summary.contains("domain_tls_ok=1"));
        assert!(summary.contains("domain_http_ok_or_redirect=1"));
        assert!(summary.contains("network_verdict=attention_required"));
    }

    #[test]
    fn connectivity_summary_treats_weak_cdn_dns_variance_as_healthy_when_reachable() {
        let results = vec![
            probe("network_environment", "wifi", "network_available"),
            dns_compatible_probe("google.com", "0"),
            dns_compatible_probe("www.google.com", "10"),
            probe("domain_reachability", "www.google.com", "tls_ok"),
            ProbeResult {
                probe_type: "tcp_fat_header".to_string(),
                target: "8.8.8.8:443 (Google DNS)".to_string(),
                outcome: "tcp_reset".to_string(),
                details: vec![ProbeDetail { key: "tcpBlockMethod".to_string(), value: "rst_injection".to_string() }],
            },
        ];

        assert_eq!(connectivity_summary(&results, &crate::types::ScanPathMode::RawPath), "5 completed · 5 healthy",);

        let analytics = connectivity_analytics_summary(&results, &crate::types::ScanPathMode::RawPath);
        assert!(analytics.contains("dns_compatible_divergence=2"));
        assert!(analytics.contains("dns_contextual_downgraded=2"));
        assert!(analytics.contains("tcp_contextual_downgraded=1"));
        assert!(
            analytics.contains(
                "network_verdict=healthy_connectivity_with_cdn_dns_variance_and_tcp_stress_probe_sensitivity",
            ),
        );
    }

    #[test]
    fn connectivity_summary_contextualizes_tcp_stress_when_domain_tls_is_healthy() {
        let results = vec![
            probe("network_environment", "wifi", "network_available"),
            probe("domain_reachability", "www.google.com", "tls_ok"),
            probe("domain_reachability", "www.cloudflare.com", "tls_ok"),
            ProbeResult {
                probe_type: "tcp_fat_header".to_string(),
                target: "172.67.70.222:443 (Cloudflare)".to_string(),
                outcome: "tcp_16kb_blocked".to_string(),
                details: vec![ProbeDetail { key: "tcpBlockMethod".to_string(), value: "window_cap".to_string() }],
            },
            ProbeResult {
                probe_type: "tcp_fat_header".to_string(),
                target: "8.8.8.8:443 (Google DNS)".to_string(),
                outcome: "tcp_reset".to_string(),
                details: vec![ProbeDetail { key: "tcpBlockMethod".to_string(), value: "rst_injection".to_string() }],
            },
            ProbeResult {
                probe_type: "tcp_fat_header".to_string(),
                target: "9.9.9.9:443 (Quad9 DNS)".to_string(),
                outcome: "tcp_reset".to_string(),
                details: vec![ProbeDetail { key: "tcpBlockMethod".to_string(), value: "rst_injection".to_string() }],
            },
        ];

        assert_eq!(connectivity_summary(&results, &crate::types::ScanPathMode::RawPath), "6 completed · 6 healthy",);

        let analytics = connectivity_analytics_summary(&results, &crate::types::ScanPathMode::RawPath);
        assert!(analytics.contains("healthy=6"));
        assert!(analytics.contains("attention=0"));
        assert!(analytics.contains("tcp_attention=3"));
        assert!(analytics.contains("tcp_contextual_downgraded=3"));
        assert!(analytics.contains("tcp_resets=2"));
        assert!(analytics.contains("tcp_window_cap=1"));
        assert!(analytics.contains("network_verdict=healthy_connectivity_with_tcp_stress_probe_sensitivity"));
    }

    #[test]
    fn connectivity_summary_keeps_tcp_stress_as_attention_when_domain_reachability_fails() {
        let results = vec![
            probe("network_environment", "wifi", "network_available"),
            probe("domain_reachability", "www.google.com", "unreachable"),
            ProbeResult {
                probe_type: "tcp_fat_header".to_string(),
                target: "8.8.8.8:443 (Google DNS)".to_string(),
                outcome: "tcp_reset".to_string(),
                details: vec![ProbeDetail { key: "tcpBlockMethod".to_string(), value: "rst_injection".to_string() }],
            },
        ];

        assert_eq!(
            connectivity_summary(&results, &crate::types::ScanPathMode::RawPath),
            "3 completed · 1 healthy · 1 attention · 1 failed",
        );

        let analytics = connectivity_analytics_summary(&results, &crate::types::ScanPathMode::RawPath);
        assert!(analytics.contains("tcp_contextual_downgraded=0"));
        assert!(analytics.contains("network_verdict=network_failures_detected"));
    }

    #[test]
    fn connectivity_summary_keeps_strong_dns_divergence_as_attention() {
        let mut dns_probe = dns_compatible_probe("google.com", "20");
        dns_probe.details.push(ProbeDetail { key: "recordTypeMismatch".to_string(), value: "true".to_string() });
        let results = vec![
            probe("network_environment", "wifi", "network_available"),
            dns_probe,
            probe("domain_reachability", "www.google.com", "tls_ok"),
        ];

        assert_eq!(
            connectivity_summary(&results, &crate::types::ScanPathMode::RawPath),
            "3 completed · 2 healthy · 1 attention",
        );
    }

    #[test]
    fn connectivity_stage_order_deduplicates_requested_families() {
        let request = scan_request(
            ScanKind::Connectivity,
            vec![
                crate::types::ProbeTask {
                    family: ProbeTaskFamily::Tcp,
                    target_id: "tcp-1".to_string(),
                    label: "tcp".to_string(),
                },
                crate::types::ProbeTask {
                    family: ProbeTaskFamily::Dns,
                    target_id: "dns-1".to_string(),
                    label: "dns".to_string(),
                },
                crate::types::ProbeTask {
                    family: ProbeTaskFamily::Tcp,
                    target_id: "tcp-2".to_string(),
                    label: "tcp2".to_string(),
                },
                crate::types::ProbeTask {
                    family: ProbeTaskFamily::Service,
                    target_id: "svc-1".to_string(),
                    label: "service".to_string(),
                },
            ],
            None,
        );

        assert_eq!(
            connectivity_stage_order(&request),
            vec![
                ExecutionStageId::Environment,
                ExecutionStageId::Tcp,
                ExecutionStageId::Dns,
                ExecutionStageId::Service,
            ],
        );
    }

    #[test]
    fn confirm_good_candidate_runs_quic_before_tcp() {
        let mut request = scan_request(ScanKind::StrategyProbe, Vec::new(), None);
        request.confirm_good_dpi_evidence = Some(ConfirmGoodDpiEvidence {
            source: ConfirmGoodDpiEvidenceSource::Active,
            stalled_flow_count: 2,
            distinct_target_count: 2,
            catalog_profile_validated: true,
            reality_handshake_confirmed: true,
            application_response_bytes: 0,
            quic_control_succeeded: false,
        });

        assert_eq!(
            strategy_stage_order(&request),
            vec![
                ExecutionStageId::Environment,
                ExecutionStageId::StrategyDnsBaseline,
                ExecutionStageId::StrategyQuicCandidates,
                ExecutionStageId::StrategyTcpCandidates,
                ExecutionStageId::StrategyConnectionConcurrency,
                ExecutionStageId::StrategyRecommendation,
            ],
        );
    }

    #[test]
    fn in_path_strategy_probe_observes_active_service_before_candidate_matrix() {
        let mut request = scan_request(ScanKind::StrategyProbe, Vec::new(), None);
        request.path_mode = crate::types::ScanPathMode::InPath;
        request.in_path_route = Some(crate::types::InPathRoute {
            host: "127.0.0.1".to_string(),
            port: 19_080,
            credentials: crate::types::InPathProxyCredentials {
                username: "diagnostics".to_string(),
                password: "bounded-secret".to_string(),
            },
        });
        request.domain_targets.push(crate::types::DomainTarget {
            host: "127.0.0.1".to_string(),
            connect_ip: None,
            connect_ips: Vec::new(),
            https_port: Some(443),
            http_port: Some(80),
            http_path: "/".to_string(),
            is_control: true,
            concurrency_probe: None,
        });

        assert_eq!(
            strategy_stage_order(&request),
            vec![
                ExecutionStageId::Environment,
                ExecutionStageId::Web,
                ExecutionStageId::StrategyDnsBaseline,
                ExecutionStageId::StrategyTcpCandidates,
                ExecutionStageId::StrategyQuicCandidates,
                ExecutionStageId::StrategyConnectionConcurrency,
                ExecutionStageId::StrategyRecommendation,
            ],
        );
    }

    #[test]
    fn run_engine_scan_records_planning_errors_in_report_and_progress() {
        let shared = Arc::new(Mutex::new(SharedState::default()));
        let cancel = Arc::new(AtomicBool::new(false));
        let request = scan_request(
            ScanKind::StrategyProbe,
            Vec::new(),
            Some(crate::types::StrategyProbeRequest {
                suite_id: "phase1".to_string(),
                base_proxy_config_json: None,
                target_selection: None,
                max_candidates: None,
            }),
        );

        run_engine_scan(
            shared.clone(),
            cancel,
            "session-1".to_string(),
            request,
            None,
            Arc::new(crate::execution::UnavailableCandidateRuntimeLauncher),
        );

        let state = shared.lock().expect("shared state lock");
        let report = state.report.clone().expect("report");
        let progress = state.progress.clone().expect("progress");

        assert_eq!(report.summary, "strategy_probe scan requires baseProxyConfigJson");
        assert!(report.results.is_empty());
        assert_eq!(report.completion_kind, crate::types::ScanCompletionKind::Terminated);
        assert_eq!(report.termination_reason, Some(crate::types::ScanTerminationReason::EngineError));
        assert_eq!(progress.phase, "finished");
        assert_eq!(progress.completed_steps, 1);
        assert_eq!(progress.total_steps, 1);
        assert!(progress.is_finished);
    }

    #[test]
    fn cancelled_run_summary_reports_partial_results_when_probe_data_exists() {
        assert_eq!(cancelled_run_summary(true), "Scan completed with partial results");
    }

    #[test]
    fn cancelled_run_summary_reports_cancelled_when_probe_data_is_empty() {
        assert_eq!(cancelled_run_summary(false), "Scan cancelled");
    }

    fn probe(probe_type: &str, target: impl Into<String>, outcome: &str) -> ProbeResult {
        ProbeResult {
            probe_type: probe_type.to_string(),
            target: target.into(),
            outcome: outcome.to_string(),
            details: Vec::new(),
        }
    }

    fn dns_compatible_probe(target: impl Into<String>, comparison_score: &str) -> ProbeResult {
        ProbeResult {
            probe_type: "dns_integrity".to_string(),
            target: target.into(),
            outcome: "dns_compatible_divergence".to_string(),
            details: vec![
                ProbeDetail { key: "dnsHttpsClass".to_string(), value: "HTTPS_RR_PRESENT".to_string() },
                ProbeDetail { key: "comparisonScore".to_string(), value: comparison_score.to_string() },
                ProbeDetail { key: "comparisonSignals".to_string(), value: "answer_count_divergent".to_string() },
            ],
        }
    }

    fn scan_request(
        kind: ScanKind,
        probe_tasks: Vec<crate::types::ProbeTask>,
        strategy_probe: Option<crate::types::StrategyProbeRequest>,
    ) -> ScanRequest {
        ScanRequest {
            profile_id: "profile".to_string(),
            display_name: "Phase 1".to_string(),
            path_mode: crate::types::ScanPathMode::RawPath,
            kind,
            family: crate::types::DiagnosticProfileFamily::General,
            region_tag: None,
            manual_only: false,
            pack_refs: Vec::new(),
            proxy_host: None,
            proxy_port: None,
            in_path_route: None,
            probe_tasks,
            domain_targets: Vec::new(),
            dns_targets: Vec::new(),
            tcp_targets: Vec::new(),
            quic_targets: Vec::new(),
            service_targets: Vec::new(),
            circumvention_targets: Vec::new(),
            throughput_targets: Vec::new(),
            whitelist_sni: Vec::new(),
            telegram_target: None,
            strategy_probe,
            confirm_good_dpi_evidence: None,
            network_snapshot: None,
            route_probe: None,
            scan_deadline_ms: None,
            diagnostic_tls_keylog_path: None,
        }
    }
}
