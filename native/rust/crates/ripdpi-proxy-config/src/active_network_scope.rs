//! Ambient active-network scope for `(host, NetProfile)`-keyed caches.
//!
//! Resolver-selection caching is keyed by `(host, network_scope)`. The
//! `network_scope` is the *NetProfile* component: a stable identifier for the
//! physical network the device is currently attached to. There is exactly one
//! active network at a time, so the scope is process-wide ambient state rather
//! than something threaded through every resolution call.
//!
//! It lives here, alongside [`crate::NetworkSnapshot`] (the scope's source),
//! so both the publisher (`ripdpi-runtime-services`
//! `BackgroundProbes::notify_network_change`) and the reader
//! (`ripdpi-ws-bootstrap` resolver selection) reach it through their existing
//! dependency on this crate — without adding new dependency edges.
//!
//! # Privacy
//!
//! The scope string MUST be a non-reversible, stable per-network key built only
//! from already-hashed / locale-stable identifiers (e.g. a hashed SSID,
//! operator code, DNS-server set) — never a raw BSSID / SSID / IMEI / device
//! IP. See `.claude/rules/network-fingerprint-privacy.md`. Callers are
//! responsible for honoring this; this module only stores the opaque string.

use std::sync::RwLock;

static ACTIVE_NETWORK_SCOPE: RwLock<Option<String>> = RwLock::new(None);

/// Publish the active network scope. `None` (or an all-whitespace string)
/// clears it, which disables `(host, NetProfile)` resolver-selection caching
/// until a real scope is published again.
pub fn set_active_network_scope(scope: Option<String>) {
    let normalized = scope.filter(|value| !value.trim().is_empty());
    if let Ok(mut guard) = ACTIVE_NETWORK_SCOPE.write() {
        *guard = normalized;
    }
}

/// Read the active network scope, if one has been published.
///
/// Returns `None` when no usable network has been observed yet — in which case
/// `(host, NetProfile)` caching is a no-op and resolver selection behaves
/// exactly as it did before the cache existed.
pub fn active_network_scope() -> Option<String> {
    ACTIVE_NETWORK_SCOPE.read().ok().and_then(|guard| guard.clone())
}

#[cfg(test)]
mod tests {
    use super::*;

    // A single test drives the process-wide global sequentially so concurrent
    // tests in this binary cannot race the shared scope.
    #[test]
    fn publish_read_and_clear_round_trip() {
        let unique = "test-scope::publish_read_and_clear_round_trip";
        set_active_network_scope(Some(unique.to_string()));
        assert_eq!(active_network_scope().as_deref(), Some(unique));

        // Blank input clears the scope back to None (caching becomes a no-op).
        set_active_network_scope(Some("   ".to_string()));
        assert_eq!(active_network_scope(), None);

        set_active_network_scope(Some(unique.to_string()));
        set_active_network_scope(None);
        assert_eq!(active_network_scope(), None);
    }
}
