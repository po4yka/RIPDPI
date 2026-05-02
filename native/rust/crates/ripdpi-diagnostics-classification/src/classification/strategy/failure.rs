use ripdpi_failure_classifier::{classify_quic_probe, ClassifiedFailure, FailureAction, FailureClass, FailureStage};

use crate::types::{
    ObservationKind, ProbeDetail, ProbeObservation, ProbeResult, StrategyProbeProtocol, TransportFailureKind,
};

pub fn strategy_probe_failure_priority(class: FailureClass) -> usize {
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

pub fn classify_strategy_probe_observation(observation: &ProbeObservation) -> Option<ClassifiedFailure> {
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

pub fn strategy_probe_observation_weight(observation: &ProbeObservation) -> usize {
    match observation.strategy.as_ref().map(|value| value.protocol.clone()) {
        Some(StrategyProbeProtocol::Https | StrategyProbeProtocol::Quic) => 2,
        _ => 1,
    }
}

pub fn classify_strategy_probe_baseline_observations(observations: &[ProbeObservation]) -> Option<ClassifiedFailure> {
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

pub fn classified_failure_probe_result(target: &str, failure: &ClassifiedFailure) -> ProbeResult {
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
