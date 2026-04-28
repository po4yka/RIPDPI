//! Shared-priors loader for the offline learner (P4.4.4, ADR-011).
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

pub use loader::{apply_priors, apply_priors_with_embedded_key, canonical_combo_hash, AppliedPriors, ApplyError};
pub use manifest::{
    is_production_key_set, verify_manifest, ManifestError, SharedPriorsManifest, SHARED_PRIORS_MANIFEST_VERSION,
    SHARED_PRIORS_PUB_KEY,
};
pub use parser::{parse, Loaded, SharedPriorsError, MAX_RAW_PAYLOAD};
