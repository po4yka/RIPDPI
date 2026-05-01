use crate::types::{Diagnosis, ProbeResult};

use super::common::{diagnosis_evidence, is_http_failure, is_quic_failure, DiagnosisSink};
use super::failure_detail_value;

pub(crate) fn classify_service_diagnoses(results: &[ProbeResult], sink: &mut DiagnosisSink) {
    for result in results.iter().filter(|result| result.probe_type == "service_reachability") {
        classify_service_result(result, sink);
    }
}

fn classify_service_result(result: &ProbeResult, sink: &mut DiagnosisSink) {
    let service_name = failure_detail_value(result, "service").unwrap_or(result.target.as_str()).to_string();
    let bootstrap_status = failure_detail_value(result, "bootstrapStatus").unwrap_or("not_run");
    let media_status = failure_detail_value(result, "mediaStatus").unwrap_or("not_run");
    let quic_status = failure_detail_value(result, "quicStatus").unwrap_or("not_run");

    if is_http_failure(bootstrap_status) {
        sink.push(Diagnosis {
            code: "service_bootstrap_blocked".to_string(),
            summary: format!("{service_name} bootstrap endpoint is blocked"),
            severity: "negative".to_string(),
            target: Some(service_name.clone()),
            evidence: diagnosis_evidence(result, &["bootstrapStatus", "bootstrapDetail", "gatewayStatus"]),
            recommendation: None,
            control_validated: None,
        });
    }
    if is_http_failure(media_status) {
        sink.push(Diagnosis {
            code: "service_media_blocked".to_string(),
            summary: format!("{service_name} media endpoint is blocked or throttled"),
            severity: "negative".to_string(),
            target: Some(service_name.clone()),
            evidence: diagnosis_evidence(result, &["mediaStatus", "mediaDetail"]),
            recommendation: None,
            control_validated: None,
        });
    }
    if is_quic_failure(quic_status) {
        sink.push(Diagnosis {
            code: "quic_blocked".to_string(),
            summary: format!("QUIC appears blocked or degraded for {service_name}"),
            severity: "warning".to_string(),
            target: Some(service_name),
            evidence: diagnosis_evidence(result, &["quicStatus", "quicError"]),
            recommendation: None,
            control_validated: None,
        });
    }
}
