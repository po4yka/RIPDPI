//! AC3 — Log output and diagnostics do not contain URL path, body bytes,
//! domain, or response payload.

use ripdpi_dns_resolver::doh::DohRedactor;

const REAL_URL_PATH: &str = "/dns-query?dns=AAABAAABAAAAAAAA...base64url";
const REAL_DOMAIN: &str = "secret-internal-service.corp.example.com";
const REAL_BODY: &[u8] = b"\x00\x01\x00\x00\x00\x01\x00\x00\x00\x00\x00\x00";
const REAL_PAYLOAD: &[u8] = b"\x00\x01\x81\x80\x00\x01\x00\x01\x00\x00\x00\x00";

#[test]
fn redact_url_path_does_not_contain_real_path() {
    let r = DohRedactor::new();
    let out = r.redact_url_path(REAL_URL_PATH);
    assert!(!out.contains("/dns-query"), "redacted URL path must not contain the real path; got: {out:?}",);
    assert!(!out.contains("dns="), "redacted URL path must not contain the query parameter; got: {out:?}",);
    assert!(!out.is_empty(), "redacted output must not be empty");
}

#[test]
fn redact_domain_does_not_contain_real_domain() {
    let r = DohRedactor::new();
    let out = r.redact_domain(REAL_DOMAIN);
    assert!(!out.contains("secret-internal-service"), "redacted domain must not contain the real domain; got: {out:?}",);
    assert!(!out.contains("corp.example.com"), "redacted domain must not contain corp suffix; got: {out:?}",);
}

#[test]
fn redact_request_body_does_not_contain_raw_bytes() {
    let r = DohRedactor::new();
    let out = r.redact_request_body(REAL_BODY);
    // The placeholder is a static string; it must not embed a hex/base64
    // encoding of the bytes either.
    assert!(!out.contains("000100"), "redacted body must not contain hex-encoded bytes; got: {out:?}",);
}

#[test]
fn redact_response_payload_does_not_contain_raw_bytes() {
    let r = DohRedactor::new();
    let out = r.redact_response_payload(REAL_PAYLOAD);
    assert!(!out.contains("018180"), "redacted payload must not contain hex-encoded bytes; got: {out:?}",);
}

#[test]
fn redacted_fields_produce_stable_placeholder_strings() {
    let r = DohRedactor::new();
    // Call twice — output must be identical and deterministic.
    assert_eq!(r.redact_url_path(REAL_URL_PATH), r.redact_url_path(REAL_URL_PATH));
    assert_eq!(r.redact_domain(REAL_DOMAIN), r.redact_domain(REAL_DOMAIN));
    assert_eq!(r.redact_request_body(REAL_BODY), r.redact_request_body(REAL_BODY));
    assert_eq!(r.redact_response_payload(REAL_PAYLOAD), r.redact_response_payload(REAL_PAYLOAD));
}
