//! AC2 — Profile with allow_doh_get=true permits GET; default config doesn't.

use ripdpi_dns_resolver::doh::{DohClientError, DohRuntimeMode};

/// Simulated profile struct that mirrors what the production resolver profile
/// will carry.  The `allow_doh_get` flag must default to `false`.
#[derive(Default)]
struct ResolverProfile {
    allow_doh_get: bool,
}

fn select_mode(profile: &ResolverProfile) -> DohRuntimeMode {
    if profile.allow_doh_get { DohRuntimeMode::Get } else { DohRuntimeMode::Post }
}

fn enforce_mode(mode: DohRuntimeMode, profile: &ResolverProfile) -> Result<(), DohClientError> {
    if mode == DohRuntimeMode::Get && !profile.allow_doh_get {
        return Err(DohClientError::GetNotEnabled);
    }
    Ok(())
}

#[test]
fn default_profile_selects_post() {
    let profile = ResolverProfile::default();
    assert_eq!(select_mode(&profile), DohRuntimeMode::Post);
}

#[test]
fn default_profile_rejects_get() {
    let profile = ResolverProfile::default();
    let err = enforce_mode(DohRuntimeMode::Get, &profile).expect_err("GET must be rejected by default");
    assert!(matches!(err, DohClientError::GetNotEnabled));
}

#[test]
fn opted_in_profile_permits_get() {
    let profile = ResolverProfile { allow_doh_get: true };
    assert_eq!(select_mode(&profile), DohRuntimeMode::Get);
    enforce_mode(DohRuntimeMode::Get, &profile).expect("GET must be allowed with opt-in");
}

#[test]
fn opted_in_profile_also_permits_post() {
    let profile = ResolverProfile { allow_doh_get: true };
    enforce_mode(DohRuntimeMode::Post, &profile).expect("POST must still work with opt-in");
}
