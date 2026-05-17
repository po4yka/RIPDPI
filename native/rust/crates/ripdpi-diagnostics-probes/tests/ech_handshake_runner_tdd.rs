//! TDD test file for the EchHandshakeRunner driver dispatcher.
//!
//! Written before the driver abstraction exists. The runner currently
//! returns a hard-coded SetupError; this file expects the new
//! `EchHandshakeDriver` trait + generic `EchHandshakeRunner<D>` so the
//! dispatcher's per-outcome mapping is independently testable with a
//! fake driver -- no DNS or TLS I/O.

use std::time::Duration;

use ripdpi_diagnostics_probes::ech_handshake::{
    EchHandshakeDriver, EchHandshakeOutcome, EchHandshakeRunner, NoopEchHandshakeDriver,
};
use ripdpi_diagnostics_probes::{Probe, ProbeContext, ProbeVerdict};

#[derive(Debug, Clone)]
struct FakeDriver {
    lookup: Result<Option<Vec<u8>>, String>,
    handshake: EchHandshakeOutcome,
}

impl EchHandshakeDriver for FakeDriver {
    async fn lookup_ech_config(&self, _host: &str) -> Result<Option<Vec<u8>>, String> {
        self.lookup.clone()
    }

    async fn handshake_with_ech(
        &self,
        _host: &str,
        _port: u16,
        _ech_config: &[u8],
        _timeout: Duration,
    ) -> EchHandshakeOutcome {
        self.handshake.clone()
    }
}

fn runner_with<D: EchHandshakeDriver>(driver: D) -> EchHandshakeRunner<D> {
    EchHandshakeRunner { driver, target_host: "cloudflare.com".to_string(), timeout: Duration::from_secs(5) }
}

fn block_on<F: std::future::Future>(f: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread().enable_all().build().expect("rt").block_on(f)
}

#[test]
fn lookup_error_yields_setup_error_inconclusive() {
    let driver = FakeDriver {
        lookup: Err("hickory: no resolver configured".to_string()),
        handshake: EchHandshakeOutcome::NoEchConfig, // unused
    };
    let probe = block_on(runner_with(driver).attempt(&ProbeContext::empty()));
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Inconclusive { reason } => assert!(reason.contains("hickory"), "reason: {reason}"),
        other => panic!("expected Inconclusive, got {other:?}"),
    }
}

#[test]
fn no_ech_config_yields_fail_ech_config_unavailable() {
    let driver = FakeDriver {
        lookup: Ok(None),
        handshake: EchHandshakeOutcome::NoEchConfig, // unused
    };
    let probe = block_on(runner_with(driver).attempt(&ProbeContext::empty()));
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "ech-config-unavailable"),
        other => panic!("expected Fail, got {other:?}"),
    }
}

#[test]
fn ech_accepted_yields_pass() {
    let driver = FakeDriver {
        lookup: Ok(Some(vec![0xde, 0xad, 0xbe, 0xef])),
        handshake: EchHandshakeOutcome::EchAccepted { selected_alpn: Some("h2".to_string()) },
    };
    let probe = block_on(runner_with(driver).attempt(&ProbeContext::empty()));
    assert_eq!(probe.run(&ProbeContext::empty()).verdict, ProbeVerdict::Pass);
}

#[test]
fn ech_rejected_with_retry_yields_fail_ech_rejected() {
    let driver =
        FakeDriver { lookup: Ok(Some(vec![1, 2, 3])), handshake: EchHandshakeOutcome::EchRejectedWithRetryConfig };
    let probe = block_on(runner_with(driver).attempt(&ProbeContext::empty()));
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "ech-rejected"),
        other => panic!("expected Fail, got {other:?}"),
    }
}

#[test]
fn handshake_failure_yields_fail_with_sanitized_class() {
    let driver = FakeDriver {
        lookup: Ok(Some(vec![1, 2, 3])),
        handshake: EchHandshakeOutcome::HandshakeFailure { detail: "TCP RST after SNI".to_string() },
    };
    let probe = block_on(runner_with(driver).attempt(&ProbeContext::empty()));
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert!(class.starts_with("ech-handshake-failure::"), "class: {class}"),
        other => panic!("expected Fail, got {other:?}"),
    }
}

#[test]
fn relay_hint_from_context_is_recorded_on_probe() {
    let driver =
        FakeDriver { lookup: Ok(Some(vec![1])), handshake: EchHandshakeOutcome::EchAccepted { selected_alpn: None } };
    let ctx = ProbeContext { relay_hint: Some("vless-relay-a".to_string()), ..ProbeContext::empty() };
    let probe = block_on(runner_with(driver).attempt(&ctx));
    assert_eq!(probe.relay_hint_used.as_deref(), Some("vless-relay-a"));
}

#[test]
fn noop_driver_yields_inconclusive_setup_error() {
    // The noop driver preserves the pre-refactor stub behaviour so
    // existing callers that have not picked a real driver still see
    // the documented "not implemented" signal.
    let runner = EchHandshakeRunner {
        driver: NoopEchHandshakeDriver,
        target_host: "example.com".to_string(),
        timeout: Duration::from_secs(1),
    };
    let probe = block_on(runner.attempt(&ProbeContext::empty()));
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Inconclusive { reason } => {
            assert!(
                reason.contains("not-implemented")
                    || reason.contains("not implemented")
                    || reason.contains("not wired"),
                "reason: {reason}"
            );
        }
        other => panic!("expected Inconclusive, got {other:?}"),
    }
}
