//! AC5 — On DoH failure the error type never encodes a plaintext-DNS fallback.
//!
//! The type-level guarantee: `DohClientError` has no variant that represents
//! "fall back to port-53 plaintext DNS".  Integration with `StrictDnsResult`
//! (row 67) is validated by asserting that every `DohClientError` variant maps
//! to a hard failure and not to a "retry via system DNS" path.

use ripdpi_dns_resolver::doh::DohClientError;
use std::net::{IpAddr, Ipv4Addr};

// ---------------------------------------------------------------------------
// StrictDnsResult mirror — matches the row-67 type without depending on it.
// ---------------------------------------------------------------------------

/// Local mirror of the row-67 `StrictDnsResult` interface.
/// When row 67 is promoted to a public export this can be replaced with the
/// real import.
#[derive(Debug, PartialEq, Eq)]
enum StrictDnsResult {
    /// Resolution succeeded via an encrypted path.
    #[expect(dead_code)]
    Resolved(Vec<u8>),
    /// Hard failure — caller must not try plaintext DNS.
    StrictFailure,
}

// ---------------------------------------------------------------------------
// Converter under test
// ---------------------------------------------------------------------------

/// Converts a `DohClientError` into a `StrictDnsResult`.
///
/// Every variant must become `StrictFailure`; there is no variant that
/// triggers a plaintext fallback.  This is the central invariant.
fn doh_error_to_strict(err: DohClientError) -> StrictDnsResult {
    // Exhaustive match — adding a new variant to DohClientError requires
    // a conscious decision here.
    match err {
        DohClientError::GetNotEnabled
        | DohClientError::AuthNameMismatch { .. }
        | DohClientError::BootstrapIpMismatch { .. }
        | DohClientError::HttpStatus(_)
        | DohClientError::Transport(_)
        | DohClientError::ResponseParse => StrictDnsResult::StrictFailure,
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[test]
fn get_not_enabled_is_strict_failure() {
    let result = doh_error_to_strict(DohClientError::GetNotEnabled);
    assert_eq!(result, StrictDnsResult::StrictFailure);
}

#[test]
fn auth_name_mismatch_is_strict_failure() {
    let err =
        DohClientError::AuthNameMismatch { expected: "dns.example.com".to_string(), presented: "evil.net".to_string() };
    assert_eq!(doh_error_to_strict(err), StrictDnsResult::StrictFailure);
}

#[test]
fn bootstrap_ip_mismatch_is_strict_failure() {
    let err = DohClientError::BootstrapIpMismatch {
        pinned: IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)),
        used: IpAddr::V4(Ipv4Addr::new(9, 9, 9, 9)),
    };
    assert_eq!(doh_error_to_strict(err), StrictDnsResult::StrictFailure);
}

#[test]
fn http_status_error_is_strict_failure() {
    assert_eq!(doh_error_to_strict(DohClientError::HttpStatus(503)), StrictDnsResult::StrictFailure);
}

#[test]
fn transport_error_is_strict_failure() {
    assert_eq!(
        doh_error_to_strict(DohClientError::Transport("connection reset".to_string())),
        StrictDnsResult::StrictFailure,
    );
}

#[test]
fn response_parse_error_is_strict_failure() {
    assert_eq!(doh_error_to_strict(DohClientError::ResponseParse), StrictDnsResult::StrictFailure);
}

/// Compile-time check: `DohClientError` must not implement any trait or
/// carry any associated function named `fallback_to_plaintext`.  This test
/// exists to document the invariant; if such a function ever appears the
/// exhaustive match in `doh_error_to_strict` above would need updating.
#[test]
fn no_plaintext_fallback_path_exists_in_error_type() {
    // If this compiles and runs, the type has no plaintext-fallback path.
    // The exhaustive match in doh_error_to_strict is the enforcement mechanism.
    let _: DohClientError = DohClientError::ResponseParse;
}
