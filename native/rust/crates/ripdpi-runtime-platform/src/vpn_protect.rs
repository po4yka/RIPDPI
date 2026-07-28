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

const PROTECT_SUBSYSTEM: &str = "protect";

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

fn run_with_outcome(backend: ProtectBackend, protect: impl FnOnce() -> io::Result<()>) -> io::Result<()> {
    let backend = backend.as_str();
    tracing::debug!(subsystem = PROTECT_SUBSYSTEM, backend, outcome = "attempt", "vpn protect");

    let result = protect();
    let outcome = classify_outcome(&result).as_str();
    tracing::debug!(subsystem = PROTECT_SUBSYSTEM, backend, outcome, "vpn protect");
    result
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn protect_socket<T: AsRawFd>(socket: &T, path: Option<&str>) -> io::Result<()> {
    if crate::protect::has_protect_callback() {
        return run_with_outcome(ProtectBackend::Jni, || {
            crate::protect::protect_socket_via_callback(socket.as_raw_fd())
        });
    }

    run_with_outcome(ProtectBackend::Uds, || ripdpi_privileged_ops::protect_socket(socket, path))
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn protect_socket<T: AsRawFd>(socket: &T, _path: Option<&str>) -> io::Result<()> {
    if crate::protect::has_protect_callback() {
        return run_with_outcome(ProtectBackend::Jni, || {
            crate::protect::protect_socket_via_callback(socket.as_raw_fd())
        });
    }

    run_with_outcome(ProtectBackend::Noop, || Ok(()))
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
