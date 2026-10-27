use ripdpi_failure_classifier::{
    ClassifiedFailure, classify_http_response_block, classify_tls_alert, classify_tls_handshake_failure,
    confirm_dns_tampering,
};
use ripdpi_packets::is_http;
use ripdpi_runtime_decision_ports::policy_ports::DnsTamperingEvidence;
use ripdpi_session::{TriggerEvent, detect_response_trigger};

use super::matching::is_tls_client_hello_payload;

pub fn response_requires_dns_tampering_evidence(request: &[u8], response: &[u8]) -> bool {
    response.starts_with(b"HTTP/1.") && is_tls_client_hello_payload(request)
}

pub fn classify_response_failure(
    request: &[u8],
    response: &[u8],
    dns_evidence: Option<DnsTamperingEvidence<'_>>,
) -> Option<ClassifiedFailure> {
    if let Some(evidence) = dns_evidence.filter(|_| response_requires_dns_tampering_evidence(request, response))
        && let Some(dns_tampering) =
            confirm_dns_tampering(evidence.host, evidence.target_ip, evidence.answers, evidence.resolver_label)
    {
        return Some(dns_tampering);
    }

    if let Some(alert) = classify_tls_alert(response) {
        return Some(alert);
    }
    let trigger = detect_response_trigger(request, response);
    if let Some(http_block) = classify_http_response_block(response)
        && !block_stub_redirect_stays_on_site(request, &http_block, trigger)
    {
        return Some(http_block);
    }
    if matches!(trigger, Some(TriggerEvent::SslErr)) {
        return Some(classify_tls_handshake_failure("TLS handshake failed before ServerHello"));
    }
    None
}

/// A Location host-label match is only a heuristic. For a plain HTTP request it counts only when
/// the redirect also leaves the requested site; a site that redirects to its own `warning.` subdomain is
/// not a block. A cross-site redirect alone stays unflagged, because every first-response failure
/// emits a block signal and cross-site redirects are ordinary web behavior.
fn block_stub_redirect_stays_on_site(
    request: &[u8],
    failure: &ClassifiedFailure,
    trigger: Option<TriggerEvent>,
) -> bool {
    is_http(request)
        && trigger != Some(TriggerEvent::Redirect)
        && failure.evidence.tags.iter().any(|tag| tag == "provider=redirect_block_url")
}

#[cfg(test)]
mod tests {
    use super::*;

    const REQUEST: &[u8] = b"GET / HTTP/1.1\r\nHost: news.example.com\r\n\r\n";

    fn redirect(location: &str) -> Vec<u8> {
        format!("HTTP/1.1 302 Found\r\nLocation: {location}\r\nContent-Length: 0\r\n\r\n").into_bytes()
    }

    #[test]
    fn block_stub_redirect_to_another_site_is_a_failure() {
        let failure = classify_response_failure(REQUEST, &redirect("http://warning.isp.example/"), None);
        assert!(failure.is_some());
    }

    #[test]
    fn block_stub_label_on_the_requested_site_is_not_a_failure() {
        assert!(
            classify_response_failure(REQUEST, &redirect("https://warning.news.example.com/notice"), None).is_none()
        );
    }

    #[test]
    fn plain_cross_site_redirect_is_not_a_failure() {
        assert!(classify_response_failure(REQUEST, &redirect("https://login.other.net/"), None).is_none());
    }
}
