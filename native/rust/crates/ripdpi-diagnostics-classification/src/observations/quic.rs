use crate::types::{
    ObservationKind, ProbeObservation, ProbeResult, QuicObservationFact, QuicProbeStatus, TransportFailureKind,
};

use super::common::{base_observation, detail_value, quic_status, transport_failure};

const PROBE_TYPE: &str = "quic_reachability";

pub(crate) fn build_observation(result: &ProbeResult) -> Option<ProbeObservation> {
    (result.probe_type == PROBE_TYPE).then(|| build_quic_observation(result))
}

pub(crate) fn build_quic_observation(result: &ProbeResult) -> ProbeObservation {
    let mut observation = base_observation(result, ObservationKind::Quic);
    let status = quic_status(detail_value(result, "status").unwrap_or(result.outcome.as_str()));
    let transport_failure = match status {
        QuicProbeStatus::Timeout => TransportFailureKind::Timeout,
        QuicProbeStatus::InitialClose => TransportFailureKind::Close,
        QuicProbeStatus::Cancelled | QuicProbeStatus::DeadlineExceeded | QuicProbeStatus::NotRun => {
            TransportFailureKind::None
        }
        _ => transport_failure(detail_value(result, "error").unwrap_or("none")),
    };
    observation.quic = Some(QuicObservationFact { host: result.target.clone(), status, transport_failure });
    observation
}
