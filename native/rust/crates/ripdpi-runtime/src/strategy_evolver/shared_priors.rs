//! Shared-priors loader for the offline learner.
//!
//! Sub-modules:
//!
//! - [`parser`] — NDJSON record parser (legacy entry point, unchanged).
//! - [`manifest`] — ed25519 + SHA-256 manifest verification.
//! - [`loader`] — `apply_priors` end-to-end pipeline with fail-secure
//!   semantics, plus [`loader::canonical_combo_hash`] for the stable hash
//!   used as the bundle key.
//!
//! Public surface re-exported here keeps the legacy parser API stable for
//! callers that imported it before the manifest layer landed.

#![allow(dead_code)]

pub mod loader;
pub mod manifest;
pub mod parser;

use std::collections::HashMap;
use std::sync::{OnceLock, RwLock};

pub use loader::{apply_priors, apply_priors_with_embedded_key, canonical_combo_hash, AppliedPriors, ApplyError};
pub use manifest::{is_production_key_set, ManifestError, SharedPriorsManifest, SHARED_PRIORS_PUB_KEY};
pub use parser::SharedPriorsError;

use super::thompson_sampling::BetaParams;

// Process-wide registry that holds the most-recently verified shared
// priors. The JNI bridge calls
// `apply_global_shared_priors` after fetching the manifest + payload from
// GitHub; new `StrategyEvolver` instances consult `latest_shared_priors`
// at session start to seed their prior store.
//
// Replacement is wholesale and atomic: a successful apply swaps the
// stored map; a failed apply leaves the previous map untouched, mirroring
// the in-evolver fail-secure semantics.
static SHARED_PRIORS_REGISTRY: OnceLock<RwLock<HashMap<u64, BetaParams>>> = OnceLock::new();

fn registry() -> &'static RwLock<HashMap<u64, BetaParams>> {
    SHARED_PRIORS_REGISTRY.get_or_init(|| RwLock::new(HashMap::new()))
}

/// Verify a signed shared-priors bundle and write the resulting prior
/// store into the process-wide registry. On verification or parse
/// failure, the registry is left untouched and the error is returned.
///
/// The `public_key` argument is the ed25519 public key the manifest is
/// expected to carry a signature for. Production callers should pass
/// [`SHARED_PRIORS_PUB_KEY`]; until that constant is replaced with a
/// real key, every call short-circuits with
/// [`ApplyError::Manifest`]`(`[`ManifestError::NoProductionKey`]`)` —
/// fail-closed by design.
///
/// On success, returns the number of records loaded into the registry.
pub fn apply_global_shared_priors(
    manifest_bytes: &[u8],
    priors_bytes: &[u8],
    public_key: &[u8; 32],
) -> Result<usize, ApplyError> {
    let applied = apply_priors(manifest_bytes, priors_bytes, public_key)?;
    let count = applied.priors.len();
    let mut guard = registry().write().expect("shared priors registry poisoned");
    *guard = applied.priors;
    Ok(count)
}

/// Production-key entry point for the JNI bridge — wraps
/// [`apply_global_shared_priors`] with the embedded
/// [`SHARED_PRIORS_PUB_KEY`].
pub fn apply_global_shared_priors_with_embedded_key(
    manifest_bytes: &[u8],
    priors_bytes: &[u8],
) -> Result<usize, ApplyError> {
    apply_global_shared_priors(manifest_bytes, priors_bytes, &SHARED_PRIORS_PUB_KEY)
}

/// Read-only snapshot of the most-recently applied prior store. Returns
/// an empty map when no bundle has been applied yet (the OnceLock is
/// initialised on first read so this never panics).
pub fn latest_shared_priors() -> HashMap<u64, BetaParams> {
    registry().read().expect("shared priors registry poisoned").clone()
}

/// Number of priors currently in the global registry. Cheap; takes only
/// a read lock.
pub fn global_shared_priors_len() -> usize {
    registry().read().expect("shared priors registry poisoned").len()
}

#[cfg(test)]
mod registry_tests {
    use super::manifest::test_support::{generate_test_key, sign_manifest_bytes};
    use super::*;

    // The static registry is process-global, so multiple tests writing
    // to it would race. We funnel the registry-mutation tests through a
    // single test that exercises both apply paths in sequence.
    #[test]
    fn registry_replaces_on_success_and_preserves_on_failure() {
        let key = generate_test_key();
        let priors = b"{\"combo_hash\": 1, \"alpha\": 12.0, \"beta\": 4.0}\n";
        let manifest = sign_manifest_bytes(&key, priors, 1, "https://example/p.ndjson");

        // Successful apply: registry now has 1 entry.
        let count = apply_global_shared_priors(manifest.as_bytes(), priors, &key.public_bytes)
            .expect("first apply must succeed");
        assert_eq!(count, 1);
        assert_eq!(global_shared_priors_len(), 1);

        // Failed apply: tampered payload. Registry must keep the prior entry.
        let tampered = b"{\"combo_hash\": 1, \"alpha\": 99.0, \"beta\": 4.0}\n";
        let err = apply_global_shared_priors(manifest.as_bytes(), tampered, &key.public_bytes)
            .expect_err("tampered apply must fail");
        assert!(matches!(err, ApplyError::Manifest(ManifestError::HashMismatch)));
        assert_eq!(global_shared_priors_len(), 1, "fail-secure: registry must keep the previously-applied entry");

        // Embedded-key entry point short-circuits while the placeholder is in place.
        let err = apply_global_shared_priors_with_embedded_key(manifest.as_bytes(), priors)
            .expect_err("placeholder embedded key must reject");
        assert!(matches!(err, ApplyError::Manifest(ManifestError::NoProductionKey)));
    }
}
