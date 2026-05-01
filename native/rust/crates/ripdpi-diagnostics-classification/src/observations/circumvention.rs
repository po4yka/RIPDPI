use crate::types::{CircumventionObservationFact, ObservationKind, ProbeObservation, ProbeResult};

use super::common::{base_observation, detail_value, endpoint_status, http_status, transport_failure};

pub(crate) fn build_circumvention_observation(result: &ProbeResult) -> ProbeObservation {
    let mut observation = base_observation(result, ObservationKind::Circumvention);
    observation.circumvention = Some(CircumventionObservationFact {
        tool: detail_value(result, "tool").unwrap_or(result.target.as_str()).to_string(),
        bootstrap_status: http_status(detail_value(result, "bootstrapStatus")),
        handshake_status: endpoint_status(detail_value(result, "handshakeStatus")),
        handshake_failure: transport_failure(detail_value(result, "handshakeError").unwrap_or("none")),
    });
    observation
}
