use std::collections::BTreeSet;

use crate::types::{Diagnosis, ProbeResult};

use super::common::{DiagnosisSink, diagnosis_evidence, is_close_error, is_reset_error, is_timeout_error};
use super::failure_detail_value;

pub(crate) fn classify_domain_diagnoses(
    results: &[ProbeResult],
    hard_failure_codes: &mut BTreeSet<String>,
    sink: &mut DiagnosisSink,
) {
    for result in results.iter().filter(|result| result.probe_type == "domain_reachability") {
        classify_domain_result(result, hard_failure_codes, sink);
    }
}

fn classify_domain_result(result: &ProbeResult, hard_failure_codes: &mut BTreeSet<String>, sink: &mut DiagnosisSink) {
    push_tls_ech_only(result, sink);
    push_ech_resolution_blocked(result, sink);
    push_tls_cert_mitm(result, hard_failure_codes, sink);
    push_tls_clienthello_failure(result, hard_failure_codes, sink);
    push_http_blockpage(result, hard_failure_codes, sink);
}

fn push_tls_ech_only(result: &ProbeResult, sink: &mut DiagnosisSink) {
    if result.outcome != "tls_ech_only" {
        return;
    }

    sink.push(Diagnosis {
        code: "tls_ech_only".to_string(),
        summary: format!("Plain TLS is blocked for {}, but ECH succeeds", result.target),
        severity: "warning".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(
            result,
            &["tls13Status", "tls12Status", "tlsEchStatus", "tlsEchVersion", "tlsEchError"],
        ),
        recommendation: None,
        control_validated: None,
    });
}

fn push_ech_resolution_blocked(result: &ProbeResult, sink: &mut DiagnosisSink) {
    if result.outcome != "unreachable" {
        return;
    }
    let Some(detail) = failure_detail_value(result, "tlsEchResolutionDetail") else {
        return;
    };
    if !detail.starts_with("ech_resolution_failed") {
        return;
    }

    sink.push(Diagnosis {
        code: "ech_resolution_blocked".to_string(),
        summary: format!("ECH config resolution failed for {} -- encrypted DNS may be blocked", result.target),
        severity: "warning".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(result, &["tlsEchResolutionDetail", "tlsEchError", "tls13Status", "tls12Status"]),
        recommendation: None,
        control_validated: None,
    });
}

fn push_tls_cert_mitm(result: &ProbeResult, hard_failure_codes: &mut BTreeSet<String>, sink: &mut DiagnosisSink) {
    if result.outcome != "tls_cert_invalid" {
        return;
    }

    sink.push(Diagnosis {
        code: "tls_cert_mitm".to_string(),
        summary: format!("TLS certificate anomaly observed for {}", result.target),
        severity: "negative".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(result, &["tlsStatus", "tlsError", "tlsSignal"]),
        recommendation: None,
        control_validated: None,
    });
    hard_failure_codes.insert("tls_cert_mitm".to_string());
}

fn push_tls_clienthello_failure(
    result: &ProbeResult,
    hard_failure_codes: &mut BTreeSet<String>,
    sink: &mut DiagnosisSink,
) {
    let tls_error = failure_detail_value(result, "tlsError").unwrap_or_default().to_ascii_lowercase();
    if tls_error.is_empty() || tls_error == "none" {
        return;
    }

    let (code, summary) = if is_timeout_error(&tls_error) {
        ("tls_clienthello_timeout", format!("TLS handshake to {} timed out after ClientHello", result.target))
    } else if is_reset_error(&tls_error) {
        ("tls_clienthello_rst", format!("TLS handshake to {} was reset after ClientHello", result.target))
    } else if is_close_error(&tls_error) {
        ("tls_clienthello_close", format!("TLS handshake to {} closed unexpectedly after ClientHello", result.target))
    } else {
        return;
    };

    sink.push(Diagnosis {
        code: code.to_string(),
        summary,
        severity: "negative".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(result, &["tlsStatus", "tlsError", "tls13Status", "tls12Status"]),
        recommendation: None,
        control_validated: None,
    });
    hard_failure_codes.insert(code.to_string());
}

fn push_http_blockpage(result: &ProbeResult, hard_failure_codes: &mut BTreeSet<String>, sink: &mut DiagnosisSink) {
    if result.outcome != "http_blockpage" {
        return;
    }

    sink.push(Diagnosis {
        code: "http_blockpage".to_string(),
        summary: format!("HTTP blockpage observed for {}", result.target),
        severity: "negative".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(result, &["httpStatus", "httpResponse"]),
        recommendation: None,
        control_validated: None,
    });
    hard_failure_codes.insert("http_blockpage".to_string());
}
