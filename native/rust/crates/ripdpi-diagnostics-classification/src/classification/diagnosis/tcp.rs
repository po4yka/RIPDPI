use crate::types::{Diagnosis, ProbeResult, ScanRequest};

use super::common::{diagnosis_evidence, DiagnosisSink};
use super::failure_detail_value;

pub(crate) fn classify_tcp_diagnoses(
    request: &ScanRequest,
    results: &[ProbeResult],
    tcp_whitelist_bypass: bool,
    sink: &mut DiagnosisSink,
) {
    for result in results.iter().filter(|result| result.probe_type == "tcp_fat_header") {
        classify_tcp_result(result, sink);
    }
    push_sni_triggered_tls_interference(request, tcp_whitelist_bypass, sink);
}

fn classify_tcp_result(result: &ProbeResult, sink: &mut DiagnosisSink) {
    push_tcp_16kb_cutoff(result, sink);
    push_connection_freeze(result, sink);
    push_port_443_policed(result, sink);
    push_whitelist_sni_bypassable(result, sink);
}

fn push_tcp_16kb_cutoff(result: &ProbeResult, sink: &mut DiagnosisSink) {
    if result.outcome != "tcp_16kb_blocked" {
        return;
    }

    sink.push(Diagnosis {
        code: "tcp_16kb_cutoff".to_string(),
        summary: format!("Late-stage TCP cutoff observed for {}", result.target),
        severity: "negative".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(result, &["bytesSent", "responsesSeen", "lastError"]),
        recommendation: None,
        control_validated: None,
    });
}

fn push_connection_freeze(result: &ProbeResult, sink: &mut DiagnosisSink) {
    if result.outcome != "tcp_freeze_after_threshold" {
        return;
    }

    sink.push(Diagnosis {
        code: "connection_freeze_detected".to_string(),
        summary: format!("Connection froze after data transfer threshold for {}", result.target),
        severity: "negative".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(result, &["bytesSent", "responsesSeen", "freezeThresholdBytes", "lastError"]),
        recommendation: Some(
            "Connection was frozen after ~16KB of data. This pattern is consistent with middlebox port-443 policing. Try using an alternative port."
                .to_string(),
        ),
        control_validated: None,
    });
}

fn push_port_443_policed(result: &ProbeResult, sink: &mut DiagnosisSink) {
    let alt_port_ok = failure_detail_value(result, "altPortStatus") == Some("ok");
    let main_port_443 = failure_detail_value(result, "port") == Some("443");
    if !alt_port_ok
        || !main_port_443
        || !matches!(
            result.outcome.as_str(),
            "tcp_freeze_after_threshold" | "tcp_16kb_blocked" | "tcp_reset" | "tcp_timeout"
        )
    {
        return;
    }

    let alt_port = failure_detail_value(result, "altPort").unwrap_or("8443");
    sink.push(Diagnosis {
        code: "port_443_policed".to_string(),
        summary: format!("Port 443 appears policed for {} but port {} works", result.target, alt_port),
        severity: "negative".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(
            result,
            &["port", "altPort", "altPortStatus", "altPortResponsesSeen", "bytesSent", "responsesSeen"],
        ),
        recommendation: Some(format!(
            "Port 443 is being specifically targeted by your ISP. Switch to port {alt_port} or another non-standard HTTPS port to avoid policing.",
        )),
        control_validated: None,
    });
}

fn push_whitelist_sni_bypassable(result: &ProbeResult, sink: &mut DiagnosisSink) {
    if result.outcome != "whitelist_sni_ok" {
        return;
    }

    sink.push(Diagnosis {
        code: "whitelist_sni_bypassable".to_string(),
        summary: format!("Whitelisted SNI recovered access for {}", result.target),
        severity: "warning".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(result, &["selectedSni", "attempts"]),
        recommendation: None,
        control_validated: None,
    });
}

fn push_sni_triggered_tls_interference(request: &ScanRequest, tcp_whitelist_bypass: bool, sink: &mut DiagnosisSink) {
    if !tcp_whitelist_bypass
        || !sink.contains_code(&["tls_clienthello_timeout", "tls_clienthello_rst", "tls_clienthello_close"])
    {
        return;
    }

    sink.push(Diagnosis {
        code: "sni_triggered_tls_interference".to_string(),
        summary: "TLS interference appears sensitive to the requested SNI".to_string(),
        severity: "warning".to_string(),
        target: request.region_tag.clone(),
        evidence: vec![
            "tls failures recovered when a whitelisted SNI was used".to_string(),
            "whitelist_sni_ok".to_string(),
        ],
        recommendation: None,
        control_validated: None,
    });
}
