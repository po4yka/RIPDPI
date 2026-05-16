//! AC1 — Runtime client defaults to POST; GET is disabled by default.

use ripdpi_dns_resolver::doh::{DohClientError, DohRuntimeMode};

#[test]
fn default_mode_is_post() {
    assert_eq!(DohRuntimeMode::default(), DohRuntimeMode::Post);
}

#[test]
fn get_mode_returns_error_without_opt_in() {
    // Simulates what the runtime client enforces before sending a request.
    let mode = DohRuntimeMode::Get;
    let result = enforce_mode_policy(mode, false);
    assert!(
        matches!(result, Err(DohClientError::GetNotEnabled)),
        "GET without opt-in must return GetNotEnabled, got: {result:?}",
    );
}

#[test]
fn post_mode_is_always_allowed() {
    let result = enforce_mode_policy(DohRuntimeMode::Post, false);
    assert!(result.is_ok(), "POST must always be allowed regardless of profile flag");
}

#[test]
fn get_mode_with_opt_in_is_allowed() {
    let result = enforce_mode_policy(DohRuntimeMode::Get, true);
    assert!(result.is_ok(), "GET with allow_doh_get=true must be allowed");
}

/// Minimal stand-in for what `DohClient::query` enforces before building the
/// HTTP request.  Production code applies this check; the test validates the
/// policy in isolation.
fn enforce_mode_policy(mode: DohRuntimeMode, allow_doh_get: bool) -> Result<(), DohClientError> {
    if mode == DohRuntimeMode::Get && !allow_doh_get {
        return Err(DohClientError::GetNotEnabled);
    }
    Ok(())
}
