use crate::types::{DomainObservationFact, ObservationKind, ProbeObservation, ProbeResult};

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
        transport_failure: transport_failure(tls_failure_text(result)),
        tls_error: tls_error(result).map(str::to_string),
        certificate_anomaly: result.outcome == "tls_cert_invalid"
            || detail_value(result, "tlsSignal") == Some("tls_cert_invalid"),
        is_control: detail_value(result, "isControl").is_some_and(|value| value == "true"),
        h3_advertised: detail_value(result, "h3Advertised") == Some("true"),
        alt_svc: detail_value(result, "altSvc").filter(|v| *v != "none").map(str::to_string),
    });
    observation
}

fn tls_failure_text(result: &ProbeResult) -> &str {
    tls_error(result).unwrap_or("none")
}

fn tls_error(result: &ProbeResult) -> Option<&str> {
    detail_value(result, "tlsError")
        .filter(|value| *value != "none")
        .or_else(|| detail_value(result, "tls13Error").filter(|value| *value != "none"))
        .or_else(|| detail_value(result, "tls12Error").filter(|value| *value != "none"))
}
