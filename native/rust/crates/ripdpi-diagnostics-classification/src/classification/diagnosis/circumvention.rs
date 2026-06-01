use crate::types::{Diagnosis, ProbeResult};

use super::common::{DiagnosisSink, diagnosis_evidence, is_http_failure, is_tls_failure};
use super::failure_detail_value;

pub(crate) fn classify_circumvention_diagnoses(results: &[ProbeResult], sink: &mut DiagnosisSink) {
    for result in results.iter().filter(|result| result.probe_type == "circumvention_reachability") {
        classify_circumvention_result(result, sink);
    }
}

fn classify_circumvention_result(result: &ProbeResult, sink: &mut DiagnosisSink) {
    let tool_name = failure_detail_value(result, "tool").unwrap_or(result.target.as_str()).to_string();
    let bootstrap_status = failure_detail_value(result, "bootstrapStatus").unwrap_or("not_run");
    let handshake_status = failure_detail_value(result, "handshakeStatus").unwrap_or("not_run");

    if is_http_failure(bootstrap_status) {
        sink.push(Diagnosis {
            code: "circumvention_bootstrap_blocked".to_string(),
            summary: format!("{tool_name} bootstrap endpoint is blocked"),
            severity: "negative".to_string(),
            target: Some(tool_name.clone()),
            evidence: diagnosis_evidence(result, &["bootstrapStatus", "bootstrapDetail"]),
            recommendation: None,
            control_validated: None,
        });
    }
    if is_tls_failure(handshake_status) {
        sink.push(Diagnosis {
            code: "circumvention_handshake_blocked".to_string(),
            summary: format!("{tool_name} handshake endpoint is blocked"),
            severity: "negative".to_string(),
            target: Some(tool_name),
            evidence: diagnosis_evidence(result, &["handshakeStatus", "handshakeError"]),
            recommendation: None,
            control_validated: None,
        });
    }
}
