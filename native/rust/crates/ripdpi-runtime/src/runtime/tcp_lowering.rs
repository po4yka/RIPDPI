use std::io;

use crate::platform;
use crate::sync::{AtomicBool, Ordering};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct TcpLoweringCapabilities {
    pub(super) restore_ttl: u8,
    pub(super) ttl_actions_unavailable: bool,
}

impl TcpLoweringCapabilities {
    pub(super) fn snapshot(default_ttl: u8, session_ttl_unavailable: &AtomicBool) -> Self {
        let restore_ttl = if default_ttl != 0 { default_ttl } else { platform::detect_default_ttl().unwrap_or(64) };
        Self { restore_ttl, ttl_actions_unavailable: session_ttl_unavailable.load(Ordering::Relaxed) }
    }

    pub(super) fn persist(self, session_ttl_unavailable: &AtomicBool) {
        if self.ttl_actions_unavailable {
            session_ttl_unavailable.store(true, Ordering::Relaxed);
        }
    }
}

#[cfg(any(test, target_os = "android"))]
pub(super) fn should_ignore_android_ttl_error(err: &io::Error) -> bool {
    matches!(
        err.raw_os_error(),
        Some(libc::EROFS | libc::EINVAL | libc::ENOPROTOOPT | libc::EOPNOTSUPP | libc::EPERM | libc::EACCES)
    )
}

#[cfg(not(any(test, target_os = "android")))]
pub(super) fn should_ignore_android_ttl_error(_err: &io::Error) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshot_uses_configured_default_ttl_and_session_seed() {
        let session = AtomicBool::new(true);
        let caps = TcpLoweringCapabilities::snapshot(42, &session);

        assert_eq!(caps.restore_ttl, 42);
        assert!(caps.ttl_actions_unavailable);
    }

    #[test]
    fn persist_carries_discovered_unavailability_to_session() {
        let session = AtomicBool::new(false);
        TcpLoweringCapabilities { restore_ttl: 64, ttl_actions_unavailable: true }.persist(&session);

        assert!(session.load(Ordering::Relaxed));
    }
}
