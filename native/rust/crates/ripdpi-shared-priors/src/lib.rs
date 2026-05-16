//! Signed shared-priors bundle verification and process-wide registry.

#![forbid(unsafe_code)]

pub mod coarse_payload;
pub mod jitter;
pub mod manifest;
pub mod parser;
pub mod uploader;

use std::collections::HashMap;
use std::sync::{OnceLock, RwLock};

pub use {
    manifest::{is_production_key_set, ManifestError, SharedPriorsManifest, SHARED_PRIORS_PUB_KEY},
    parser::SharedPriorsError,
};

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PriorParams {
    pub alpha: f64,
    pub beta: f64,
}

#[derive(Debug)]
pub enum ApplyError {
    Manifest(ManifestError),
    Parse(SharedPriorsError),
    InvalidUtf8,
}

impl std::fmt::Display for ApplyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Manifest(err) => write!(f, "manifest verification failed: {err}"),
            Self::Parse(err) => write!(f, "priors payload parse failed: {err}"),
            Self::InvalidUtf8 => write!(f, "priors payload was not valid utf-8"),
        }
    }
}

impl std::error::Error for ApplyError {}

#[derive(Debug)]
pub struct AppliedPriors {
    pub manifest: SharedPriorsManifest,
    pub priors: HashMap<u64, PriorParams>,
    pub skipped: Vec<(usize, String)>,
}

pub fn apply_priors(
    manifest_bytes: &[u8],
    priors_bytes: &[u8],
    public_key: &[u8; 32],
) -> Result<AppliedPriors, ApplyError> {
    let manifest = manifest::verify_manifest(manifest_bytes, priors_bytes, public_key).map_err(ApplyError::Manifest)?;
    let priors_str = std::str::from_utf8(priors_bytes).map_err(|_| ApplyError::InvalidUtf8)?;
    let loaded = parser::parse(priors_str).map_err(ApplyError::Parse)?;
    Ok(AppliedPriors { manifest, priors: loaded.priors, skipped: loaded.skipped })
}

pub fn apply_priors_with_embedded_key(manifest_bytes: &[u8], priors_bytes: &[u8]) -> Result<AppliedPriors, ApplyError> {
    apply_priors(manifest_bytes, priors_bytes, &SHARED_PRIORS_PUB_KEY)
}

static SHARED_PRIORS_REGISTRY: OnceLock<RwLock<HashMap<u64, PriorParams>>> = OnceLock::new();

fn registry() -> &'static RwLock<HashMap<u64, PriorParams>> {
    SHARED_PRIORS_REGISTRY.get_or_init(|| RwLock::new(HashMap::new()))
}

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

pub fn apply_global_shared_priors_with_embedded_key(
    manifest_bytes: &[u8],
    priors_bytes: &[u8],
) -> Result<usize, ApplyError> {
    apply_global_shared_priors(manifest_bytes, priors_bytes, &SHARED_PRIORS_PUB_KEY)
}

pub fn latest_shared_priors() -> HashMap<u64, PriorParams> {
    registry().read().expect("shared priors registry poisoned").clone()
}

pub fn global_shared_priors_len() -> usize {
    registry().read().expect("shared priors registry poisoned").len()
}

#[cfg(test)]
mod tests {
    use super::manifest::test_support::{generate_test_key, sign_manifest_bytes};
    use super::*;

    const SAMPLE_PRIORS: &[u8] =
        b"{\"combo_hash\": 1, \"alpha\": 12.0, \"beta\": 4.0}\n{\"combo_hash\": 2, \"alpha\": 3.5, \"beta\": 1.5}\n";

    #[test]
    fn apply_priors_roundtrip_returns_parsed_records() {
        let key = generate_test_key();
        let manifest = sign_manifest_bytes(&key, SAMPLE_PRIORS, 1_745_798_400, "https://example/priors.ndjson");
        let applied = apply_priors(manifest.as_bytes(), SAMPLE_PRIORS, &key.public_bytes)
            .expect("apply_priors should succeed for a signed bundle");
        assert_eq!(applied.priors.len(), 2);
        assert!(applied.skipped.is_empty());
        assert_eq!(applied.manifest.issued_at_unix, 1_745_798_400);
    }

    #[test]
    fn registry_replaces_on_success_and_preserves_on_failure() {
        let key = generate_test_key();
        let priors = b"{\"combo_hash\": 1, \"alpha\": 12.0, \"beta\": 4.0}\n";
        let manifest = sign_manifest_bytes(&key, priors, 1, "https://example/p.ndjson");

        let count = apply_global_shared_priors(manifest.as_bytes(), priors, &key.public_bytes)
            .expect("first apply must succeed");
        assert_eq!(count, 1);
        assert_eq!(global_shared_priors_len(), 1);

        let tampered = b"{\"combo_hash\": 1, \"alpha\": 99.0, \"beta\": 4.0}\n";
        let err = apply_global_shared_priors(manifest.as_bytes(), tampered, &key.public_bytes)
            .expect_err("tampered apply must fail");
        assert!(matches!(err, ApplyError::Manifest(ManifestError::HashMismatch)));
        assert_eq!(global_shared_priors_len(), 1, "fail-secure: registry must keep the previously-applied entry");
    }
}
