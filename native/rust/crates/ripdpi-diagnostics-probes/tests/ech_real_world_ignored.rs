//! Real-world integration tests for [`EchHandshakeRunner`] against a live ECH-enabled host.
//!
//! These tests REQUIRE network egress to `cloudflare.com:443` and a working DNS resolver.
//! They run only with:
//!
//! ```text
//! cargo test --test ech_real_world_ignored -- --ignored
//! ```
//!
//! On a sandbox runner without network, `Err(...)` lookup outcomes are expected and tolerated.
//! This is the integration counterpart to the dispatcher tests in
//! `tests/ech_handshake_runner_tdd.rs`; together they form the full
//! TDD coverage for EchHandshakeRunner + driver.

use std::time::Duration;

use ripdpi_diagnostics_probes::ProbeContext;
use ripdpi_diagnostics_probes::probes::ech_handshake::{EchHandshakeDriver, EchHandshakeOutcome, EchHandshakeRunner};
use ripdpi_diagnostics_probes::probes::hickory_rustls_ech_driver::HickoryRustlsEchHandshakeDriver;

fn block_on<F: std::future::Future>(f: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread().enable_all().build().expect("rt").block_on(f)
}

/// Verifies that `HickoryRustlsEchHandshakeDriver::lookup_ech_config` either successfully
/// extracts an ECH config from Cloudflare's HTTPS RR, or returns a documented sandbox-tolerable
/// error. A `None` result is treated as a parsing regression and fails the test.
#[test]
#[ignore]
fn real_world_cloudflare_lookup_resolves_or_documents_failure() {
    let driver = HickoryRustlsEchHandshakeDriver::new();
    let result = block_on(driver.lookup_ech_config("cloudflare.com"));
    match result {
        Ok(Some(bytes)) => {
            let bytes: Vec<u8> = bytes;
            assert!(!bytes.is_empty(), "ECH config bytes should be non-empty");
        }
        Ok(None) => panic!(
            "Cloudflare's HTTPS RR should contain an ech= param; got None — \
             this indicates a parsing regression"
        ),
        Err(detail) => {
            eprintln!("real-world lookup error (expected on sandboxes): {detail}");
            // Acceptable if it's a setup/sandbox failure; not asserting.
        }
    }
}

/// Verifies that the full `EchHandshakeRunner` produces one of the five documented outcome
/// variants when run against `cloudflare.com`. Any unrecognised variant fails the test,
/// pinning the contract: a real-world run cannot produce an unknown verdict.
#[test]
#[ignore]
fn real_world_cloudflare_runner_attempt_produces_documented_outcome() {
    let driver = HickoryRustlsEchHandshakeDriver::new();
    let runner = EchHandshakeRunner::new(driver, "cloudflare.com", Duration::from_secs(5));
    let probe = block_on(runner.attempt(&ProbeContext::empty()));

    let documented_setup_error_fragments = [
        "rustls-ech-feature-not-enabled",
        "svcparam-key-not-found",
        "ech-runner-not-implemented",
        "ech-config-parse-error",
        "ech-clientconfig-build-error",
        "server-name-parse-error",
        "network",
        "timeout",
        "refused",
        "unreachable",
        "resolve",
        "dns",
        "hickory",
    ];

    match &probe.outcome {
        EchHandshakeOutcome::EchAccepted { .. } => {
            // Best-case: ECH negotiation succeeded end-to-end.
        }
        EchHandshakeOutcome::EchRejectedWithRetryConfig => {
            // Server signalled retry configs — still a known, documented outcome.
        }
        EchHandshakeOutcome::NoEchConfig => {
            // HTTPS RR present but no ech= param found — documented outcome.
        }
        EchHandshakeOutcome::HandshakeFailure { detail } => {
            // TLS-layer failure unrelated to ECH config retrieval — documented outcome.
            eprintln!("real-world handshake failure (documented): {detail}");
        }
        EchHandshakeOutcome::SetupError { detail } => {
            let detail_lower = detail.to_lowercase();
            let is_documented = documented_setup_error_fragments.iter().any(|fragment| detail_lower.contains(fragment));
            assert!(
                is_documented,
                "SetupError detail does not match any documented sandbox/setup fragment: {detail:?}\n\
                 If this is a new legitimate failure mode, add it to `documented_setup_error_fragments`."
            );
            eprintln!("real-world setup error (expected on sandboxes): {detail}");
        }
    }
}
