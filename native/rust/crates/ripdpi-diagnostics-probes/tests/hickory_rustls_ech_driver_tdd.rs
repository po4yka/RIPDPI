//! TDD tests for [`HickoryRustlsEchHandshakeDriver`].
//!
//! Tests are split into two tiers:
//!
//! - **Pure / compile-time** — no I/O; always run in PR CI.
//! - **Network** — marked `#[ignore]`; run manually or in a dedicated
//!   network-egress lane with `cargo test -- --ignored`.

use std::time::Duration;

use ripdpi_diagnostics_probes::ech_handshake::{EchHandshakeDriver, EchHandshakeOutcome};
use ripdpi_diagnostics_probes::hickory_rustls_ech_driver::HickoryRustlsEchHandshakeDriver;

// ── helpers ───────────────────────────────────────────────────────────────────

/// Minimal block_on helper — mirrors the pattern in ech_handshake_runner_tdd.rs
/// so we do not require `tokio::test` macros in the workspace features.
fn block_on<F: std::future::Future>(f: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread().enable_all().build().expect("tokio rt").block_on(f)
}

// ── compile-time trait check ──────────────────────────────────────────────────

/// Compile-time assertion that `HickoryRustlsEchHandshakeDriver` satisfies the
/// `EchHandshakeDriver` bound. The function is never called; its existence is
/// the test.
fn _assert_impl<T: EchHandshakeDriver>() {}

#[test]
fn driver_implements_ech_handshake_driver_trait() {
    // If this compiles, the trait is correctly implemented.
    let _ = _assert_impl::<HickoryRustlsEchHandshakeDriver>;
}

// ── pure unit tests ───────────────────────────────────────────────────────────

/// The default constructor must set `lookup_timeout` to exactly 5 seconds.
#[test]
fn default_constructor_sets_5s_lookup_timeout() {
    let driver = HickoryRustlsEchHandshakeDriver::new();
    assert_eq!(driver.lookup_timeout, Duration::from_secs(5));
}

/// `Default` impl must agree with `new()`.
#[test]
fn default_trait_matches_new() {
    let via_new = HickoryRustlsEchHandshakeDriver::new();
    let via_default = HickoryRustlsEchHandshakeDriver::default();
    assert_eq!(via_new.lookup_timeout, via_default.lookup_timeout);
}

/// The handshake stub must return `SetupError` with the documented detail
/// string. No network I/O is performed.
#[test]
fn handshake_returns_setup_error_with_documented_detail() {
    let driver = HickoryRustlsEchHandshakeDriver::new();
    let outcome = block_on(driver.handshake_with_ech("cloudflare.com", 443, b"\x00", Duration::from_secs(1)));
    match outcome {
        EchHandshakeOutcome::SetupError { detail } => {
            assert_eq!(detail, "rustls-ech-feature-not-enabled", "unexpected detail: {detail:?}");
        }
        other => panic!("expected SetupError, got {other:?}"),
    }
}

// ── network tests (ignored in PR CI) ─────────────────────────────────────────

/// Verifies that `lookup_ech_config` returns `Err(_)` for a host that cannot
/// exist in DNS. Requires outbound UDP/TCP on port 53 (Cloudflare resolver).
///
/// Run with: `cargo test -p ripdpi-diagnostics-probes -- --ignored`
#[test]
#[ignore]
fn lookup_for_nonexistent_host_returns_err() {
    let driver = HickoryRustlsEchHandshakeDriver::new();
    let result = block_on(driver.lookup_ech_config("ripdpi-test-nx-nonexistent-12345.invalid"));
    assert!(result.is_err(), "expected Err for non-existent host, got {result:?}");
}
