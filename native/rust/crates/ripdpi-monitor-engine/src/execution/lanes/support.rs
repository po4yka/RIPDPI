use crate::candidates::StrategyCandidateSpec;
use crate::types::ProbeDetail;

pub(super) fn candidate_probe_details(
    candidate: &StrategyCandidateSpec,
    protocol: &str,
    latency_ms: u64,
) -> Vec<ProbeDetail> {
    vec![
        ProbeDetail { key: "candidateId".to_string(), value: candidate.id.to_string() },
        ProbeDetail { key: "candidateLabel".to_string(), value: candidate.label.to_string() },
        ProbeDetail { key: "candidateFamily".to_string(), value: candidate.family.to_string() },
        ProbeDetail { key: "protocol".to_string(), value: protocol.to_string() },
        ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
    ]
}
