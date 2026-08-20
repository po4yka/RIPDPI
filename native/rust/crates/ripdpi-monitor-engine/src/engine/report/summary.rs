use crate::classification::pack_versions_from_refs;
use crate::observations::ENGINE_ANALYSIS_VERSION;
use crate::types::{
    ConfirmGoodDpiVerdict, ConfirmGoodDpiVerdictStatus, Diagnosis, ExecutionPlanSnapshot, ProbeObservation,
    ProbeResult, ScanCompletionKind, ScanReport, ScanRequest, StrategyProbeReport,
};
use crate::util::{ProbeOutcomeBucket, classify_probe_outcome};
use ripdpi_telemetry::recorder;
use std::collections::HashSet;

pub(crate) struct ReportBuildContext {
    pub(crate) session_id: String,
    pub(crate) request: ScanRequest,
    pub(crate) started_at: u64,
    pub(crate) execution_plan: Option<ExecutionPlanSnapshot>,
}

pub(crate) fn build_report(
    context: ReportBuildContext,
    summary: String,
    results: Vec<ProbeResult>,
    observations: Vec<ProbeObservation>,
    strategy_probe_report: Option<StrategyProbeReport>,
    classifier_version: Option<String>,
) -> ScanReport {
    let ReportBuildContext { session_id, request, started_at, execution_plan } = context;
    let confirm_good_dpi_verdict = strategy_probe_report
        .as_ref()
        .and_then(|report| report.recommendation.as_ref())
        .and_then(|recommendation| recommendation.transport_pivot.as_ref())
        .and_then(|_| request.confirm_good_dpi_evidence.clone())
        .map(|mut evidence| {
            evidence.quic_control_succeeded = true;
            ConfirmGoodDpiVerdict { status: ConfirmGoodDpiVerdictStatus::Suspected, evidence }
        });
    let diagnoses = confirm_good_dpi_verdict.as_ref().map_or_else(Vec::new, |_| {
        vec![Diagnosis {
            code: "confirm_good_dpi_suspected".to_string(),
            summary:
                "Reality handshakes succeeded, but application data did not establish while QUIC remained reachable"
                    .to_string(),
            severity: "warning".to_string(),
            target: None,
            evidence: vec![
                "two distinct Reality application flows stalled after handshake".to_string(),
                "same-network QUIC control succeeded".to_string(),
            ],
            recommendation: Some(
                "Use a tested UDP/QUIC relay; changing TLS fingerprints is unlikely to address this behavior"
                    .to_string(),
            ),
            control_validated: Some(true),
        }]
    });
    ScanReport {
        session_id,
        profile_id: request.profile_id,
        path_mode: request.path_mode,
        started_at,
        finished_at: crate::util::now_ms(),
        summary,
        completion_kind: ScanCompletionKind::Normal,
        termination_reason: None,
        results,
        observations,
        engine_analysis_version: Some(ENGINE_ANALYSIS_VERSION.to_string()),
        diagnoses,
        classifier_version,
        pack_versions: pack_versions_from_refs(&request.pack_refs),
        strategy_probe_report,
        confirm_good_dpi_verdict,
        metrics_summary: recorder::snapshot(),
        execution_plan,
        candidate_runtime_cleanup: None,
    }
}

pub(in crate::engine) fn connectivity_summary(
    results: &[ProbeResult],
    path_mode: &crate::types::ScanPathMode,
) -> String {
    let mut healthy = 0usize;
    let mut attention = 0usize;
    let mut failed = 0usize;
    let mut inconclusive = 0usize;
    let mut counted_dns_findings = HashSet::new();

    for result in results {
        let bucket = effective_probe_bucket(result, results, path_mode);
        if bucket != ProbeOutcomeBucket::Healthy && is_dns_finding(result) {
            let key = dns_finding_key(result);
            if !counted_dns_findings.insert(key) {
                continue;
            }
        }
        match bucket {
            ProbeOutcomeBucket::Healthy => healthy += 1,
            ProbeOutcomeBucket::Attention => attention += 1,
            ProbeOutcomeBucket::Failed => failed += 1,
            ProbeOutcomeBucket::Inconclusive => inconclusive += 1,
        }
    }

    let mut parts = vec![format!("{} completed", results.len()), format!("{healthy} healthy")];
    if attention > 0 {
        parts.push(format!("{attention} attention"));
    }
    if failed > 0 {
        parts.push(format!("{failed} failed"));
    }
    if inconclusive > 0 {
        parts.push(format!("{inconclusive} inconclusive"));
    }
    parts.join(" · ")
}

pub(in crate::engine) fn connectivity_analytics_summary(
    results: &[ProbeResult],
    path_mode: &crate::types::ScanPathMode,
) -> String {
    let mut buckets = BucketCounts::default();
    let mut dns_compatible_divergence = 0usize;
    let mut dns_contextual_downgraded = 0usize;
    let mut dns_suspicious = 0usize;
    let mut tcp_attention = 0usize;
    let mut tcp_contextual_downgraded = 0usize;
    let mut tcp_resets = 0usize;
    let mut tcp_window_cap = 0usize;
    let mut domain_tls_ok = 0usize;
    let mut domain_http_ok_or_redirect = 0usize;
    let mut domain_http_unreachable = 0usize;

    for result in results {
        let bucket = effective_probe_bucket(result, results, path_mode);
        buckets.add_bucket(bucket);
        match result.probe_type.as_str() {
            "dns_integrity" => match result.outcome.as_str() {
                "dns_compatible_divergence" => {
                    dns_compatible_divergence += 1;
                    if bucket == ProbeOutcomeBucket::Healthy {
                        dns_contextual_downgraded += 1;
                    }
                }
                "dns_suspicious_divergence"
                | "dns_sinkhole_substitution"
                | "dns_nxdomain_mismatch"
                | "udp_blocked"
                | "udp_skipped_or_blocked" => dns_suspicious += 1,
                _ => {}
            },
            "tcp_fat_header" => {
                if !matches!(result.outcome.as_str(), "tcp_fat_header_ok" | "fat_ok" | "tcp_ok" | "whitelist_sni_ok") {
                    tcp_attention += 1;
                    let tcp_stress_downgraded = is_tcp_stress_probe_sensitivity_outcome(&result.outcome)
                        && bucket == ProbeOutcomeBucket::Healthy;
                    if tcp_stress_downgraded {
                        tcp_contextual_downgraded += 1;
                    }
                }
                if result.outcome == "tcp_reset" {
                    tcp_resets += 1;
                }
                if detail_value(result, "tcpBlockMethod").as_deref() == Some("window_cap") {
                    tcp_window_cap += 1;
                }
            }
            "domain_reachability" => {
                if result.outcome == "tls_ok" {
                    domain_tls_ok += 1;
                }
                match detail_value(result, "httpStatusClass").as_deref() {
                    Some("success" | "redirect") => domain_http_ok_or_redirect += 1,
                    Some("unreachable") => domain_http_unreachable += 1,
                    _ => {
                        if detail_value(result, "httpStatus").as_deref() == Some("http_unreachable") {
                            domain_http_unreachable += 1;
                        }
                    }
                }
            }
            _ => {}
        }
    }

    format!(
        "Diagnostics analytics total={} healthy={} attention={} failed={} inconclusive={} dns_compatible_divergence={} dns_contextual_downgraded={} dns_suspicious={} tcp_attention={} tcp_contextual_downgraded={} tcp_resets={} tcp_window_cap={} domain_tls_ok={} domain_http_ok_or_redirect={} domain_http_unreachable={} network_verdict={}",
        results.len(),
        buckets.healthy,
        buckets.attention,
        buckets.failed,
        buckets.inconclusive,
        dns_compatible_divergence,
        dns_contextual_downgraded,
        dns_suspicious,
        tcp_attention,
        tcp_contextual_downgraded,
        tcp_resets,
        tcp_window_cap,
        domain_tls_ok,
        domain_http_ok_or_redirect,
        domain_http_unreachable,
        network_verdict(results, path_mode),
    )
}

#[derive(Default)]
struct BucketCounts {
    healthy: usize,
    attention: usize,
    failed: usize,
    inconclusive: usize,
}

impl BucketCounts {
    fn add_bucket(&mut self, bucket: ProbeOutcomeBucket) {
        match bucket {
            ProbeOutcomeBucket::Healthy => self.healthy += 1,
            ProbeOutcomeBucket::Attention => self.attention += 1,
            ProbeOutcomeBucket::Failed => self.failed += 1,
            ProbeOutcomeBucket::Inconclusive => self.inconclusive += 1,
        }
    }
}

fn effective_probe_bucket(
    result: &ProbeResult,
    results: &[ProbeResult],
    path_mode: &crate::types::ScanPathMode,
) -> ProbeOutcomeBucket {
    let base = classify_probe_outcome(&result.probe_type, path_mode, &result.outcome).bucket;
    let contextual_healthy =
        is_contextual_cdn_dns_variance(result, results) || is_contextual_tcp_stress_probe_sensitivity(result, results);
    if contextual_healthy { ProbeOutcomeBucket::Healthy } else { base }
}

fn network_verdict(results: &[ProbeResult], path_mode: &crate::types::ScanPathMode) -> &'static str {
    let has_failed =
        results.iter().any(|result| effective_probe_bucket(result, results, path_mode) == ProbeOutcomeBucket::Failed);
    if has_failed {
        return "network_failures_detected";
    }
    let has_contextual_dns = results.iter().any(|result| is_contextual_cdn_dns_variance(result, results));
    let has_tcp_stress = results.iter().any(|result| {
        result.probe_type == "tcp_fat_header" && is_tcp_stress_probe_sensitivity_outcome(&result.outcome)
    });
    let has_other_attention = results.iter().any(|result| {
        result.probe_type != "tcp_fat_header"
            && effective_probe_bucket(result, results, path_mode) == ProbeOutcomeBucket::Attention
    });
    match (has_other_attention, has_contextual_dns, has_tcp_stress) {
        (false, true, true) => "healthy_connectivity_with_cdn_dns_variance_and_tcp_stress_probe_sensitivity",
        (false, false, true) => "healthy_connectivity_with_tcp_stress_probe_sensitivity",
        (false, true, _) => "healthy_connectivity_with_cdn_dns_variance",
        (false, false, _) => "healthy_connectivity",
        _ => "attention_required",
    }
}

fn is_contextual_cdn_dns_variance(result: &ProbeResult, results: &[ProbeResult]) -> bool {
    result.probe_type == "dns_integrity"
        && result.outcome == "dns_compatible_divergence"
        && is_known_geodns_domain(&result.target)
        && has_weak_dns_divergence_evidence(result)
        && has_healthy_domain_reachability(&result.target, results)
}

fn is_contextual_tcp_stress_probe_sensitivity(result: &ProbeResult, results: &[ProbeResult]) -> bool {
    result.probe_type == "tcp_fat_header"
        && is_tcp_stress_probe_sensitivity_outcome(&result.outcome)
        && has_healthy_domain_reachability_any(results)
        && !has_domain_reachability_failure(results)
}

fn is_tcp_stress_probe_sensitivity_outcome(outcome: &str) -> bool {
    matches!(
        outcome,
        "tcp_16kb_blocked" | "tcp_reset" | "tcp_timeout" | "tcp_freeze_after_threshold" | "tls_handshake_failed"
    )
}

fn has_weak_dns_divergence_evidence(result: &ProbeResult) -> bool {
    let details = |key| detail_value(result, key);
    if matches!(details("recordTypeMismatch").as_deref(), Some("true"))
        || matches!(details("authorityMismatch").as_deref(), Some("true"))
        || matches!(details("malformedPointers").as_deref(), Some("true"))
        || matches!(details("dnsInjectionSuspected").as_deref(), Some("true"))
    {
        return false;
    }
    if details("dnsHttpsClass").as_deref() != Some("HTTPS_RR_PRESENT") {
        return false;
    }
    let comparison_score = details("comparisonScore").and_then(|value| value.parse::<u32>().ok()).unwrap_or(0);
    if comparison_score >= 20 {
        return false;
    }
    let comparison_signals = details("comparisonSignals").unwrap_or_default();
    !comparison_signals.split(',').any(|signal| {
        matches!(
            signal.trim(),
            "record_type_mismatch"
                | "rcode_mismatch"
                | "extra_cname_in_udp"
                | "authority_missing_in_udp"
                | "ttl_highly_divergent"
        )
    })
}

fn has_healthy_domain_reachability(dns_target: &str, results: &[ProbeResult]) -> bool {
    let dns_host = normalized_probe_authority(dns_target);
    results.iter().any(|candidate| {
        candidate.probe_type == "domain_reachability"
            && candidate.outcome == "tls_ok"
            && same_dns_site(&dns_host, &normalized_probe_authority(&candidate.target))
    })
}

fn has_healthy_domain_reachability_any(results: &[ProbeResult]) -> bool {
    results.iter().any(|candidate| candidate.probe_type == "domain_reachability" && candidate.outcome == "tls_ok")
}

fn has_domain_reachability_failure(results: &[ProbeResult]) -> bool {
    results.iter().any(|candidate| candidate.probe_type == "domain_reachability" && candidate.outcome != "tls_ok")
}

fn is_dns_finding(result: &ProbeResult) -> bool {
    result.probe_type == "dns_integrity"
}

fn dns_finding_key(result: &ProbeResult) -> (String, String) {
    (normalized_probe_authority(&result.target), result.outcome.clone())
}

fn is_known_geodns_domain(target: &str) -> bool {
    let host = normalized_probe_authority(target);
    ["google.com", "youtube.com", "ytimg.com", "googlevideo.com", "gstatic.com", "cloudflare.com"]
        .iter()
        .any(|domain| same_dns_site(&host, domain))
}

fn same_dns_site(left: &str, right: &str) -> bool {
    left == right || left.ends_with(&format!(".{right}")) || right.ends_with(&format!(".{left}"))
}

fn normalized_probe_authority(value: &str) -> String {
    let trimmed = value.trim();
    let without_scheme =
        trimmed.strip_prefix("https://").or_else(|| trimmed.strip_prefix("http://")).unwrap_or(trimmed);
    let authority = without_scheme
        .split('/')
        .next()
        .unwrap_or_default()
        .split(" (")
        .next()
        .unwrap_or_default()
        .split(':')
        .next()
        .unwrap_or_default();
    authority.strip_prefix("www.").unwrap_or(authority).to_ascii_lowercase()
}

fn detail_value(result: &ProbeResult, key: &str) -> Option<String> {
    result.details.iter().find_map(|detail| (detail.key == key).then_some(detail.value.clone()))
}
