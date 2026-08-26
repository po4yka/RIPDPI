//! Failure classification from pre-extracted [`FieldCache`] fields.
//!
//! Consumes fields already parsed by a [`ResponseParser`] instead of
//! re-scanning raw response bytes. This eliminates redundant parsing
//! when multiple classifiers inspect the same response.

use ripdpi_packets::fields::FieldCache;

use crate::block_detection::{BlockpageFingerprint, match_blockpage_response, match_body_keyword};
use crate::tls::{CLOSE_NOTIFY_ALERT, tls_alert_description};
use crate::{ClassifiedFailure, FailureAction, FailureClass, FailureEvidence, FailureStage};

/// Classify a failure from pre-extracted protocol fields.
///
/// Checks TLS alerts, HTTP blockpages/redirects, and TLS handshake
/// failures using cached fields — no byte-level re-parsing needed.
///
/// TLS `close_notify` is treated as a graceful close rather than a
/// failure: classification falls through to the HTTP checks instead.
///
/// Returns `None` if no failure is detected.
pub fn classify_from_fields(cache: &FieldCache, fingerprints: &[BlockpageFingerprint]) -> Option<ClassifiedFailure> {
    // 1. TLS alert (highest priority — unambiguous signal), except a
    //    graceful close_notify, which is not a failure at all.
    if let Some(alert_code) = cache.tls_alert_code()
        && alert_code != CLOSE_NOTIFY_ALERT
    {
        let alert_desc = tls_alert_description(Some(alert_code));
        return Some(
            ClassifiedFailure::new(
                FailureClass::TlsAlert,
                FailureStage::FirstResponse,
                FailureAction::RetryWithMatchingGroup,
                format!("TLS alert received: {alert_desc}"),
            )
            .with_tag("alert", alert_desc.to_string()),
        );
    }

    // 2. HTTP classification (blockpage, redirect, legal block).
    if let Some(status) = cache.http_status_code() {
        // HTTP 451 (Unavailable For Legal Reasons) is always a blockpage.
        if status == 451 {
            return Some(ClassifiedFailure::new(
                FailureClass::HttpBlockpage,
                FailureStage::HttpResponse,
                FailureAction::RetryWithMatchingGroup,
                "HTTP 451 Unavailable For Legal Reasons",
            ));
        }

        // Check CSV fingerprints against cached headers and body.
        let headers: Vec<(String, String)> = cache
            .fields()
            .iter()
            .filter_map(|f| match f {
                ripdpi_packets::fields::ProtocolField::HttpHeader { name, value } => {
                    Some((name.clone(), value.clone()))
                }
                _ => None,
            })
            .collect();
        let body = cache.body_bytes();

        if let Some(provider) = match_blockpage_response(&headers, &body, fingerprints) {
            let is_redirect = (300..400).contains(&status);
            let class = if is_redirect { FailureClass::Redirect } else { FailureClass::HttpBlockpage };
            return Some(ClassifiedFailure {
                class,
                stage: FailureStage::HttpResponse,
                action: FailureAction::RetryWithMatchingGroup,
                evidence: FailureEvidence::new(format!("Blockpage fingerprint matched: {provider}"))
                    .with_tag("provider", provider)
                    .with_tag("statusCode", status.to_string()),
            });
        }

        // HTTP 403 with generic block keywords in body.
        if status == 403 && match_body_keyword(&body).is_some() {
            return Some(ClassifiedFailure::new(
                FailureClass::HttpBlockpage,
                FailureStage::HttpResponse,
                FailureAction::RetryWithMatchingGroup,
                "HTTP 403 with blockpage keywords in body",
            ));
        }

        // Redirect without fingerprint match — still suspicious.
        if (300..400).contains(&status)
            && let Some(location) = cache.redirect_location()
        {
            return Some(
                ClassifiedFailure::new(
                    FailureClass::Redirect,
                    FailureStage::HttpResponse,
                    FailureAction::RetryWithMatchingGroup,
                    format!("HTTP {status} redirect to {location}"),
                )
                .with_tag("location", location.to_string()),
            );
        }
    }

    // 3. TLS handshake failure (expected ServerHello but got neither).
    // Only applicable when TLS fields were expected but not seen.
    // Callers should only invoke this when the request was a TLS ClientHello.

    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_packets::fields::{FieldObserver, ProtocolField};

    fn empty_fingerprints() -> Vec<BlockpageFingerprint> {
        vec![]
    }

    #[test]
    fn tls_alert_from_cache() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::TlsAlertCode(40));

        let result = classify_from_fields(&cache, &empty_fingerprints());
        let failure = result.expect("should classify TLS alert");
        assert_eq!(failure.class, FailureClass::TlsAlert);
        assert!(failure.evidence.summary.contains("handshake_failure"));
    }

    #[test]
    fn http_451_from_cache() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::HttpStatusCode(451));

        let result = classify_from_fields(&cache, &empty_fingerprints());
        let failure = result.expect("should classify HTTP 451");
        assert_eq!(failure.class, FailureClass::HttpBlockpage);
    }

    #[test]
    fn http_403_with_keywords_from_cache() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::HttpStatusCode(403));
        cache.on_field(&ProtocolField::HttpBodyChunk(b"Access Denied by policy".to_vec()));

        let result = classify_from_fields(&cache, &empty_fingerprints());
        let failure = result.expect("should classify blockpage");
        assert_eq!(failure.class, FailureClass::HttpBlockpage);
    }

    #[test]
    fn http_302_redirect_from_cache() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::HttpStatusCode(302));
        cache.on_field(&ProtocolField::HttpRedirectLocation("http://block.isp.example/".into()));

        let result = classify_from_fields(&cache, &empty_fingerprints());
        let failure = result.expect("should classify redirect");
        assert_eq!(failure.class, FailureClass::Redirect);
    }

    #[test]
    fn http_200_no_failure() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::HttpStatusCode(200));
        cache.on_field(&ProtocolField::HttpBodyChunk(b"<html>Hello</html>".to_vec()));

        let result = classify_from_fields(&cache, &empty_fingerprints());
        assert!(result.is_none());
    }

    #[test]
    fn empty_cache_no_failure() {
        let cache = FieldCache::new();
        let result = classify_from_fields(&cache, &empty_fingerprints());
        assert!(result.is_none());
    }

    #[test]
    fn tls_alert_takes_priority_over_http() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::TlsAlertCode(40));
        cache.on_field(&ProtocolField::HttpStatusCode(403));

        let result = classify_from_fields(&cache, &empty_fingerprints());
        let failure = result.expect("should classify");
        assert_eq!(failure.class, FailureClass::TlsAlert, "TLS alert should take priority");
    }

    #[test]
    fn close_notify_falls_through_to_http_checks() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::TlsAlertCode(0));
        cache.on_field(&ProtocolField::HttpStatusCode(403));
        cache.on_field(&ProtocolField::HttpBodyChunk(b"Access Denied by policy".to_vec()));

        let result = classify_from_fields(&cache, &empty_fingerprints());
        let failure = result.expect("close_notify must not mask the http blockpage");
        assert_eq!(failure.class, FailureClass::HttpBlockpage);
    }

    #[test]
    fn lone_close_notify_is_not_a_failure() {
        let mut cache = FieldCache::new();
        cache.on_field(&ProtocolField::TlsAlertCode(0));

        assert!(classify_from_fields(&cache, &empty_fingerprints()).is_none());
    }
}
