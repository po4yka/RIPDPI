use std::sync::Mutex as StdMutex;

use ripdpi_proxy_runtime_adapter::proxy_config::NetworkSnapshot;

use super::identity::snapshot_identity;

/// Tracks the last observed network identity so we only reprobe when the
/// network actually changes, not on every `NetworkSnapshot` push.
pub(crate) struct ReprobeTracker {
    last_identity: StdMutex<Option<String>>,
}

impl ReprobeTracker {
    pub(crate) fn new() -> Self {
        Self { last_identity: StdMutex::new(None) }
    }

    /// Returns `true` if the network identity changed since the last call,
    /// indicating that a reprobe should be scheduled. Returns `false` for the
    /// initial assignment (proxy just started) and for unchanged snapshots.
    pub(crate) fn check_snapshot(&self, snapshot: &NetworkSnapshot) -> bool {
        let identity = snapshot_identity(snapshot);
        let mut last = self.last_identity.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if last.as_deref() == Some(&identity) {
            return false;
        }
        let is_initial = last.is_none();
        *last = Some(identity);
        // Don't reprobe on initial snapshot (proxy just started).
        !is_initial
    }
}
