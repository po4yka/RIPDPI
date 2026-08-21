//! Pull-model telemetry for the relay spoofer.
//!
//! Mirrors the RIPDPI atomic-counter pull pattern: a process-global
//! `AtomicU64` tally that the relay's 1 Hz telemetry poller reads via the
//! accessor. The tracing event carries ONLY the static event name —
//! never the decoy SNI hostname, the destination, or the spoof method value
//! (see `network-fingerprint-privacy.md` and AC7 redaction).

use std::sync::atomic::{AtomicU64, Ordering};

static SPOOF_ACTIVE: AtomicU64 = AtomicU64::new(0);
static SPOOF_FAILED: AtomicU64 = AtomicU64::new(0);

/// Number of connections for which a decoy ClientHello has been injected since
/// process start. Pull-model accessor for the relay telemetry poller.
#[must_use]
pub fn spoof_active_count() -> u64 {
    SPOOF_ACTIVE.load(Ordering::Relaxed)
}

/// Number of injection attempts that failed since process start (missing
/// capabilities, unsupported address family, send errors). Pull-model
/// accessor for the relay telemetry poller.
#[must_use]
pub fn spoof_failed_count() -> u64 {
    SPOOF_FAILED.load(Ordering::Relaxed)
}

/// Record that one connection had its decoy ClientHello injected.
///
/// Privacy: the emitted tracing event name is a fixed string. It MUST NOT be
/// extended with the decoy hostname, destination IP, or method value.
pub fn note_spoofed_connection() {
    // Ordering: Relaxed — standalone telemetry tally
    SPOOF_ACTIVE.fetch_add(1, Ordering::Relaxed);
    tracing::debug!("tls.spoof_active");
}

/// Record that one injection attempt failed.
///
/// Privacy: same redaction contract as [`note_spoofed_connection`] — fixed
/// event name only; the failure *class* is logged by the caller as an
/// `io::ErrorKind`, never as a message carrying addresses or hostnames.
pub fn note_spoof_failed() {
    // Ordering: Relaxed — standalone telemetry tally
    SPOOF_FAILED.fetch_add(1, Ordering::Relaxed);
    tracing::debug!("tls.spoof_failed");
}

#[cfg(test)]
mod tests {
    use super::*;

    // NOTE: SPOOF_ACTIVE is process-global; this is the only test that mutates
    // it, so the before/after delta is deterministic within this binary.
    #[test]
    fn increments_once_per_call() {
        let before = spoof_active_count();
        note_spoofed_connection();
        assert_eq!(spoof_active_count(), before + 1);
        note_spoofed_connection();
        assert_eq!(spoof_active_count(), before + 2);
    }

    // NOTE: SPOOF_FAILED is process-global and only this test mutates it.
    #[test]
    fn failures_tallied_independently_of_successes() {
        let before_ok = spoof_active_count();
        let before_failed = spoof_failed_count();
        note_spoof_failed();
        note_spoof_failed();
        assert_eq!(spoof_failed_count(), before_failed + 2);
        assert_eq!(spoof_active_count(), before_ok, "failure tally must not touch the success tally");
    }
}
