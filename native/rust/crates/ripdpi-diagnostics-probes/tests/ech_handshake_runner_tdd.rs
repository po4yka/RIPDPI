//! TDD test file for the EchHandshakeRunner driver dispatcher.
//!
//! Exercises the `EchHandshakeDriver` trait + generic `EchHandshakeRunner<D>`
//! dispatcher with fake drivers so per-outcome mapping stays testable without
//! DNS or TLS I/O.

use std::time::Duration;

use ripdpi_diagnostics_probes::probes::ech_handshake::{
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

struct FakeUnreachableDriver;

impl EchHandshakeDriver for FakeUnreachableDriver {
    /// # Cancel safety:
    /// cancel-safe: this guard panics before it starts any work.
    async fn lookup_ech_config(&self, _host: &str) -> Result<Option<Vec<u8>>, String> {
        panic!("unsupported context must not start a lookup")
    }

    /// # Cancel safety:
    /// cancel-safe: this guard panics before it starts any work.
    async fn handshake_with_ech(
        &self,
        _host: &str,
        _port: u16,
        _ech_config: &[u8],
        _timeout: Duration,
    ) -> EchHandshakeOutcome {
        panic!("unsupported context must not start a handshake")
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
    assert_eq!(probe.relay_hint_used, None);
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
fn unsupported_context_is_rejected_before_io_without_relay_attribution() {
    let contexts = [
        ProbeContext { relay_hint: Some("vless-relay-a".to_string()), ..ProbeContext::empty() },
        ProbeContext { resolver_hint: Some("encrypted-dns".to_string()), ..ProbeContext::empty() },
        ProbeContext { strategy_signature: Some("active-strategy".to_string()), ..ProbeContext::empty() },
        ProbeContext {
            relay_hint: Some(String::new()),
            resolver_hint: Some(String::new()),
            strategy_signature: Some(String::new()),
            ..ProbeContext::empty()
        },
    ];
    for ctx in contexts {
        let probe = block_on(runner_with(FakeUnreachableDriver).attempt(&ctx));
        assert_eq!(
            probe.run(&ctx).verdict,
            ProbeVerdict::Inconclusive { reason: "ech-runner-context-unsupported".to_string() },
        );
        assert_eq!(probe.relay_hint_used, None);
    }
}

#[test]
fn network_scope_metadata_does_not_prevent_a_direct_attempt() {
    let driver =
        FakeDriver { lookup: Ok(Some(vec![1])), handshake: EchHandshakeOutcome::EchAccepted { selected_alpn: None } };
    let ctx = ProbeContext { network_scope_key: Some("network-scope".to_string()), ..ProbeContext::empty() };
    let probe = block_on(runner_with(driver).attempt(&ctx));
    assert_eq!(probe.run(&ctx).verdict, ProbeVerdict::Pass);
    assert_eq!(probe.relay_hint_used, None);
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
    assert_eq!(probe.relay_hint_used, None);
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
