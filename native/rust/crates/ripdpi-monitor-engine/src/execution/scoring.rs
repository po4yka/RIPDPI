use ripdpi_proxy_config::ProxyConfigPayload;

use crate::candidates::StrategyCandidateSpec;
use crate::types::{ProbeResult, StrategyProbeCandidateSummary, StrategyProbeDomainOutcome};

#[derive(Debug)]
pub struct CandidateExecution {
    pub summary: StrategyProbeCandidateSummary,
    pub results: Vec<ProbeResult>,
    pub cancelled: bool,
}

#[derive(Default)]
pub struct CandidateScore {
    pub results: Vec<ProbeResult>,
    pub succeeded_targets: usize,
    pub total_targets: usize,
    pub weighted_success_score: usize,
    pub total_weight: usize,
    pub quality_score: usize,
    pub latency_sum_ms: u64,
    pub latency_count: usize,
    /// Per-domain success tracking for autolearn seeding.
    /// Key: normalized domain, Value: number of successful probes for that domain.
    pub domain_successes: std::collections::BTreeMap<String, usize>,
    /// Per-domain total probe count for autolearn seeding.
    pub domain_totals: std::collections::BTreeMap<String, usize>,
}

impl CandidateScore {
    pub fn add(&mut self, sample: ProbeSample) {
        if let Some(ref domain) = sample.domain {
            *self.domain_totals.entry(domain.clone()).or_default() += 1;
            if sample.success {
                *self.domain_successes.entry(domain.clone()).or_default() += 1;
            }
        }
        self.results.push(sample.result);
        self.total_targets += 1;
        self.total_weight += sample.weight;
        self.quality_score += sample.quality * sample.weight;
        if sample.success {
            self.succeeded_targets += 1;
            self.weighted_success_score += sample.weight;
            self.latency_sum_ms += sample.latency_ms;
            self.latency_count += 1;
        }
    }

    pub fn average_latency_ms(&self) -> Option<u64> {
        (self.latency_count > 0).then(|| self.latency_sum_ms / self.latency_count as u64)
    }

    pub fn is_full_success(&self) -> bool {
        self.total_targets > 0 && self.succeeded_targets == self.total_targets
    }

    /// Build per-domain outcome list. A domain is considered successful if all
    /// of its probes (HTTP + HTTPS) passed.
    pub fn domain_outcomes(&self) -> Vec<StrategyProbeDomainOutcome> {
        self.domain_totals
            .iter()
            .map(|(domain, &total)| {
                let successes = self.domain_successes.get(domain).copied().unwrap_or(0);
                StrategyProbeDomainOutcome { domain: domain.clone(), succeeded: successes == total && total > 0 }
            })
            .collect()
    }
}

pub struct ProbeSample {
    pub result: ProbeResult,
    pub success: bool,
    pub weight: usize,
    pub quality: usize,
    pub latency_ms: u64,
    /// The domain this sample was probed against, for per-domain outcome tracking.
    pub domain: Option<String>,
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
        },
        results: score.results,
        cancelled: false,
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
        },
        results: Vec::new(),
        cancelled: false,
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
        },
        results: Vec::new(),
        cancelled: false,
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
    }
}

pub fn candidate_proxy_config_json(spec: &StrategyCandidateSpec) -> Option<String> {
    serde_json::to_string(&ProxyConfigPayload::Ui {
        strategy_preset: None,
        config: spec.config.clone(),
        runtime_context: None,
        log_context: None,
        session_overrides: None,
    })
    .ok()
}

pub fn candidate_notes(spec: &StrategyCandidateSpec, extra_notes: &[&str]) -> Vec<String> {
    spec.notes.iter().copied().chain(extra_notes.iter().copied()).map(str::to_string).collect()
}

pub fn winning_candidate_index(candidates: &[StrategyProbeCandidateSummary]) -> Option<usize> {
    candidates
        .iter()
        .enumerate()
        .filter(|(_, candidate)| !candidate.skipped && candidate.outcome != "not_applicable")
        .max_by_key(|(index, candidate)| {
            (
                candidate.weighted_success_score,
                candidate.quality_score,
                std::cmp::Reverse(candidate.average_latency_ms.unwrap_or(u64::MAX)),
                std::cmp::Reverse(*index),
            )
        })
        .map(|(index, _)| index)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_proxy_config::ProxyUiConfig;

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
        let candidates =
            vec![summary_with("a", 3, 4, 5, false, Some(200)), summary_with("b", 3, 4, 5, false, Some(100))];

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
        });

        assert!(score.is_full_success());
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

        let execution =
            not_applicable_candidate_execution(&spec, 4, 3, "No baseline HTTPS target exposed ECH capability");

        assert_eq!(execution.summary.outcome, "not_applicable");
        assert_eq!(execution.summary.rationale, "No baseline HTTPS target exposed ECH capability");
        assert_eq!(execution.summary.total_targets, 4);
        assert_eq!(execution.summary.total_weight, 12);
        assert!(execution
            .summary
            .notes
            .iter()
            .any(|note| note.contains("Runs only when the baseline proves an ECH-capable HTTPS path")));
        assert!(execution.summary.notes.iter().any(|note| note == "No baseline HTTPS target exposed ECH capability"));
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
            emitter_tier: crate::types::StrategyEmitterTier::NonRootProduction,
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
}
