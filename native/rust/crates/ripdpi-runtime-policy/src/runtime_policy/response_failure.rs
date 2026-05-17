use ripdpi_failure_classifier::{
    classify_http_response_block, classify_tls_alert, classify_tls_handshake_failure, confirm_dns_tampering,
    ClassifiedFailure,
};
use ripdpi_runtime_decision_ports::policy_ports::DnsTamperingEvidence;
use ripdpi_session::{detect_response_trigger, TriggerEvent};

use super::matching::is_tls_client_hello_payload;

pub fn response_requires_dns_tampering_evidence(request: &[u8], response: &[u8]) -> bool {
    response.starts_with(b"HTTP/1.") && is_tls_client_hello_payload(request)
}

pub fn classify_response_failure(
    request: &[u8],
    response: &[u8],
    dns_evidence: Option<DnsTamperingEvidence<'_>>,
) -> Option<ClassifiedFailure> {
    if let Some(evidence) = dns_evidence.filter(|_| response_requires_dns_tampering_evidence(request, response)) {
        if let Some(dns_tampering) =
            confirm_dns_tampering(evidence.host, evidence.target_ip, evidence.answers, evidence.resolver_label)
        {
            return Some(dns_tampering);
        }
    }

    if let Some(alert) = classify_tls_alert(response) {
        return Some(alert);
    }
    if let Some(http_block) = classify_http_response_block(response) {
        return Some(http_block);
    }
    if matches!(detect_response_trigger(request, response), Some(TriggerEvent::SslErr)) {
        return Some(classify_tls_handshake_failure("TLS handshake failed before ServerHello"));
    }
    None
}
