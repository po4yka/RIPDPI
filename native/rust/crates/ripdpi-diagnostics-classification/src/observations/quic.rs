use crate::types::{ObservationKind, ProbeObservation, ProbeResult, QuicObservationFact};

use super::common::{base_observation, detail_value, quic_status, transport_failure};

pub(crate) fn build_quic_observation(result: &ProbeResult) -> ProbeObservation {
    let mut observation = base_observation(result, ObservationKind::Quic);
    observation.quic = Some(QuicObservationFact {
        host: result.target.clone(),
        status: quic_status(detail_value(result, "status").unwrap_or(result.outcome.as_str())),
        transport_failure: transport_failure(detail_value(result, "error").unwrap_or("none")),
    });
    observation
}
