//! Runtime-adaptation — `VpnService.protect` dispatch for outbound sockets.
//!
//! `protect_socket` chooses between two paths at call time: if a
//! `VpnService.protect` callback is registered (via the `protect` /
//! `ripdpi-native-protect` registry) it routes the fd through that callback;
//! otherwise it falls back to `ripdpi-privileged-ops` on Linux/Android, or a
//! no-op on other targets. This is the load-bearing protect invariant — see
//! `.claude/rules/vpnservice-protect-invariant.md`. Surfaced through the
//! `vpn` facade.

use std::io;
use std::os::fd::AsRawFd;
use std::sync::atomic::{AtomicU64, Ordering};

const PROTECT_SUBSYSTEM: &str = "protect";
const PROTECT_EVENT_KIND: &str = "vpn_protect";
const PROTECT_EVENT_SOURCE: &str = "vpn_protect";
const PROTECT_SUCCESS_SAMPLE_INTERVAL: u64 = 64;
static PROXY_SUCCESS_SEQUENCE: AtomicU64 = AtomicU64::new(0);
static DIAGNOSTICS_SUCCESS_SEQUENCE: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ProtectEventRing {
    Proxy,
    Diagnostics,
}

impl ProtectEventRing {
    const fn as_str(self) -> &'static str {
        match self {
            Self::Proxy => "proxy",
            Self::Diagnostics => "diagnostics",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ProtectBackend {
    Jni,
    #[cfg(any(target_os = "linux", target_os = "android"))]
    Uds,
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    Noop,
}

impl ProtectBackend {
    const fn as_str(self) -> &'static str {
        match self {
            Self::Jni => "jni",
            #[cfg(any(target_os = "linux", target_os = "android"))]
            Self::Uds => "uds",
            #[cfg(not(any(target_os = "linux", target_os = "android")))]
            Self::Noop => "noop",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ProtectOutcome {
    Success,
    Rejected,
    Error,
}

impl ProtectOutcome {
    const fn as_str(self) -> &'static str {
        match self {
            Self::Success => "success",
            Self::Rejected => "rejected",
            Self::Error => "error",
        }
    }
}

fn classify_outcome(result: &io::Result<()>) -> ProtectOutcome {
    match result {
        Ok(()) => ProtectOutcome::Success,
        Err(error) if error.kind() == io::ErrorKind::PermissionDenied => ProtectOutcome::Rejected,
        Err(_) => ProtectOutcome::Error,
    }
}

fn should_emit_outcome(outcome: ProtectOutcome, success_sequence: u64) -> bool {
    outcome != ProtectOutcome::Success || success_sequence.is_multiple_of(PROTECT_SUCCESS_SAMPLE_INTERVAL)
}

fn run_with_outcome(
    backend: ProtectBackend,
    ring: Option<ProtectEventRing>,
    diagnostics_session_id: Option<&str>,
    protect: impl FnOnce() -> io::Result<()>,
) -> io::Result<()> {
    let result = protect();
    let outcome = classify_outcome(&result);
    if let Some(ring) = ring {
        let success_sequence = match (outcome, ring) {
            (ProtectOutcome::Success, ProtectEventRing::Proxy) => {
                PROXY_SUCCESS_SEQUENCE.fetch_add(1, Ordering::Relaxed)
            }
            (ProtectOutcome::Success, ProtectEventRing::Diagnostics) => {
                DIAGNOSTICS_SUCCESS_SEQUENCE.fetch_add(1, Ordering::Relaxed)
            }
            _ => 0,
        };
        if should_emit_outcome(outcome, success_sequence) {
            let backend = backend.as_str();
            let outcome = outcome.as_str();
            let diagnostics_session_id = diagnostics_session_id.unwrap_or("");
            tracing::debug!(
                ring = ring.as_str(),
                source = PROTECT_EVENT_SOURCE,
                kind = PROTECT_EVENT_KIND,
                subsystem = PROTECT_SUBSYSTEM,
                diagnostics_session_id,
                backend,
                outcome,
                "vpn protect backend={} outcome={}",
                backend,
                outcome
            );
        }
    }
    result
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn protect_socket<T: AsRawFd>(socket: &T, path: Option<&str>) -> io::Result<()> {
    protect_socket_with_ring(socket, path, None, None)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn protect_socket_with_ring<T: AsRawFd>(
    socket: &T,
    path: Option<&str>,
    ring: Option<ProtectEventRing>,
    diagnostics_session_id: Option<&str>,
) -> io::Result<()> {
    if crate::protect::has_protect_callback() {
        return run_with_outcome(ProtectBackend::Jni, ring, diagnostics_session_id, || {
            crate::protect::protect_socket_via_callback(socket.as_raw_fd())
        });
    }

    run_with_outcome(ProtectBackend::Uds, ring, diagnostics_session_id, || {
        ripdpi_privileged_ops::protect_socket(socket, path)
    })
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn protect_socket<T: AsRawFd>(socket: &T, path: Option<&str>) -> io::Result<()> {
    protect_socket_with_ring(socket, path, None, None)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn protect_socket_with_ring<T: AsRawFd>(
    socket: &T,
    _path: Option<&str>,
    ring: Option<ProtectEventRing>,
    diagnostics_session_id: Option<&str>,
) -> io::Result<()> {
    if crate::protect::has_protect_callback() {
        return run_with_outcome(ProtectBackend::Jni, ring, diagnostics_session_id, || {
            crate::protect::protect_socket_via_callback(socket.as_raw_fd())
        });
    }

    run_with_outcome(ProtectBackend::Noop, ring, diagnostics_session_id, || Ok(()))
}

pub fn protect_proxy_socket<T: AsRawFd>(socket: &T, path: Option<&str>) -> io::Result<()> {
    protect_socket_with_ring(socket, path, Some(ProtectEventRing::Proxy), None)
}

pub fn protect_diagnostics_socket<T: AsRawFd>(
    socket: &T,
    path: Option<&str>,
    diagnostics_session_id: &str,
) -> io::Result<()> {
    protect_socket_with_ring(socket, path, Some(ProtectEventRing::Diagnostics), Some(diagnostics_session_id))
}

#[cfg(test)]
mod tests {
    use android_support::{EventRingBuffers, EventRingLayer, RingConfig};
    use tracing_subscriber::prelude::*;

    use super::*;

    fn ring_buffers() -> EventRingBuffers {
        EventRingBuffers::new(RingConfig {
            proxy_capacity: 8,
            relay_capacity: 8,
            warp_capacity: 8,
            tunnel_capacity: 8,
            diagnostics_capacity: 8,
        })
    }

    #[test]
    fn classifies_successful_protection() {
        assert_eq!(classify_outcome(&Ok(())), ProtectOutcome::Success);
    }

    #[test]
    fn classifies_protection_rejection_without_error_details() {
        let result = Err(io::Error::new(io::ErrorKind::PermissionDenied, "sensitive detail"));
        assert_eq!(classify_outcome(&result), ProtectOutcome::Rejected);
    }

    #[test]
    fn classifies_operational_failure_without_error_details() {
        let result = Err(io::Error::new(io::ErrorKind::ConnectionRefused, "sensitive detail"));
        assert_eq!(classify_outcome(&result), ProtectOutcome::Error);
    }

    #[test]
    fn samples_successes_but_never_rejections_or_errors() {
        assert!(should_emit_outcome(ProtectOutcome::Success, 0));
        assert!(!should_emit_outcome(ProtectOutcome::Success, 1));
        assert!(should_emit_outcome(ProtectOutcome::Success, 64));
        assert!(should_emit_outcome(ProtectOutcome::Rejected, 1));
        assert!(should_emit_outcome(ProtectOutcome::Error, 1));
    }

    #[test]
    fn records_redacted_protect_outcome_in_selected_ring() {
        let buffers = ring_buffers();
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));

        let result = tracing::subscriber::with_default(subscriber, || {
            run_with_outcome(ProtectBackend::Jni, Some(ProtectEventRing::Diagnostics), Some("stage-a"), || Ok(()))
        });

        assert!(result.is_ok());
        assert!(buffers.drain_proxy().is_empty());
        let events = buffers.drain_diagnostics();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].source, PROTECT_EVENT_SOURCE);
        assert_eq!(events[0].kind.as_deref(), Some(PROTECT_EVENT_KIND));
        assert_eq!(events[0].diagnostics_session_id.as_deref(), Some("stage-a"));
        assert_eq!(events[0].message, "vpn protect backend=jni outcome=success");
        assert!(!events[0].message.contains("fd="));
    }
}
