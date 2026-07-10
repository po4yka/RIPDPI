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
    manifest::{ManifestError, SHARED_PRIORS_PUB_KEY, SharedPriorsManifest, is_production_key_set},
    parser::SharedPriorsError,
};

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PriorParams {
    pub alpha: f64,
    pub beta: f64,
}

/// Fixed beta pseudo-failure mass for a verified `active_broad` protocol
/// threat. It is intentionally small enough for local successes to outweigh.
pub const ACTIVE_BROAD_BETA_PSEUDO_FAILURES: f64 = 3.0;

/// Verified, network-scoped protocol threat priors from the shared bundle.
///
/// Cloning creates an independent snapshot of the in-memory map. Callers can
/// retain that snapshot for one scoring run without holding the global lock.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ProtocolThreatPriors {
    entries: HashMap<String, HashMap<String, i64>>,
}

impl ProtocolThreatPriors {
    /// Number of active-broad records in this snapshot, including records that
    /// may have expired since the bundle was applied.
    pub fn len(&self) -> usize {
        self.entries.values().map(HashMap::len).sum()
    }

    /// Whether this snapshot contains no protocol threat records.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Return the bounded beta pseudo-failure prior for an exact protocol and
    /// network-scope match. Missing and expired records are neutral.
    pub fn beta_pseudo_failures(&self, protocol_class: &str, network_scope_key: &str, now_unix: i64) -> f64 {
        if now_unix < 0 {
            return 0.0;
        }
        match self.entries.get(protocol_class).and_then(|scopes| scopes.get(network_scope_key)) {
            Some(expires_at_unix) if now_unix < *expires_at_unix => ACTIVE_BROAD_BETA_PSEUDO_FAILURES,
            Some(_) | None => 0.0,
        }
    }

    fn insert(&mut self, protocol_class: String, network_scope_key: String, expires_at_unix: i64) -> bool {
        self.entries.entry(protocol_class).or_default().insert(network_scope_key, expires_at_unix).is_none()
    }
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
    pub protocol_threats: ProtocolThreatPriors,
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
    Ok(AppliedPriors {
        manifest,
        priors: loaded.priors,
        protocol_threats: loaded.protocol_threats,
        skipped: loaded.skipped,
    })
}

pub fn apply_priors_with_embedded_key(manifest_bytes: &[u8], priors_bytes: &[u8]) -> Result<AppliedPriors, ApplyError> {
    apply_priors(manifest_bytes, priors_bytes, &SHARED_PRIORS_PUB_KEY)
}

#[derive(Debug, Default)]
struct RegistryState {
    priors: HashMap<u64, PriorParams>,
    protocol_threats: ProtocolThreatPriors,
}

static SHARED_PRIORS_REGISTRY: OnceLock<RwLock<RegistryState>> = OnceLock::new();

fn registry() -> &'static RwLock<RegistryState> {
    SHARED_PRIORS_REGISTRY.get_or_init(|| RwLock::new(RegistryState::default()))
}

pub fn apply_global_shared_priors(
    manifest_bytes: &[u8],
    priors_bytes: &[u8],
    public_key: &[u8; 32],
) -> Result<usize, ApplyError> {
    let applied = apply_priors(manifest_bytes, priors_bytes, public_key)?;
    let count = applied.priors.len();
    let mut guard = registry().write().expect("shared priors registry poisoned");
    *guard = RegistryState { priors: applied.priors, protocol_threats: applied.protocol_threats };
    Ok(count)
}

pub fn apply_global_shared_priors_with_embedded_key(
    manifest_bytes: &[u8],
    priors_bytes: &[u8],
) -> Result<usize, ApplyError> {
    apply_global_shared_priors(manifest_bytes, priors_bytes, &SHARED_PRIORS_PUB_KEY)
}

pub fn latest_shared_priors() -> HashMap<u64, PriorParams> {
    registry().read().expect("shared priors registry poisoned").priors.clone()
}

pub fn global_shared_priors_len() -> usize {
    registry().read().expect("shared priors registry poisoned").priors.len()
}

pub fn latest_protocol_threat_priors() -> ProtocolThreatPriors {
    registry().read().expect("shared priors registry poisoned").protocol_threats.clone()
}

pub fn global_protocol_threat_priors_len() -> usize {
    registry().read().expect("shared priors registry poisoned").protocol_threats.len()
}

#[cfg(test)]
mod tests {
    use super::manifest::test_support::{generate_test_key, sign_manifest_bytes};
    use super::*;

    const SAMPLE_PRIORS: &[u8] =
        b"{\"combo_hash\": 1, \"alpha\": 12.0, \"beta\": 4.0}\n{\"combo_hash\": 2, \"alpha\": 3.5, \"beta\": 1.5}\n";
    const SCOPE_KEY: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    #[test]
    fn apply_priors_roundtrip_returns_parsed_records() {
        let key = generate_test_key();
        let manifest = sign_manifest_bytes(&key, SAMPLE_PRIORS, 1_745_798_400, "https://example/priors.ndjson");
        let applied = apply_priors(manifest.as_bytes(), SAMPLE_PRIORS, &key.public_bytes)
            .expect("apply_priors should succeed for a signed bundle");
        assert_eq!(applied.priors.len(), 2);
        assert!(applied.protocol_threats.is_empty());
        assert!(applied.skipped.is_empty());
        assert_eq!(applied.manifest.issued_at_unix, 1_745_798_400);
    }

    #[test]
    fn registry_atomically_replaces_both_stores_and_preserves_them_on_failure() {
        let key = generate_test_key();
        let priors = format!(
            "{{\"combo_hash\":1,\"alpha\":12.0,\"beta\":4.0}}\n{{\"record_type\":\"protocol_threat\",\"protocol_class\":\"vless\",\"network_scope_key\":\"{SCOPE_KEY}\",\"state\":\"active_broad\",\"expires_at_unix\":2000}}\n"
        );
        let manifest = sign_manifest_bytes(&key, priors.as_bytes(), 1, "https://example/p.ndjson");

        let count = apply_global_shared_priors(manifest.as_bytes(), priors.as_bytes(), &key.public_bytes)
            .expect("first apply must succeed");
        assert_eq!(count, 1);
        assert_eq!(global_shared_priors_len(), 1);
        assert_eq!(global_protocol_threat_priors_len(), 1);
        assert_eq!(
            latest_protocol_threat_priors().beta_pseudo_failures("vless", SCOPE_KEY, 1000),
            ACTIVE_BROAD_BETA_PSEUDO_FAILURES
        );

        let invalid = format!(
            "{{\"record_type\":\"protocol_threat\",\"protocol_class\":\"VLESS\",\"network_scope_key\":\"{SCOPE_KEY}\",\"state\":\"active_broad\",\"expires_at_unix\":2000}}\n"
        );
        let invalid_manifest = sign_manifest_bytes(&key, invalid.as_bytes(), 2, "https://example/invalid.ndjson");
        let err = apply_global_shared_priors(invalid_manifest.as_bytes(), invalid.as_bytes(), &key.public_bytes)
            .expect_err("signed invalid threat update must fail");
        assert!(matches!(err, ApplyError::Parse(SharedPriorsError::InvalidThreatRecord { .. })));
        assert_eq!(global_shared_priors_len(), 1);
        assert_eq!(global_protocol_threat_priors_len(), 1);

        let combo_only = b"{\"combo_hash\": 7, \"alpha\": 2.0, \"beta\": 1.0}\n";
        let combo_only_manifest = sign_manifest_bytes(&key, combo_only, 3, "https://example/combo-only.ndjson");
        apply_global_shared_priors(combo_only_manifest.as_bytes(), combo_only, &key.public_bytes)
            .expect("valid combo-only update must replace both stores");
        assert_eq!(global_shared_priors_len(), 1);
        assert_eq!(global_protocol_threat_priors_len(), 0);

        let tampered = b"{\"combo_hash\": 1, \"alpha\": 99.0, \"beta\": 4.0}\n";
        let err = apply_global_shared_priors(manifest.as_bytes(), tampered, &key.public_bytes)
            .expect_err("tampered apply must fail");
        assert!(matches!(err, ApplyError::Manifest(ManifestError::HashMismatch)));
        assert_eq!(global_shared_priors_len(), 1, "fail-secure: registry must keep the previously-applied entry");
        assert_eq!(
            global_protocol_threat_priors_len(),
            0,
            "fail-secure: registry must keep the previously-applied threat state"
        );
    }
}
