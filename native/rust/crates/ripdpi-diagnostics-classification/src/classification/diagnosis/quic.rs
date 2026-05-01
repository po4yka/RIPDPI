use crate::types::{Diagnosis, ProbeResult};

use super::common::{diagnosis_evidence, DiagnosisSink};

pub(crate) fn classify_quic_diagnoses(results: &[ProbeResult], sink: &mut DiagnosisSink) {
    for result in results.iter().filter(|result| result.probe_type == "quic_reachability") {
        if !matches!(result.outcome.as_str(), "quic_initial_response" | "quic_response") {
            sink.push(Diagnosis {
                code: "quic_blocked".to_string(),
                summary: format!("QUIC appears blocked or degraded for {}", result.target),
                severity: "warning".to_string(),
                target: Some(result.target.clone()),
                evidence: diagnosis_evidence(result, &["status", "error", "latencyMs"]),
                recommendation: None,
                control_validated: None,
            });
        }
    }
}
