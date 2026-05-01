use crate::types::{ObservationKind, ProbeObservation, ProbeResult, ServiceObservationFact};

use super::common::{base_observation, detail_value, endpoint_status, http_status, quic_status, transport_failure};

const PROBE_TYPE: &str = "service_reachability";

pub(crate) fn build_observation(result: &ProbeResult) -> Option<ProbeObservation> {
    (result.probe_type == PROBE_TYPE).then(|| build_service_observation(result))
}

pub(crate) fn build_service_observation(result: &ProbeResult) -> ProbeObservation {
    let mut observation = base_observation(result, ObservationKind::Service);
    observation.service = Some(ServiceObservationFact {
        service: detail_value(result, "service").unwrap_or(result.target.as_str()).to_string(),
        bootstrap_status: http_status(detail_value(result, "bootstrapStatus")),
        media_status: http_status(detail_value(result, "mediaStatus")),
        endpoint_status: endpoint_status(detail_value(result, "gatewayStatus")),
        endpoint_failure: transport_failure(detail_value(result, "gatewayError").unwrap_or("none")),
        quic_status: quic_status(detail_value(result, "quicStatus").unwrap_or("not_run")),
        quic_failure: transport_failure(detail_value(result, "quicError").unwrap_or("none")),
    });
    observation
}
