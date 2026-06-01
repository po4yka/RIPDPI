//! Per-app-family NO_TCP_FALLBACK memory.
//!
//! When an app never retries on TCP after a UDP/QUIC suppression event (i.e.
//! the `NO_TCP_FALLBACK` heuristic fires for that tuple), callers should
//! record the app-package identity here so that RIPDPI does not re-apply
//! soft-disable to the same package in the future.
//!
//! The key is `(package_name, package_version_code)`. Recording a new version
//! for an existing package implicitly invalidates the old entry — this is the
//! "invalidate on app update" requirement. Callers are expected to supply the
//! version code obtained from the Android package manager.
//!
//! # Ownership / integration note
//! This module is a pure in-memory **reference** store. As of the Direct-mode
//! transport-policy-and-verdicts epic the **live owner** of per-app
//! NO_TCP_FALLBACK suppression is the Kotlin service layer
//! (`DirectPathPolicyLearner` consulting `NoTcpFallbackAppMemory`), because that
//! is where Android package identity, package-update broadcasts, and version
//! lookup are available. This type does not itself enforce any live Android
//! policy. It documents the intended contract and stays test-validated so a
//! future native policy owner (gated on per-flow UID->package attribution) can
//! adopt it: consult [`AppFamilyMemory::should_skip_soft_disable`] before
//! applying `QuicMode::SoftDisable` for a package and skip soft-disable when it
//! returns `true`.

use std::collections::HashMap;

/// In-memory store that remembers which `(package_name, version_code)` pairs
/// have been marked as `NO_TCP_FALLBACK`.
///
/// Version-code acts as a cache-buster: a mark recorded for version N is
/// invisible when queried with version M ≠ N, and calling
/// [`invalidate_on_app_update`] with a new version removes the old entry.
#[derive(Debug, Default)]
pub struct AppFamilyMemory {
    /// Maps `package_name` → the version code for which the NO_TCP_FALLBACK
    /// mark was recorded.
    marked: HashMap<String, u64>,
}

impl AppFamilyMemory {
    /// Create a new, empty store.
    pub fn new() -> Self {
        Self::default()
    }

    /// Record that `package_name` at `version_code` never retried on TCP after
    /// a UDP/QUIC suppression event.
    ///
    /// If an earlier mark for the same package but a different version exists
    /// it is silently replaced.
    pub fn record_no_tcp_fallback(&mut self, package_name: &str, version_code: u64) {
        self.marked.insert(package_name.to_owned(), version_code);
    }

    /// Returns `true` when `package_name` has been marked `NO_TCP_FALLBACK`
    /// **for the exact same `version_code`**.
    ///
    /// Returns `false` for unknown packages or when `version_code` differs from
    /// the recorded one (i.e. the app was updated).
    pub fn should_skip_soft_disable(&self, package_name: &str, version_code: u64) -> bool {
        self.marked.get(package_name).copied() == Some(version_code)
    }

    /// Remove the NO_TCP_FALLBACK mark for `package_name` because the package
    /// has been updated to `new_version_code`.
    ///
    /// After this call, [`should_skip_soft_disable`] returns `false` for
    /// `package_name` until a new mark is recorded for `new_version_code`.
    ///
    /// No-op when no mark exists for the package.
    pub fn invalidate_on_app_update(&mut self, package_name: &str, new_version_code: u64) {
        // Remove any mark whose version no longer matches.
        if let Some(recorded) = self.marked.get(package_name).copied()
            && recorded != new_version_code
        {
            self.marked.remove(package_name);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mark_and_lookup_same_version_returns_true() {
        let mut mem = AppFamilyMemory::new();
        mem.record_no_tcp_fallback("com.example.app", 42);
        assert!(mem.should_skip_soft_disable("com.example.app", 42));
    }

    #[test]
    fn mark_v1_lookup_v2_returns_false() {
        let mut mem = AppFamilyMemory::new();
        mem.record_no_tcp_fallback("com.example.app", 1);
        assert!(!mem.should_skip_soft_disable("com.example.app", 2));
    }

    #[test]
    fn invalidate_on_app_update_removes_entry() {
        let mut mem = AppFamilyMemory::new();
        mem.record_no_tcp_fallback("com.example.app", 10);
        mem.invalidate_on_app_update("com.example.app", 11);
        assert!(!mem.should_skip_soft_disable("com.example.app", 10));
        assert!(!mem.should_skip_soft_disable("com.example.app", 11));
    }

    #[test]
    fn lookup_unmarked_package_returns_false() {
        let mem = AppFamilyMemory::new();
        assert!(!mem.should_skip_soft_disable("com.unknown.app", 1));
    }

    #[test]
    fn marking_pkg_a_does_not_affect_pkg_b() {
        let mut mem = AppFamilyMemory::new();
        mem.record_no_tcp_fallback("com.example.a", 5);
        assert!(mem.should_skip_soft_disable("com.example.a", 5));
        assert!(!mem.should_skip_soft_disable("com.example.b", 5));
    }
}
