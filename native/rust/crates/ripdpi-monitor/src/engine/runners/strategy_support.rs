use std::time::Duration;

use ripdpi_failure_classifier::{ClassifiedFailure, FailureClass};
use ripdpi_runtime::platform::RuntimeCapability;

use crate::candidates::{strategy_probe_config_json, CandidateEligibility, StrategyCandidateSpec};
use crate::classification::{interleave_candidate_families, reorder_tcp_candidates_for_failure};
use crate::execution::not_applicable_candidate_execution;
use crate::types::StrategyProbeCandidateSummary as CandidateSummary;
use crate::types::{
    DomainTarget, StrategyProbeAuditAssessment, StrategyProbeAuditConfidence, StrategyProbeAuditConfidenceLevel,
    StrategyProbeAuditCoverage, StrategyProbeRecommendation,
};
use crate::types::{ProbeResult, StrategyProbeCandidateSummary, StrategyProbeLiveProgress, StrategyProbeProgressLane};
use crate::util::STRATEGY_PROBE_SUITE_FULL_MATRIX_V1;

use super::super::super::runtime::{ExecutionPlan, ExecutionRuntime, RunnerArtifacts};

pub(super) const ECH_ELIGIBILITY_RATIONALE: &str =
    "Baseline did not expose an ECH-capable HTTPS target, so ECH extension splitting would be a no-op";

pub(super) const FAKE_TTL_ELIGIBILITY_RATIONALE: &str =
    "setsockopt(IP_TTL) is unavailable on this platform (Android VPN/tun mode); fake-packet strategies that rely on TTL manipulation are skipped";

pub(super) const TCP_FAST_OPEN_ELIGIBILITY_RATIONALE: &str =
    "TCP Fast Open is unavailable on this device/kernel, so TFO probe variants are skipped";

const ROOTED_EMITTER_ELIGIBILITY_RATIONALE: &str = "Requires rooted production emitter tier";
const LAB_EMITTER_ELIGIBILITY_RATIONALE: &str = "Requires lab-only emitter tier";
const GENERIC_EMITTER_ELIGIBILITY_RATIONALE: &str = "Required emitter capability unavailable";

pub(super) fn record_not_applicable_tcp_candidate(
    runtime: &mut ExecutionRuntime,
    plan: &ExecutionPlan,
    phase: &str,
    spec: &StrategyCandidateSpec,
    candidate_index: usize,
    candidate_total: usize,
    reason: &str,
    log_suffix: &str,
) {
    let execution = not_applicable_candidate_execution(spec, plan.request.domain_targets.len() * 2, 3, reason);
    runtime.record_step(
        plan,
        phase,
        format!("Marked {} as not applicable{}", spec.label, log_suffix),
        Some(spec.label.to_string()),
        Some(execution.summary.outcome.clone()),
        Some(strategy_probe_live_progress_with_targets(
            StrategyProbeProgressLane::Tcp,
            candidate_index,
            candidate_total,
            spec.id,
            spec.label,
            0,
            0,
        )),
        RunnerArtifacts::from_results(
            execution.results.clone(),
            "strategy_probe",
            "debug",
            format!("Skipped execution for {}{}", spec.label, log_suffix),
        ),
    );
    runtime.strategy.tcp_candidates.push(execution.summary);
}

pub(super) fn resolve_recommended_proxy_config_json(
    quic_candidate: &CandidateSummary,
    fallback_quic_spec: &StrategyCandidateSpec,
) -> String {
    quic_candidate
        .proxy_config_json
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .map_or_else(|| strategy_probe_config_json(&fallback_quic_spec.config), str::to_owned)
}

pub(super) fn strategy_probe_live_progress_with_targets(
    lane: StrategyProbeProgressLane,
    candidate_index: usize,
    candidate_total: usize,
    candidate_id: &str,
    candidate_label: &str,
    succeeded_targets: usize,
    total_targets: usize,
) -> StrategyProbeLiveProgress {
    StrategyProbeLiveProgress {
        lane,
        candidate_index,
        candidate_total,
        candidate_id: candidate_id.to_string(),
        candidate_label: candidate_label.to_string(),
        succeeded_targets,
        total_targets,
    }
}

pub(super) struct FamilyFailureTracker<'a> {
    blocked: Option<&'a str>,
    last_failed: Option<&'a str>,
    consecutive: usize,
    threshold: usize,
}

impl<'a> FamilyFailureTracker<'a> {
    pub(super) fn new(threshold: usize) -> Self {
        Self { blocked: None, last_failed: None, consecutive: 0, threshold }
    }

    pub(super) fn record(&mut self, family: &'a str, failed: bool) {
        if failed {
            if self.last_failed == Some(family) {
                self.consecutive += 1;
            } else {
                self.last_failed = Some(family);
                self.consecutive = 1;
            }
            if self.consecutive >= self.threshold {
                self.blocked = Some(family);
                self.consecutive = 0;
            }
        } else {
            self.last_failed = None;
            self.consecutive = 0;
            self.blocked = None;
        }
        if self.blocked.is_some() && family != self.blocked.unwrap_or_default() {
            self.blocked = None;
        }
    }

    pub(super) fn blocked_family(&self) -> Option<&'a str> {
        self.blocked
    }
}

fn probe_detail_value<'a>(result: &'a ProbeResult, key: &str) -> Option<&'a str> {
    result.details.iter().find(|detail| detail.key == key).map(|detail| detail.value.as_str())
}

pub(super) fn compute_rst_adaptive_timeout(baseline_failure: &ClassifiedFailure) -> Option<Duration> {
    if !matches!(baseline_failure.class, FailureClass::TcpReset) {
        return None;
    }
    Some(Duration::from_millis(1500))
}

#[cfg(test)]
pub(super) fn baseline_has_tls_ech_only(results: &[ProbeResult]) -> bool {
    results.iter().any(|result| result.probe_type == "strategy_https" && result.outcome == "tls_ech_only")
}

pub(super) fn baseline_supports_ech_candidates(results: &[ProbeResult]) -> bool {
    results.iter().any(|result| {
        result.probe_type == "strategy_https"
            && (result.outcome == "tls_ech_only"
                || probe_detail_value(result, "tlsEchResolutionDetail") == Some("ech_config_available")
                || probe_detail_value(result, "cdnProvider").is_some_and(|value| !value.trim().is_empty()))
    })
}

pub(super) fn ordered_follow_up_tcp_candidates(
    tcp_specs: &[StrategyCandidateSpec],
    failure_class: Option<FailureClass>,
    baseline_results: &[ProbeResult],
    probe_seed: u64,
    fake_ttl_available: bool,
) -> Vec<StrategyCandidateSpec> {
    let reordered = reorder_tcp_candidates_for_failure(tcp_specs, failure_class, fake_ttl_available)
        .into_iter()
        .skip(1)
        .collect::<Vec<_>>();
    if !baseline_supports_ech_candidates(baseline_results) {
        return interleave_candidate_families(reordered, probe_seed);
    }

    let mut ech_priority = Vec::new();
    let mut remaining = Vec::new();
    for spec in reordered {
        if spec.eligibility == CandidateEligibility::RequiresEchCapability {
            ech_priority.push(spec);
        } else {
            remaining.push(spec);
        }
    }
    ech_priority.extend(interleave_candidate_families(remaining, probe_seed));
    ech_priority
}

#[derive(Clone, Copy)]
pub(super) struct StrategyAuditLaneCounts {
    pub(super) planned: usize,
    pub(super) executed: usize,
    pub(super) skipped: usize,
    pub(super) not_applicable: usize,
}

impl StrategyAuditLaneCounts {
    pub(super) fn applicable_planned(self) -> usize {
        self.planned.saturating_sub(self.not_applicable)
    }
}

pub(super) fn round_percent(numerator: usize, denominator: usize) -> usize {
    if denominator == 0 {
        0
    } else {
        (numerator.saturating_mul(100) + (denominator / 2)) / denominator
    }
}

pub(super) fn strategy_audit_lane_counts(
    candidates: &[StrategyProbeCandidateSummary],
    planned: usize,
) -> StrategyAuditLaneCounts {
    StrategyAuditLaneCounts {
        planned,
        executed: candidates
            .iter()
            .filter(|candidate| !candidate.skipped && candidate.outcome != "not_applicable")
            .count(),
        skipped: candidates.iter().filter(|candidate| candidate.skipped).count(),
        not_applicable: candidates.iter().filter(|candidate| candidate.outcome == "not_applicable").count(),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum PilotHostingFamily {
    Direct,
    Cloudflare,
    Google,
    DomesticCdn,
    ForeignCdn,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum PilotReachabilitySet {
    Control,
    Domestic,
    Foreign,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct PilotTargetBucket {
    hosting_family: PilotHostingFamily,
    reachability_set: PilotReachabilitySet,
    ech_likely: bool,
}

fn all_candidates_tied(candidates: &[StrategyProbeCandidateSummary]) -> bool {
    let eligible: Vec<_> = candidates.iter().filter(|c| !c.skipped && c.outcome != "not_applicable").collect();
    if eligible.len() < 2 {
        return false;
    }
    let first = &eligible[0];
    eligible
        .iter()
        .all(|c| c.weighted_success_score == first.weighted_success_score && c.quality_score == first.quality_score)
}

fn candidate_score_percent(candidate: &StrategyProbeCandidateSummary) -> usize {
    round_percent(candidate.weighted_success_score, candidate.total_weight)
}

fn winner_margin_percent(candidates: &[StrategyProbeCandidateSummary], winner_candidate_id: &str) -> usize {
    let executable_scores = candidates
        .iter()
        .filter(|candidate| !candidate.skipped && candidate.outcome != "not_applicable")
        .map(|candidate| (candidate.id.as_str(), candidate_score_percent(candidate)))
        .collect::<Vec<_>>();
    let Some((_, winner_score)) =
        executable_scores.iter().find(|(candidate_id, _)| *candidate_id == winner_candidate_id)
    else {
        return 0;
    };
    let runner_up_score = executable_scores
        .iter()
        .filter(|(candidate_id, _)| *candidate_id != winner_candidate_id)
        .map(|(_, score)| *score)
        .max()
        .unwrap_or(0);
    winner_score.saturating_sub(runner_up_score)
}

fn pilot_hosting_family(host: &str) -> PilotHostingFamily {
    let host = host.trim().to_ascii_lowercase();
    if host.ends_with(".workers.dev")
        || host.ends_with(".pages.dev")
        || host.contains("cloudflare")
        || host.ends_with(".cloudflare.com")
    {
        PilotHostingFamily::Cloudflare
    } else if host.ends_with(".google.com")
        || host.ends_with(".googlevideo.com")
        || host.ends_with(".googleapis.com")
        || host.ends_with(".gstatic.com")
        || host.ends_with(".youtube.com")
        || host.ends_with(".ytimg.com")
        || host.ends_with(".1e100.net")
    {
        PilotHostingFamily::Google
    } else if host.ends_with(".yandex.ru")
        || host.ends_with(".yandex.net")
        || host.ends_with(".ya.ru")
        || host.ends_with(".vk.com")
        || host.ends_with(".mail.ru")
        || host.ends_with(".ok.ru")
        || host.ends_with(".rutube.ru")
    {
        PilotHostingFamily::DomesticCdn
    } else if host.ends_with(".cdn77.org")
        || host.ends_with(".akamai.net")
        || host.ends_with(".akamaized.net")
        || host.ends_with(".fastly.net")
        || host.ends_with(".cloudfront.net")
        || host.ends_with(".edgekey.net")
        || host.contains("cdn")
    {
        PilotHostingFamily::ForeignCdn
    } else {
        PilotHostingFamily::Direct
    }
}

fn pilot_reachability_set(target: &DomainTarget) -> PilotReachabilitySet {
    if target.is_control || target.host.eq_ignore_ascii_case("control") {
        return PilotReachabilitySet::Control;
    }
    let host = target.host.trim().to_ascii_lowercase();
    if host.ends_with(".ru") || host.ends_with(".su") || host.ends_with(".xn--p1ai") {
        PilotReachabilitySet::Domestic
    } else {
        PilotReachabilitySet::Foreign
    }
}

fn pilot_target_bucket(target: &DomainTarget) -> PilotTargetBucket {
    let hosting_family = pilot_hosting_family(&target.host);
    PilotTargetBucket {
        hosting_family,
        reachability_set: pilot_reachability_set(target),
        ech_likely: matches!(hosting_family, PilotHostingFamily::Cloudflare | PilotHostingFamily::Google),
    }
}

pub(super) fn pilot_bucket_label(target: &DomainTarget) -> String {
    let bucket = pilot_target_bucket(target);
    format!(
        "{:?}:{:?}:ech={}",
        bucket.reachability_set,
        bucket.hosting_family,
        if bucket.ech_likely { "yes" } else { "no" }
    )
    .to_ascii_lowercase()
}

pub(super) fn stratified_pilot_targets(domain_targets: &[DomainTarget]) -> Vec<DomainTarget> {
    let mut selected = Vec::new();
    let mut seen_buckets = std::collections::HashSet::new();
    let mut selected_hosts = std::collections::HashSet::new();

    while selected.len() < 3 {
        let mut seen_reachability = std::collections::HashSet::new();
        let mut seen_hosting = std::collections::HashSet::new();
        for target in &selected {
            let bucket = pilot_target_bucket(target);
            seen_reachability.insert(bucket.reachability_set);
            seen_hosting.insert(bucket.hosting_family);
        }

        let Some(next_target) = domain_targets
            .iter()
            .filter(|target| !selected_hosts.contains(target.host.as_str()))
            .max_by_key(|target| {
                let bucket = pilot_target_bucket(target);
                (
                    matches!(bucket.reachability_set, PilotReachabilitySet::Control),
                    !seen_reachability.contains(&bucket.reachability_set),
                    !seen_hosting.contains(&bucket.hosting_family),
                    bucket.ech_likely,
                    !matches!(bucket.hosting_family, PilotHostingFamily::Direct),
                    matches!(bucket.reachability_set, PilotReachabilitySet::Domestic),
                    std::cmp::Reverse(target.host.as_str()),
                )
            })
        else {
            break;
        };

        selected_hosts.insert(next_target.host.clone());
        let bucket = pilot_target_bucket(next_target);
        if seen_buckets.insert(bucket) {
            selected.push(next_target.clone());
        }
    }

    if selected.is_empty() {
        if let Some(first) = domain_targets.first() {
            selected.push(first.clone());
        }
    }
    selected
}

pub(super) fn capability_available(
    capability: RuntimeCapability,
    fake_ttl_available: bool,
    ipfrag_caps: ripdpi_runtime::platform::IpFragmentationCapabilities,
) -> bool {
    match capability {
        RuntimeCapability::TtlWrite => fake_ttl_available,
        RuntimeCapability::RawTcpFakeSend => ipfrag_caps.raw_ipv4,
        RuntimeCapability::RawUdpFragmentation => crate::candidates::supports_udp_ip_fragmentation_for(ipfrag_caps),
        RuntimeCapability::ReplacementSocket | RuntimeCapability::RootHelperAvailable => ipfrag_caps.tcp_repair,
        RuntimeCapability::VpnProtectCallback | RuntimeCapability::NetworkBinding => true,
    }
}

pub(super) fn annotate_emitter_execution(
    summary: &mut StrategyProbeCandidateSummary,
    spec: &StrategyCandidateSpec,
    fake_ttl_available: bool,
    ipfrag_caps: ripdpi_runtime::platform::IpFragmentationCapabilities,
) {
    if spec.exact_emitter_requires_root
        && !ripdpi_runtime::platform::seqovl_supported()
        && spec.approximate_fallback_family.is_some()
    {
        summary.emitter_downgraded = true;
        if let Some(fallback_family) = spec.approximate_fallback_family {
            summary
                .notes
                .push(format!("Exact rooted emitter unavailable; executed approximate {fallback_family} fallback"));
        }
    }
    if summary.exact_emitter_requires_root {
        if let Some(capability) = spec
            .requires_capabilities
            .iter()
            .copied()
            .find(|&capability| !capability_available(capability, fake_ttl_available, ipfrag_caps))
        {
            summary.notes.push(missing_capability_note(spec, capability));
        }
    }
}

fn missing_capability_note(spec: &StrategyCandidateSpec, capability: RuntimeCapability) -> String {
    if spec.exact_emitter_requires_root
        || matches!(spec.emitter_tier, crate::types::StrategyEmitterTier::RootedProduction)
    {
        format!("Requires rooted production emitter tier ({})", capability.as_str())
    } else if matches!(spec.emitter_tier, crate::types::StrategyEmitterTier::LabDiagnosticsOnly) {
        format!("Lab-only emitter tier unavailable ({})", capability.as_str())
    } else {
        format!("Required emitter capability unavailable ({})", capability.as_str())
    }
}

pub(super) fn missing_capability_rationale(spec: &StrategyCandidateSpec) -> &'static str {
    if spec.exact_emitter_requires_root
        || matches!(spec.emitter_tier, crate::types::StrategyEmitterTier::RootedProduction)
    {
        ROOTED_EMITTER_ELIGIBILITY_RATIONALE
    } else if matches!(spec.emitter_tier, crate::types::StrategyEmitterTier::LabDiagnosticsOnly) {
        LAB_EMITTER_ELIGIBILITY_RATIONALE
    } else {
        GENERIC_EMITTER_ELIGIBILITY_RATIONALE
    }
}

pub(super) fn capability_suffix(capability: RuntimeCapability) -> &'static str {
    match capability {
        RuntimeCapability::TtlWrite => " — ttl_write unavailable",
        RuntimeCapability::RawTcpFakeSend => " — raw_tcp_fake_send unavailable",
        RuntimeCapability::RawUdpFragmentation => " — raw_udp_fragmentation unavailable",
        RuntimeCapability::ReplacementSocket => " — replacement_socket unavailable",
        RuntimeCapability::RootHelperAvailable => " — root_helper_available unavailable",
        RuntimeCapability::VpnProtectCallback => " — vpn_protect_callback unavailable",
        RuntimeCapability::NetworkBinding => " — network_binding unavailable",
    }
}

struct AuditSignals {
    weak_winner_coverage: bool,
    low_tcp_execution: bool,
    low_quic_execution: bool,
    narrow_tcp_margin: bool,
    narrow_quic_margin: bool,
    all_tcp_tied: bool,
    all_quic_tied: bool,
}

fn build_audit_confidence(dns_short_circuited: bool, signals: &AuditSignals) -> StrategyProbeAuditConfidence {
    let penalty_table: &[(bool, i32, &str)] = &[
        (dns_short_circuited, 45, "Baseline DNS tampering short-circuited the audit before fallback candidates ran."),
        (
            signals.weak_winner_coverage,
            25,
            "The winning TCP or QUIC lane recovered too few weighted targets to trust the recommendation.",
        ),
        (signals.low_tcp_execution, 15, "TCP matrix coverage stayed below 75% of applicable candidates."),
        (signals.low_quic_execution, 15, "QUIC matrix coverage stayed below 75% of applicable candidates."),
        (signals.narrow_tcp_margin, 10, "TCP winner margin stayed below 10 points over the next candidate."),
        (signals.narrow_quic_margin, 10, "QUIC winner margin stayed below 10 points over the next candidate."),
        (signals.all_tcp_tied, 20, "All TCP candidates produced identical results; the winner is arbitrary."),
        (signals.all_quic_tied, 15, "All QUIC candidates produced identical results; the winner is arbitrary."),
    ];
    let mut score = 100i32;
    let mut warnings = Vec::new();
    for &(condition, penalty, message) in penalty_table {
        if condition {
            score -= penalty;
            warnings.push(message.to_string());
        }
    }
    let score = score.clamp(0, 100) as usize;
    let level = if score >= 80 {
        StrategyProbeAuditConfidenceLevel::High
    } else if score >= 50 {
        StrategyProbeAuditConfidenceLevel::Medium
    } else {
        StrategyProbeAuditConfidenceLevel::Low
    };
    let rationale = match () {
        _ if dns_short_circuited => "Baseline DNS tampering short-circuited the audit before fallback candidates ran",
        _ if signals.weak_winner_coverage => "The winning TCP or QUIC lane recovered too few weighted targets",
        _ if signals.low_tcp_execution || signals.low_quic_execution => {
            "The audit did not execute enough of the applicable matrix to fully trust the winner"
        }
        _ if signals.all_tcp_tied || signals.all_quic_tied => {
            "All candidates in a lane produced identical results; the recommendation is arbitrary"
        }
        _ if signals.narrow_tcp_margin || signals.narrow_quic_margin => {
            "The winning candidates only narrowly outperformed the next-best options"
        }
        _ => "Matrix coverage and winner strength are consistent",
    }
    .to_string();
    StrategyProbeAuditConfidence { level, score, rationale, warnings }
}

fn build_audit_coverage(
    tcp_counts: StrategyAuditLaneCounts,
    quic_counts: StrategyAuditLaneCounts,
    tcp_winner: Option<&StrategyProbeCandidateSummary>,
    quic_winner: Option<&StrategyProbeCandidateSummary>,
    tcp_winner_coverage: usize,
    quic_winner_coverage: usize,
) -> StrategyProbeAuditCoverage {
    let total_planned = tcp_counts.applicable_planned() + quic_counts.applicable_planned();
    let total_executed = tcp_counts.executed + quic_counts.executed;
    StrategyProbeAuditCoverage {
        tcp_candidates_planned: tcp_counts.planned,
        tcp_candidates_executed: tcp_counts.executed,
        tcp_candidates_skipped: tcp_counts.skipped,
        tcp_candidates_not_applicable: tcp_counts.not_applicable,
        quic_candidates_planned: quic_counts.planned,
        quic_candidates_executed: quic_counts.executed,
        quic_candidates_skipped: quic_counts.skipped,
        quic_candidates_not_applicable: quic_counts.not_applicable,
        tcp_winner_succeeded_targets: tcp_winner.map_or(0, |c| c.succeeded_targets),
        tcp_winner_total_targets: tcp_winner.map_or(0, |c| c.total_targets),
        quic_winner_succeeded_targets: quic_winner.map_or(0, |c| c.succeeded_targets),
        quic_winner_total_targets: quic_winner.map_or(0, |c| c.total_targets),
        matrix_coverage_percent: round_percent(total_executed, total_planned),
        winner_coverage_percent: (tcp_winner_coverage + quic_winner_coverage).div_ceil(2),
        tcp_winner_coverage_percent: tcp_winner_coverage,
        quic_winner_coverage_percent: quic_winner_coverage,
    }
}

pub(super) fn resolve_strategy_probe_audit_assessment(
    suite_id: &str,
    tcp_candidates: &[StrategyProbeCandidateSummary],
    quic_candidates: &[StrategyProbeCandidateSummary],
    recommendation: &StrategyProbeRecommendation,
    tcp_candidates_planned: usize,
    quic_candidates_planned: usize,
    dns_short_circuited: bool,
) -> Option<StrategyProbeAuditAssessment> {
    if suite_id != STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 {
        return None;
    }

    let tcp_counts = strategy_audit_lane_counts(tcp_candidates, tcp_candidates_planned);
    let quic_counts = strategy_audit_lane_counts(quic_candidates, quic_candidates_planned);
    let tcp_winner = tcp_candidates.iter().find(|c| c.id == recommendation.tcp_candidate_id);
    let quic_winner = quic_candidates.iter().find(|c| c.id == recommendation.quic_candidate_id);
    let tcp_winner_coverage = tcp_winner.map_or(0, candidate_score_percent);
    let quic_winner_coverage = quic_winner.map_or(0, candidate_score_percent);
    let tcp_lane_coverage = round_percent(tcp_counts.executed, tcp_counts.applicable_planned());
    let quic_lane_coverage = round_percent(quic_counts.executed, quic_counts.applicable_planned());

    let signals = AuditSignals {
        weak_winner_coverage: tcp_winner_coverage < 50 || quic_winner_coverage < 50,
        low_tcp_execution: tcp_counts.applicable_planned() > 0 && tcp_lane_coverage < 75,
        low_quic_execution: quic_counts.applicable_planned() > 0 && quic_lane_coverage < 75,
        narrow_tcp_margin: winner_margin_percent(tcp_candidates, &recommendation.tcp_candidate_id) < 10,
        narrow_quic_margin: winner_margin_percent(quic_candidates, &recommendation.quic_candidate_id) < 10,
        all_tcp_tied: all_candidates_tied(tcp_candidates),
        all_quic_tied: all_candidates_tied(quic_candidates),
    };

    let coverage = build_audit_coverage(
        tcp_counts,
        quic_counts,
        tcp_winner,
        quic_winner,
        tcp_winner_coverage,
        quic_winner_coverage,
    );

    let no_evasion_needed = !dns_short_circuited
        && signals.all_tcp_tied
        && signals.all_quic_tied
        && recommendation.tcp_candidate_id == "baseline_current"
        && recommendation.quic_candidate_id == "baseline_current"
        && tcp_winner_coverage >= 80
        && quic_winner_coverage >= 80;

    if no_evasion_needed {
        return Some(StrategyProbeAuditAssessment {
            dns_short_circuited: false,
            coverage,
            confidence: StrategyProbeAuditConfidence {
                level: StrategyProbeAuditConfidenceLevel::High,
                score: 100,
                rationale: "All strategies performed equally — no evasion needed".to_string(),
                warnings: Vec::new(),
            },
        });
    }

    Some(StrategyProbeAuditAssessment {
        dns_short_circuited,
        coverage,
        confidence: build_audit_confidence(dns_short_circuited, &signals),
    })
}
