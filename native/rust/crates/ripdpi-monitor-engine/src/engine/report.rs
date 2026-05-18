use crate::classification::pack_versions_from_refs;
use crate::observations::ENGINE_ANALYSIS_VERSION;
use crate::types::{ProbeObservation, ProbeResult, ScanReport, ScanRequest, StrategyProbeReport};
use crate::util::classify_probe_outcome;
use ripdpi_telemetry::recorder;

pub(super) fn build_report(
    session_id: String,
    request: ScanRequest,
    started_at: u64,
    summary: String,
    results: Vec<ProbeResult>,
    observations: Vec<ProbeObservation>,
    strategy_probe_report: Option<StrategyProbeReport>,
    classifier_version: Option<String>,
) -> ScanReport {
    ScanReport {
        session_id,
        profile_id: request.profile_id,
        path_mode: request.path_mode,
        started_at,
        finished_at: crate::util::now_ms(),
        summary,
        results,
        observations,
        engine_analysis_version: Some(ENGINE_ANALYSIS_VERSION.to_string()),
        diagnoses: Vec::new(),
        classifier_version,
        pack_versions: pack_versions_from_refs(&request.pack_refs),
        strategy_probe_report,
        metrics_summary: recorder::snapshot(),
    }
}

pub(super) fn connectivity_summary(results: &[ProbeResult], path_mode: &crate::types::ScanPathMode) -> String {
    let mut healthy = 0usize;
    let mut attention = 0usize;
    let mut failed = 0usize;
    let mut inconclusive = 0usize;

    for result in results {
        match classify_probe_outcome(&result.probe_type, path_mode, &result.outcome).bucket {
            crate::util::ProbeOutcomeBucket::Healthy => healthy += 1,
            crate::util::ProbeOutcomeBucket::Attention => attention += 1,
            crate::util::ProbeOutcomeBucket::Failed => failed += 1,
            crate::util::ProbeOutcomeBucket::Inconclusive => inconclusive += 1,
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

pub(super) fn connectivity_analytics_summary(
    results: &[ProbeResult],
    path_mode: &crate::types::ScanPathMode,
) -> String {
    let mut buckets = BucketCounts::default();
    let mut dns_compatible_divergence = 0usize;
    let mut dns_suspicious = 0usize;
    let mut tcp_attention = 0usize;
    let mut tcp_resets = 0usize;
    let mut tcp_window_cap = 0usize;
    let mut domain_tls_ok = 0usize;
    let mut domain_http_ok_or_redirect = 0usize;
    let mut domain_http_unreachable = 0usize;

    for result in results {
        buckets.add(&result.probe_type, path_mode, &result.outcome);
        match result.probe_type.as_str() {
            "dns_integrity" => match result.outcome.as_str() {
                "dns_compatible_divergence" => dns_compatible_divergence += 1,
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
        "Diagnostics analytics total={} healthy={} attention={} failed={} inconclusive={} dns_compatible_divergence={} dns_suspicious={} tcp_attention={} tcp_resets={} tcp_window_cap={} domain_tls_ok={} domain_http_ok_or_redirect={} domain_http_unreachable={}",
        results.len(),
        buckets.healthy,
        buckets.attention,
        buckets.failed,
        buckets.inconclusive,
        dns_compatible_divergence,
        dns_suspicious,
        tcp_attention,
        tcp_resets,
        tcp_window_cap,
        domain_tls_ok,
        domain_http_ok_or_redirect,
        domain_http_unreachable,
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
    fn add(&mut self, probe_type: &str, path_mode: &crate::types::ScanPathMode, outcome: &str) {
        match classify_probe_outcome(probe_type, path_mode, outcome).bucket {
            crate::util::ProbeOutcomeBucket::Healthy => self.healthy += 1,
            crate::util::ProbeOutcomeBucket::Attention => self.attention += 1,
            crate::util::ProbeOutcomeBucket::Failed => self.failed += 1,
            crate::util::ProbeOutcomeBucket::Inconclusive => self.inconclusive += 1,
        }
    }
}

fn detail_value(result: &ProbeResult, key: &str) -> Option<String> {
    result.details.iter().find_map(|detail| (detail.key == key).then_some(detail.value.clone()))
}
