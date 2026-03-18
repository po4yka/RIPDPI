use std::collections::BTreeMap;

use ripdpi_failure_classifier::{classify_quic_probe, ClassifiedFailure, FailureAction, FailureClass, FailureStage};

use crate::types::{ProbeDetail, ProbeResult};
use crate::util::stable_probe_hash;
use crate::candidates::StrategyCandidateSpec;

// --- Functions ---

pub(crate) fn failure_detail_value<'a>(result: &'a ProbeResult, key: &str) -> Option<&'a str> {
    result.details.iter().find_map(|detail| (detail.key == key).then_some(detail.value.as_str()))
}

pub(crate) fn classify_transport_failure_text(text: &str, stage: FailureStage) -> Option<ClassifiedFailure> {
    let normalized = text.trim().to_ascii_lowercase();
    if normalized.is_empty() || normalized == "none" {
        return None;
    }
    if normalized.contains("alert") {
        return Some(ClassifiedFailure::new(
            FailureClass::TlsAlert,
            stage,
            FailureAction::RetryWithMatchingGroup,
            text,
        ));
    }
    if normalized.contains("reset")
        || normalized.contains("broken pipe")
        || normalized.contains("aborted")
        || normalized.contains("unexpected eof")
    {
        return Some(ClassifiedFailure::new(
            FailureClass::TcpReset,
            stage,
            FailureAction::RetryWithMatchingGroup,
            text,
        ));
    }
    if normalized.contains("timed out") || normalized.contains("timeout") || normalized.contains("would block") {
        return Some(ClassifiedFailure::new(
            FailureClass::SilentDrop,
            stage,
            FailureAction::RetryWithMatchingGroup,
            text,
        ));
    }
    None
}

pub(crate) fn classify_strategy_probe_result(result: &ProbeResult) -> Option<ClassifiedFailure> {
    match (result.probe_type.as_str(), result.outcome.as_str()) {
        ("strategy_http", "http_blockpage") => Some(ClassifiedFailure::new(
            FailureClass::HttpBlockpage,
            FailureStage::HttpResponse,
            FailureAction::RetryWithMatchingGroup,
            "HTTP blockpage observed during baseline candidate",
        )),
        ("strategy_http", "http_unreachable") => failure_detail_value(result, "error")
            .and_then(|value| classify_transport_failure_text(value, FailureStage::FirstResponse)),
        ("strategy_https", "tls_handshake_failed") => {
            let error = failure_detail_value(result, "tlsError").unwrap_or("tls_handshake_failed");
            Some(
                classify_transport_failure_text(error, FailureStage::TlsHandshake).unwrap_or_else(|| {
                    ClassifiedFailure::new(
                        FailureClass::TlsHandshakeFailure,
                        FailureStage::TlsHandshake,
                        FailureAction::RetryWithMatchingGroup,
                        error,
                    )
                }),
            )
        }
        ("strategy_quic", outcome) => {
            let error = failure_detail_value(result, "error").filter(|value| *value != "none");
            classify_quic_probe(outcome, error)
        }
        _ => None,
    }
}

pub(crate) fn strategy_probe_failure_weight(result: &ProbeResult) -> usize {
    match result.probe_type.as_str() {
        "strategy_https" | "strategy_quic" => 2,
        _ => 1,
    }
}

pub(crate) fn strategy_probe_failure_priority(class: FailureClass) -> usize {
    match class {
        FailureClass::HttpBlockpage => 5,
        FailureClass::TcpReset => 4,
        FailureClass::SilentDrop => 3,
        FailureClass::TlsAlert => 2,
        FailureClass::TlsHandshakeFailure => 1,
        FailureClass::QuicBreakage => 1,
        _ => 0,
    }
}

pub(crate) fn classify_strategy_probe_baseline_results(results: &[ProbeResult]) -> Option<ClassifiedFailure> {
    let mut aggregated = Vec::<(FailureClass, usize, ClassifiedFailure)>::new();
    for result in results {
        let Some(failure) = classify_strategy_probe_result(result) else {
            continue;
        };
        let weight = strategy_probe_failure_weight(result);
        if let Some(entry) = aggregated.iter_mut().find(|entry| entry.0 == failure.class) {
            entry.1 += weight;
        } else {
            aggregated.push((failure.class, weight, failure));
        }
    }
    aggregated
        .into_iter()
        .max_by_key(|(class, weight, _)| (*weight, strategy_probe_failure_priority(*class)))
        .map(|(_, _, failure)| failure)
}

pub(crate) fn classified_failure_probe_result(target: &str, failure: &ClassifiedFailure) -> ProbeResult {
    let evidence =
        std::iter::once(failure.evidence.summary.as_str())
            .chain(failure.evidence.tags.iter().map(String::as_str))
            .collect::<Vec<_>>()
            .join(" | ");
    ProbeResult {
        probe_type: "strategy_failure_classification".to_string(),
        target: target.to_string(),
        outcome: failure.class.as_str().to_string(),
        details: vec![
            ProbeDetail { key: "failureClass".to_string(), value: failure.class.as_str().to_string() },
            ProbeDetail { key: "failureStage".to_string(), value: failure.stage.as_str().to_string() },
            ProbeDetail { key: "failureEvidence".to_string(), value: evidence },
            ProbeDetail { key: "fallbackDecision".to_string(), value: failure.action.as_str().to_string() },
        ],
    }
}

pub(crate) fn reorder_tcp_candidates_for_failure(
    candidates: &[StrategyCandidateSpec],
    failure_class: Option<FailureClass>,
) -> Vec<StrategyCandidateSpec> {
    let preferred_ids: &[&str] = match failure_class {
        Some(FailureClass::HttpBlockpage) => &["baseline_current", "parser_only", "parser_unixeol", "split_host"],
        Some(FailureClass::TcpReset) => &["baseline_current", "split_host", "tlsrec_split_host", "tlsrec_hostfake_split"],
        Some(FailureClass::SilentDrop) => &["baseline_current", "tlsrec_fake_rich", "tlsrec_hostfake", "tlsrec_hostfake_split"],
        Some(FailureClass::TlsAlert) => &["baseline_current", "split_host", "tlsrec_split_host", "tlsrec_hostfake"],
        _ => &[],
    };
    let mut ordered = Vec::with_capacity(candidates.len());
    for id in preferred_ids {
        if let Some(candidate) = candidates.iter().find(|candidate| candidate.id == *id) {
            ordered.push(candidate.clone());
        }
    }
    for candidate in candidates {
        if !ordered.iter().any(|existing| existing.id == candidate.id) {
            ordered.push(candidate.clone());
        }
    }
    ordered
}

pub(crate) fn filter_quic_candidates_for_failure(
    candidates: Vec<StrategyCandidateSpec>,
    failure_class: Option<FailureClass>,
) -> Vec<StrategyCandidateSpec> {
    if !matches!(failure_class, Some(FailureClass::QuicBreakage)) {
        return candidates;
    }
    let allowed = ["quic_disabled", "quic_compat_burst", "quic_realistic_burst"];
    candidates
        .into_iter()
        .filter(|candidate| allowed.contains(&candidate.id))
        .collect()
}

pub(crate) fn interleave_candidate_families(mut candidates: Vec<StrategyCandidateSpec>, seed: u64) -> Vec<StrategyCandidateSpec> {
    let mut families = BTreeMap::<&'static str, Vec<StrategyCandidateSpec>>::new();
    for candidate in candidates.drain(..) {
        families.entry(candidate.family).or_default().push(candidate);
    }
    let mut family_order = families.keys().copied().collect::<Vec<_>>();
    family_order.sort_by_key(|family| stable_probe_hash(seed, family));
    for family in &family_order {
        if let Some(entries) = families.get_mut(family) {
            entries.sort_by_key(|candidate| stable_probe_hash(seed, candidate.id));
        }
    }
    let mut ordered = Vec::new();
    loop {
        let mut progressed = false;
        for family in &family_order {
            let Some(entries) = families.get_mut(family) else {
                continue;
            };
            if entries.is_empty() {
                continue;
            }
            ordered.push(entries.remove(0));
            progressed = true;
        }
        if !progressed {
            break;
        }
    }
    ordered
}

pub(crate) fn next_candidate_index(candidates: &[StrategyCandidateSpec], blocked_family: Option<&str>) -> usize {
    blocked_family
        .and_then(|blocked| candidates.iter().position(|candidate| candidate.family != blocked))
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_transport_failure_text_alert() {
        let result = classify_transport_failure_text("received fatal alert: handshake_failure", FailureStage::TlsHandshake);
        assert!(result.is_some());
        assert_eq!(result.unwrap().class, FailureClass::TlsAlert);
    }

    #[test]
    fn classify_transport_failure_text_reset() {
        let result = classify_transport_failure_text("connection reset by peer", FailureStage::FirstResponse);
        assert!(result.is_some());
        assert_eq!(result.unwrap().class, FailureClass::TcpReset);
    }

    #[test]
    fn classify_transport_failure_text_timeout() {
        let result = classify_transport_failure_text("operation timed out", FailureStage::FirstResponse);
        assert!(result.is_some());
        assert_eq!(result.unwrap().class, FailureClass::SilentDrop);
    }

    #[test]
    fn classify_transport_failure_text_empty_returns_none() {
        assert!(classify_transport_failure_text("", FailureStage::FirstResponse).is_none());
    }

    #[test]
    fn classify_transport_failure_text_none_returns_none() {
        assert!(classify_transport_failure_text("none", FailureStage::FirstResponse).is_none());
    }

    #[test]
    fn classify_transport_failure_text_unknown_returns_none() {
        assert!(classify_transport_failure_text("some random error", FailureStage::FirstResponse).is_none());
    }

    #[test]
    fn strategy_probe_failure_priority_ordering() {
        assert!(strategy_probe_failure_priority(FailureClass::HttpBlockpage) > strategy_probe_failure_priority(FailureClass::TcpReset));
        assert!(strategy_probe_failure_priority(FailureClass::TcpReset) > strategy_probe_failure_priority(FailureClass::SilentDrop));
        assert!(strategy_probe_failure_priority(FailureClass::SilentDrop) > strategy_probe_failure_priority(FailureClass::TlsAlert));
    }

    #[test]
    fn strategy_probe_failure_weight_https_is_2() {
        let result = ProbeResult {
            probe_type: "strategy_https".to_string(),
            target: "test".to_string(),
            outcome: "tls_ok".to_string(),
            details: vec![],
        };
        assert_eq!(strategy_probe_failure_weight(&result), 2);
    }

    #[test]
    fn strategy_probe_failure_weight_http_is_1() {
        let result = ProbeResult {
            probe_type: "strategy_http".to_string(),
            target: "test".to_string(),
            outcome: "http_ok".to_string(),
            details: vec![],
        };
        assert_eq!(strategy_probe_failure_weight(&result), 1);
    }
}
