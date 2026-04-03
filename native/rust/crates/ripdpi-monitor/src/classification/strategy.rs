use std::collections::BTreeMap;

use ripdpi_failure_classifier::{classify_quic_probe, ClassifiedFailure, FailureAction, FailureClass, FailureStage};

use crate::candidates::StrategyCandidateSpec;
use crate::types::{
    ObservationKind, ProbeDetail, ProbeObservation, ProbeResult, StrategyProbeProtocol, TransportFailureKind,
};
use crate::util::stable_probe_hash;

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

pub(crate) fn classify_strategy_probe_observation(observation: &ProbeObservation) -> Option<ClassifiedFailure> {
    let strategy = observation.strategy.as_ref()?;
    if observation.kind != ObservationKind::Strategy {
        return None;
    }
    match strategy.protocol {
        StrategyProbeProtocol::Http if observation.evidence.iter().any(|value| value == "http_blockpage") => {
            Some(ClassifiedFailure::new(
                FailureClass::HttpBlockpage,
                FailureStage::HttpResponse,
                FailureAction::RetryWithMatchingGroup,
                "HTTP blockpage observed during baseline candidate",
            ))
        }
        StrategyProbeProtocol::Http => {
            classify_failure_from_transport(strategy.transport_failure.clone(), FailureStage::FirstResponse)
        }
        StrategyProbeProtocol::Https => Some(
            classify_failure_from_transport(strategy.transport_failure.clone(), FailureStage::TlsHandshake)
                .unwrap_or_else(|| {
                    ClassifiedFailure::new(
                        FailureClass::TlsHandshakeFailure,
                        FailureStage::TlsHandshake,
                        FailureAction::RetryWithMatchingGroup,
                        "tls_handshake_failed",
                    )
                }),
        ),
        StrategyProbeProtocol::Quic => classify_quic_probe(
            observation.evidence.first().map_or("quic_error", String::as_str),
            quic_error_from_failure(strategy.transport_failure.clone()),
        ),
        _ => None,
    }
}

pub(crate) fn strategy_probe_observation_weight(observation: &ProbeObservation) -> usize {
    match observation.strategy.as_ref().map(|value| value.protocol.clone()) {
        Some(StrategyProbeProtocol::Https | StrategyProbeProtocol::Quic) => 2,
        _ => 1,
    }
}

pub(crate) fn classify_strategy_probe_baseline_observations(
    observations: &[ProbeObservation],
) -> Option<ClassifiedFailure> {
    let mut aggregated = Vec::<(FailureClass, usize, ClassifiedFailure)>::new();
    for observation in observations {
        let Some(failure) = classify_strategy_probe_observation(observation) else {
            continue;
        };
        let weight = strategy_probe_observation_weight(observation);
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

fn classify_failure_from_transport(failure: TransportFailureKind, stage: FailureStage) -> Option<ClassifiedFailure> {
    let evidence = match failure {
        TransportFailureKind::Alert => "alert",
        TransportFailureKind::Reset => "reset",
        TransportFailureKind::Close => "close",
        TransportFailureKind::Timeout => "timeout",
        TransportFailureKind::Certificate => "certificate",
        TransportFailureKind::Other => "other",
        TransportFailureKind::None => return None,
    };
    match failure {
        TransportFailureKind::Alert => {
            Some(ClassifiedFailure::new(FailureClass::TlsAlert, stage, FailureAction::RetryWithMatchingGroup, evidence))
        }
        TransportFailureKind::Reset | TransportFailureKind::Close => {
            Some(ClassifiedFailure::new(FailureClass::TcpReset, stage, FailureAction::RetryWithMatchingGroup, evidence))
        }
        TransportFailureKind::Timeout => Some(ClassifiedFailure::new(
            FailureClass::SilentDrop,
            stage,
            FailureAction::RetryWithMatchingGroup,
            evidence,
        )),
        TransportFailureKind::Certificate | TransportFailureKind::Other => Some(ClassifiedFailure::new(
            FailureClass::TlsHandshakeFailure,
            stage,
            FailureAction::RetryWithMatchingGroup,
            evidence,
        )),
        TransportFailureKind::None => None,
    }
}

fn quic_error_from_failure(failure: TransportFailureKind) -> Option<&'static str> {
    match failure {
        TransportFailureKind::Timeout => Some("timeout"),
        TransportFailureKind::Reset => Some("reset"),
        TransportFailureKind::Close => Some("close"),
        TransportFailureKind::Alert => Some("alert"),
        TransportFailureKind::Certificate => Some("certificate"),
        TransportFailureKind::Other => Some("error"),
        TransportFailureKind::None => None,
    }
}

pub(crate) fn classified_failure_probe_result(target: &str, failure: &ClassifiedFailure) -> ProbeResult {
    let evidence = std::iter::once(failure.evidence.summary.as_str())
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
    fake_ttl_available: bool,
) -> Vec<StrategyCandidateSpec> {
    let preferred_ids: &[&str] = match failure_class {
        Some(FailureClass::HttpBlockpage) => &["baseline_current", "parser_only", "parser_unixeol", "split_host"],
        Some(FailureClass::TcpReset) => {
            &["baseline_current", "split_host", "tlsrec_split_host", "tlsrec_hostfake_split"]
        }
        Some(FailureClass::SilentDrop) if !fake_ttl_available => {
            &["baseline_current", "tlsrec_split_host", "tlsrec_hostfake_split", "split_host"]
        }
        Some(FailureClass::SilentDrop) => {
            &["baseline_current", "tlsrec_fake_rich", "tlsrec_hostfake", "tlsrec_hostfake_split"]
        }
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
    candidates.into_iter().filter(|candidate| allowed.contains(&candidate.id)).collect()
}

pub(crate) fn interleave_candidate_families(
    mut candidates: Vec<StrategyCandidateSpec>,
    seed: u64,
) -> Vec<StrategyCandidateSpec> {
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
    blocked_family.and_then(|blocked| candidates.iter().position(|candidate| candidate.family != blocked)).unwrap_or(0)
}
