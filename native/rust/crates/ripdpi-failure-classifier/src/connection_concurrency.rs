use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

pub const CONNECTION_CONCURRENCY_CLASSIFIER_VERSION: &str = "connection_concurrency_v1";

/// Canonical TLS profile ordering. Single source of truth for both the
/// probe-matrix completeness check ([`matrix_is_complete`]) and the
/// recommendation tie-breaks ([`recommend_profile`]).
const PROFILE_ORDER: &[&str] =
    &["chrome_stable", "chrome_desktop_stable", "firefox_stable", "firefox_ech_stable", "safari_stable", "edge_stable"];

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionConcurrencyEvidence {
    pub requested_parallelism: u16,
    pub observed_peak_parallelism: u16,
    pub launch_spread_ms: u32,
    pub burst_window_ms: u32,
    pub tls_profile_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FailureEvidenceContext {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub connection_concurrency: Option<ConnectionConcurrencyEvidence>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConnectionConcurrencyCellStatus {
    Healthy,
    Blocked,
    Mixed,
    Contaminated,
    Skipped,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionConcurrencyCell {
    pub target_cohort: String,
    pub target_id: String,
    pub tls_profile_id: String,
    pub requested_parallelism: u16,
    pub observed_peak_parallelism: u16,
    pub launch_spread_ms: u32,
    pub burst_window_ms: u32,
    pub success_count: u16,
    pub failure_count: u16,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub block_signals: Vec<String>,
    pub status: ConnectionConcurrencyCellStatus,
    #[serde(default)]
    pub contaminated: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub skip_reason: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConnectionConcurrencyVerdict {
    NoInteraction,
    FingerprintOnly,
    ConcurrencyOnly,
    ConjunctionSuspected,
    ConjunctionConfirmed,
    Inconclusive,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionConcurrencyAssessment {
    pub classifier_version: String,
    pub verdict: ConnectionConcurrencyVerdict,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub recommended_tls_profile_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub recommended_max_parallel_handshakes: Option<u16>,
    pub planned_cells: u16,
    pub clean_cells: u16,
    pub affected_target_count: u16,
    #[serde(default)]
    pub healthy_caps_by_profile: BTreeMap<String, u16>,
    #[serde(default)]
    pub warnings: Vec<String>,
}

pub fn classify_connection_concurrency_matrix(
    cells: &[ConnectionConcurrencyCell],
    current_profile: Option<&str>,
) -> ConnectionConcurrencyAssessment {
    let clean: Vec<_> = cells
        .iter()
        .filter(|cell| {
            !cell.contaminated
                && !matches!(
                    cell.status,
                    ConnectionConcurrencyCellStatus::Contaminated | ConnectionConcurrencyCellStatus::Skipped
                )
        })
        .collect();
    let mut profiles: BTreeMap<&str, Vec<&ConnectionConcurrencyCell>> = BTreeMap::new();
    for cell in &clean {
        profiles.entry(cell.tls_profile_id.as_str()).or_default().push(cell);
    }

    let mut affected_targets = BTreeSet::new();
    let mut affected_patterns = BTreeSet::new();
    let mut fingerprint_only = false;
    let mut any_healthy = false;
    for profile_cells in profiles.values() {
        any_healthy |= profile_cells.iter().any(|cell| cell.status == ConnectionConcurrencyCellStatus::Healthy);
        for baseline in profile_cells
            .iter()
            .filter(|cell| cell.requested_parallelism == 1 && cell.status == ConnectionConcurrencyCellStatus::Healthy)
        {
            for blocked in profile_cells.iter().filter(|cell| {
                cell.target_id == baseline.target_id
                    && cell.requested_parallelism > 1
                    && cell.status == ConnectionConcurrencyCellStatus::Blocked
            }) {
                affected_targets.insert(blocked.target_id.as_str());
                affected_patterns.insert((
                    blocked.target_id.as_str(),
                    blocked.tls_profile_id.as_str(),
                    blocked.requested_parallelism,
                ));
            }
        }
    }
    for blocked in clean
        .iter()
        .filter(|cell| cell.requested_parallelism == 1 && cell.status == ConnectionConcurrencyCellStatus::Blocked)
    {
        fingerprint_only |= clean.iter().any(|control| {
            control.target_id == blocked.target_id
                && control.tls_profile_id != blocked.tls_profile_id
                && control.requested_parallelism == 1
                && control.status == ConnectionConcurrencyCellStatus::Healthy
        });
    }

    let mut controlled_patterns: BTreeMap<(&str, u16), BTreeSet<&str>> = BTreeMap::new();
    for (target, affected_profile, level) in &affected_patterns {
        let has_healthy_control = clean.iter().any(|cell| {
            cell.target_id == *target
                && cell.tls_profile_id != *affected_profile
                && cell.requested_parallelism == *level
                && cell.status == ConnectionConcurrencyCellStatus::Healthy
        });
        if has_healthy_control {
            controlled_patterns.entry((*affected_profile, *level)).or_default().insert(*target);
        }
    }
    let concurrency_only = clean.iter().any(|first| {
        first.requested_parallelism > 1
            && first.status == ConnectionConcurrencyCellStatus::Blocked
            && clean.iter().any(|second| {
                second.target_id == first.target_id
                    && second.tls_profile_id != first.tls_profile_id
                    && second.requested_parallelism == first.requested_parallelism
                    && second.status == ConnectionConcurrencyCellStatus::Blocked
                    && has_healthy_baseline(&clean, first)
                    && has_healthy_baseline(&clean, second)
            })
    });
    let matrix_complete = matrix_is_complete(&clean, cells);
    let all_clean_healthy = clean.len() == cells.len()
        && !clean.is_empty()
        && matrix_complete
        && clean.iter().all(|cell| cell.status == ConnectionConcurrencyCellStatus::Healthy);
    let replicated_pattern = controlled_patterns.values().any(|targets| targets.len() >= 2);
    let single_clean_pattern = controlled_patterns.values().any(|targets| !targets.is_empty());

    let verdict = if replicated_pattern {
        ConnectionConcurrencyVerdict::ConjunctionConfirmed
    } else if single_clean_pattern {
        ConnectionConcurrencyVerdict::ConjunctionSuspected
    } else if fingerprint_only {
        ConnectionConcurrencyVerdict::FingerprintOnly
    } else if concurrency_only {
        ConnectionConcurrencyVerdict::ConcurrencyOnly
    } else if all_clean_healthy {
        ConnectionConcurrencyVerdict::NoInteraction
    } else {
        ConnectionConcurrencyVerdict::Inconclusive
    };

    let healthy_caps_by_profile = cohort_healthy_caps(cells);
    let recommendation = recommend_profile(&healthy_caps_by_profile, current_profile);
    let mut warnings = Vec::new();
    if clean.len() != cells.len() {
        warnings.push("Contaminated or skipped cells were excluded from classification.".to_string());
    }
    if !any_healthy {
        warnings.push("No healthy profile/concurrency cell was available for a recommendation.".to_string());
    }

    ConnectionConcurrencyAssessment {
        classifier_version: CONNECTION_CONCURRENCY_CLASSIFIER_VERSION.to_string(),
        verdict,
        recommended_tls_profile_id: recommendation.map(|(profile, _)| profile.to_string()),
        recommended_max_parallel_handshakes: recommendation.map(|(_, level)| level),
        planned_cells: u16::try_from(cells.len()).unwrap_or(u16::MAX),
        clean_cells: u16::try_from(clean.len()).unwrap_or(u16::MAX),
        affected_target_count: u16::try_from(affected_targets.len()).unwrap_or(u16::MAX),
        healthy_caps_by_profile,
        warnings,
    }
}

fn has_healthy_baseline(clean: &[&ConnectionConcurrencyCell], cell: &ConnectionConcurrencyCell) -> bool {
    clean.iter().any(|baseline| {
        baseline.target_id == cell.target_id
            && baseline.tls_profile_id == cell.tls_profile_id
            && baseline.requested_parallelism == 1
            && baseline.status == ConnectionConcurrencyCellStatus::Healthy
    })
}

fn matrix_is_complete(clean: &[&ConnectionConcurrencyCell], cells: &[ConnectionConcurrencyCell]) -> bool {
    if clean.len() != cells.len() {
        return false;
    }
    let targets = clean.iter().map(|cell| cell.target_id.as_str()).collect::<BTreeSet<_>>();
    let profiles = clean.iter().map(|cell| cell.tls_profile_id.as_str()).collect::<BTreeSet<_>>();
    let levels = clean.iter().map(|cell| cell.requested_parallelism).collect::<BTreeSet<_>>();
    let canonical_profile_count = PROFILE_ORDER.len();
    profiles.len() == canonical_profile_count
        && targets.iter().all(|target| {
            profiles.iter().all(|profile| {
                levels.iter().all(|level| {
                    clean.iter().any(|cell| {
                        cell.target_id == *target
                            && cell.tls_profile_id == *profile
                            && cell.requested_parallelism == *level
                    })
                })
            })
        })
}

fn cohort_healthy_caps(cells: &[ConnectionConcurrencyCell]) -> BTreeMap<String, u16> {
    let targets = cells.iter().map(|cell| cell.target_id.as_str()).collect::<BTreeSet<_>>();
    let profiles = cells.iter().map(|cell| cell.tls_profile_id.as_str()).collect::<BTreeSet<_>>();
    profiles
        .into_iter()
        .filter_map(|profile| {
            cells
                .iter()
                .filter(|cell| cell.tls_profile_id == profile)
                .map(|cell| cell.requested_parallelism)
                .collect::<BTreeSet<_>>()
                .into_iter()
                .filter(|level| {
                    targets.iter().all(|target| {
                        cells.iter().any(|cell| {
                            cell.target_id == *target
                                && cell.tls_profile_id == profile
                                && cell.requested_parallelism == *level
                                && !cell.contaminated
                                && cell.status == ConnectionConcurrencyCellStatus::Healthy
                        })
                    })
                })
                .max()
                .map(|cap| (profile.to_string(), cap.min(8)))
        })
        .collect()
}

fn recommend_profile<'a>(
    healthy_caps: &'a BTreeMap<String, u16>,
    current_profile: Option<&str>,
) -> Option<(&'a str, u16)> {
    healthy_caps.iter().map(|(profile, level)| (profile.as_str(), *level)).max_by_key(|(profile, level)| {
        let current = u8::from(current_profile == Some(*profile));
        let catalog_rank = PROFILE_ORDER
            .iter()
            .position(|candidate| candidate == profile)
            .map_or(0, |position| PROFILE_ORDER.len().saturating_sub(position));
        (*level, current, catalog_rank)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cell(
        profile: &str,
        target: &str,
        parallelism: u16,
        status: ConnectionConcurrencyCellStatus,
    ) -> ConnectionConcurrencyCell {
        ConnectionConcurrencyCell {
            target_cohort: "global-platform-control-v1".to_string(),
            target_id: target.to_string(),
            tls_profile_id: profile.to_string(),
            requested_parallelism: parallelism,
            observed_peak_parallelism: parallelism,
            launch_spread_ms: 100,
            burst_window_ms: 250,
            success_count: if status == ConnectionConcurrencyCellStatus::Healthy { parallelism } else { 0 },
            failure_count: if status == ConnectionConcurrencyCellStatus::Blocked { parallelism } else { 0 },
            block_signals: if status == ConnectionConcurrencyCellStatus::Blocked {
                vec!["connection_freeze".to_string()]
            } else {
                Vec::new()
            },
            status,
            contaminated: false,
            skip_reason: None,
        }
    }

    #[test]
    fn conjunction_requires_profile_specific_parallelism_failure_on_two_targets() {
        let cells = vec![
            cell("chrome_stable", "target-a", 1, ConnectionConcurrencyCellStatus::Healthy),
            cell("chrome_stable", "target-a", 4, ConnectionConcurrencyCellStatus::Blocked),
            cell("chrome_stable", "target-b", 1, ConnectionConcurrencyCellStatus::Healthy),
            cell("chrome_stable", "target-b", 4, ConnectionConcurrencyCellStatus::Blocked),
            cell("firefox_stable", "target-a", 1, ConnectionConcurrencyCellStatus::Healthy),
            cell("firefox_stable", "target-a", 4, ConnectionConcurrencyCellStatus::Healthy),
            cell("firefox_stable", "target-b", 1, ConnectionConcurrencyCellStatus::Healthy),
            cell("firefox_stable", "target-b", 4, ConnectionConcurrencyCellStatus::Healthy),
        ];

        let assessment = classify_connection_concurrency_matrix(&cells, Some("firefox_stable"));

        assert_eq!(assessment.verdict, ConnectionConcurrencyVerdict::ConjunctionConfirmed);
        assert_eq!(assessment.recommended_tls_profile_id.as_deref(), Some("firefox_stable"));
        assert_eq!(assessment.recommended_max_parallel_handshakes, Some(4));
    }

    #[test]
    fn neither_axis_alone_is_a_conjunction() {
        let fingerprint_only = vec![
            cell("chrome_stable", "target-a", 1, ConnectionConcurrencyCellStatus::Blocked),
            cell("chrome_stable", "target-a", 4, ConnectionConcurrencyCellStatus::Blocked),
            cell("firefox_stable", "target-a", 1, ConnectionConcurrencyCellStatus::Healthy),
            cell("firefox_stable", "target-a", 4, ConnectionConcurrencyCellStatus::Healthy),
        ];
        let concurrency_only = vec![
            cell("chrome_stable", "target-a", 1, ConnectionConcurrencyCellStatus::Healthy),
            cell("chrome_stable", "target-a", 4, ConnectionConcurrencyCellStatus::Blocked),
            cell("firefox_stable", "target-a", 1, ConnectionConcurrencyCellStatus::Healthy),
            cell("firefox_stable", "target-a", 4, ConnectionConcurrencyCellStatus::Blocked),
        ];

        assert_eq!(
            classify_connection_concurrency_matrix(&fingerprint_only, None).verdict,
            ConnectionConcurrencyVerdict::FingerprintOnly,
        );
        assert_eq!(
            classify_connection_concurrency_matrix(&concurrency_only, None).verdict,
            ConnectionConcurrencyVerdict::ConcurrencyOnly,
        );
    }

    #[test]
    fn a_single_clean_target_is_suspected_but_never_confirmed() {
        let cells = vec![
            cell("chrome_stable", "target-a", 1, ConnectionConcurrencyCellStatus::Healthy),
            cell("chrome_stable", "target-a", 4, ConnectionConcurrencyCellStatus::Blocked),
            cell("firefox_stable", "target-a", 4, ConnectionConcurrencyCellStatus::Healthy),
        ];
        assert_eq!(
            classify_connection_concurrency_matrix(&cells, None).verdict,
            ConnectionConcurrencyVerdict::ConjunctionSuspected,
        );
    }

    #[test]
    fn recommendation_cap_must_be_healthy_across_the_target_cohort() {
        let cells = vec![
            cell("chrome_stable", "target-a", 1, ConnectionConcurrencyCellStatus::Healthy),
            cell("chrome_stable", "target-a", 4, ConnectionConcurrencyCellStatus::Blocked),
            cell("chrome_stable", "target-b", 1, ConnectionConcurrencyCellStatus::Healthy),
            cell("chrome_stable", "target-b", 4, ConnectionConcurrencyCellStatus::Blocked),
            cell("chrome_stable", "target-c", 1, ConnectionConcurrencyCellStatus::Healthy),
            cell("chrome_stable", "target-c", 4, ConnectionConcurrencyCellStatus::Healthy),
        ];

        let assessment = classify_connection_concurrency_matrix(&cells, Some("chrome_stable"));

        assert_eq!(assessment.healthy_caps_by_profile.get("chrome_stable"), Some(&1));
        assert_eq!(assessment.recommended_max_parallel_handshakes, Some(1));
    }

    #[test]
    fn all_healthy_is_no_interaction_while_mixed_or_incomplete_is_inconclusive() {
        let healthy = [
            "chrome_stable",
            "chrome_desktop_stable",
            "firefox_stable",
            "firefox_ech_stable",
            "safari_stable",
            "edge_stable",
        ]
        .into_iter()
        .flat_map(|profile| {
            [1, 4].map(|level| cell(profile, "target-a", level, ConnectionConcurrencyCellStatus::Healthy))
        })
        .collect::<Vec<_>>();
        let mixed = vec![cell("chrome_stable", "target-a", 4, ConnectionConcurrencyCellStatus::Mixed)];
        let mut contaminated = cell("chrome_stable", "target-a", 4, ConnectionConcurrencyCellStatus::Blocked);
        contaminated.contaminated = true;
        contaminated.status = ConnectionConcurrencyCellStatus::Contaminated;
        assert_eq!(
            classify_connection_concurrency_matrix(&healthy, None).verdict,
            ConnectionConcurrencyVerdict::NoInteraction,
        );
        assert_eq!(
            classify_connection_concurrency_matrix(&mixed, None).verdict,
            ConnectionConcurrencyVerdict::Inconclusive,
        );
        assert_eq!(
            classify_connection_concurrency_matrix(&[contaminated], None).verdict,
            ConnectionConcurrencyVerdict::Inconclusive,
        );
        let incomplete = vec![
            cell("chrome_stable", "target-a", 1, ConnectionConcurrencyCellStatus::Healthy),
            ConnectionConcurrencyCell {
                status: ConnectionConcurrencyCellStatus::Skipped,
                skip_reason: Some("deadline".to_string()),
                ..cell("chrome_stable", "target-a", 4, ConnectionConcurrencyCellStatus::Healthy)
            },
        ];
        assert_eq!(
            classify_connection_concurrency_matrix(&incomplete, None).verdict,
            ConnectionConcurrencyVerdict::Inconclusive,
        );
    }
}
