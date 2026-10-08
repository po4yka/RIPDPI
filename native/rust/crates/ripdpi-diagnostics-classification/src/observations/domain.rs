use crate::types::{DomainObservationFact, ObservationKind, ProbeObservation, ProbeResult, TransportFailureKind};

use super::common::{base_observation, detail_value, http_status, tls_status, transport_failure};

const PROBE_TYPE: &str = "domain_reachability";

pub(crate) fn build_observation(result: &ProbeResult) -> Option<ProbeObservation> {
    (result.probe_type == PROBE_TYPE).then(|| build_domain_observation(result))
}

pub(crate) fn build_domain_observation(result: &ProbeResult) -> ProbeObservation {
    let mut observation = base_observation(result, ObservationKind::Domain);
    observation.domain = Some(DomainObservationFact {
        host: result.target.clone(),
        http_status: http_status(detail_value(result, "httpStatus")),
        tls13_status: tls_status(detail_value(result, "tls13Status")),
        tls12_status: tls_status(detail_value(result, "tls12Status")),
        tls_ech_status: tls_status(detail_value(result, "tlsEchStatus")),
        tls_ech_version: detail_value(result, "tlsEchVersion").filter(|value| *value != "unknown").map(str::to_string),
        tls_ech_error: detail_value(result, "tlsEchError").filter(|value| *value != "none").map(str::to_string),
        tls_ech_resolution_detail: detail_value(result, "tlsEchResolutionDetail")
            .filter(|value| *value != "none")
            .map(str::to_string),
        transport_failure: tls_transport_failure(result),
        tls_error: tls_error(result).map(str::to_string),
        certificate_anomaly: result.outcome == "tls_cert_invalid"
            || detail_value(result, "tlsSignal") == Some("tls_cert_invalid"),
        is_control: detail_value(result, "isControl").is_some_and(|value| value == "true"),
        h3_advertised: detail_value(result, "h3Advertised") == Some("true"),
        alt_svc: detail_value(result, "altSvc").filter(|v| *v != "none").map(str::to_string),
    });
    observation
}

fn tls_transport_failure(result: &ProbeResult) -> TransportFailureKind {
    let Some(error) = tls_error(result) else {
        return TransportFailureKind::None;
    };
    // The domain fact has no stage field. Keep pre-TLS errors in raw details,
    // but do not let consumers interpret a connect failure as a TLS failure.
    if detail_value(result, "tlsFailureStage") != Some("tls_handshake") {
        return TransportFailureKind::Other;
    }
    transport_failure(error)
}

fn tls_error(result: &ProbeResult) -> Option<&str> {
    if detail_value(result, "tlsStatus") == Some("tls_ok") || result.outcome == "tls_ok" {
        return None;
    }
    detail_value(result, "tlsError").filter(|value| *value != "none")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::ProbeDetail;

    #[test]
    fn domain_timeout_fact_requires_handshake_stage() {
        for (stage, expected) in [
            ("tcp_connect", TransportFailureKind::Other),
            ("dns_resolution", TransportFailureKind::Other),
            ("socks5_negotiation", TransportFailureKind::Other),
            ("stream_setup", TransportFailureKind::Other),
            ("none", TransportFailureKind::Other),
            ("tls_handshake", TransportFailureKind::Timeout),
        ] {
            let result = ProbeResult {
                probe_type: "domain_reachability".into(),
                target: "example.com".into(),
                outcome: "unreachable".into(),
                details: vec![
                    ProbeDetail { key: "tlsError".into(), value: "connection timed out".into() },
                    ProbeDetail { key: "tlsFailureStage".into(), value: stage.into() },
                ],
            };
            let domain = build_domain_observation(&result).domain.unwrap();
            assert_eq!(domain.transport_failure, expected, "stage={stage}");
            assert_eq!(domain.tls_error.as_deref(), Some("connection timed out"));
        }
    }

    #[test]
    fn successful_tls_does_not_inherit_another_profile_error() {
        let result = ProbeResult {
            probe_type: "domain_reachability".into(),
            target: "example.com".into(),
            outcome: "tls_ok".into(),
            details: vec![
                ProbeDetail { key: "tlsStatus".into(), value: "tls_ok".into() },
                ProbeDetail { key: "tlsError".into(), value: "none".into() },
                ProbeDetail { key: "tls12Error".into(), value: "connection timed out".into() },
            ],
        };
        let domain = build_domain_observation(&result).domain.unwrap();
        assert_eq!(domain.transport_failure, TransportFailureKind::None);
        assert_eq!(domain.tls_error, None);
    }
}
