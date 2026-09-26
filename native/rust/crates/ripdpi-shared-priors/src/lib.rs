//! Signed shared-priors bundle verification and process-wide registry.

#![forbid(unsafe_code)]

pub mod coarse_payload;
pub mod jitter;
pub mod manifest;
pub mod parser;
pub mod uploader;
mod watermark;

use std::collections::HashMap;
use std::path::Path;
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
    Watermark(std::io::Error),
    Rollback { accepted: i64, incoming: i64 },
    ConflictingRelease(i64),
}

impl std::fmt::Display for ApplyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Manifest(err) => write!(f, "manifest verification failed: {err}"),
            Self::Parse(err) => write!(f, "priors payload parse failed: {err}"),
            Self::InvalidUtf8 => write!(f, "priors payload was not valid utf-8"),
            Self::Watermark(err) => write!(f, "shared-priors release marker failed: {err}"),
            Self::Rollback { accepted, incoming } => {
                write!(f, "shared-priors release {incoming} is older than accepted release {accepted}")
            }
            Self::ConflictingRelease(issued_at) => {
                write!(f, "shared-priors release {issued_at} has a conflicting payload")
            }
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
    watermark_path: &Path,
) -> Result<usize, ApplyError> {
    let applied = apply_priors(manifest_bytes, priors_bytes, public_key)?;
    let mut guard = registry().write().expect("shared priors registry poisoned");
    publish_verified(applied, watermark_path, &mut guard)
}

fn publish_verified(
    applied: AppliedPriors,
    watermark_path: &Path,
    guard: &mut RegistryState,
) -> Result<usize, ApplyError> {
    let count = applied.priors.len();
    watermark::check_and_store(watermark_path, &applied.manifest)?;
    *guard = RegistryState { priors: applied.priors, protocol_threats: applied.protocol_threats };
    Ok(count)
}

pub fn apply_global_shared_priors_with_embedded_key(
    manifest_bytes: &[u8],
    priors_bytes: &[u8],
    watermark_path: &Path,
) -> Result<usize, ApplyError> {
    apply_global_shared_priors(manifest_bytes, priors_bytes, &SHARED_PRIORS_PUB_KEY, watermark_path)
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
        let temp = tempfile::tempdir().expect("marker directory");
        let marker = temp.path().join("release.json");
        let key = generate_test_key();
        let priors = format!(
            "{{\"combo_hash\":1,\"alpha\":12.0,\"beta\":4.0}}\n{{\"record_type\":\"protocol_threat\",\"protocol_class\":\"vless\",\"network_scope_key\":\"{SCOPE_KEY}\",\"state\":\"active_broad\",\"expires_at_unix\":2000}}\n"
        );
        let manifest = sign_manifest_bytes(&key, priors.as_bytes(), 1, "https://example/p.ndjson");

        let count = apply_global_shared_priors(manifest.as_bytes(), priors.as_bytes(), &key.public_bytes, &marker)
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
        let err =
            apply_global_shared_priors(invalid_manifest.as_bytes(), invalid.as_bytes(), &key.public_bytes, &marker)
                .expect_err("signed invalid threat update must fail");
        assert!(matches!(err, ApplyError::Parse(SharedPriorsError::InvalidThreatRecord { .. })));
        assert_eq!(global_shared_priors_len(), 1);
        assert_eq!(global_protocol_threat_priors_len(), 1);

        let combo_only = b"{\"combo_hash\": 7, \"alpha\": 2.0, \"beta\": 1.0}\n";
        let combo_only_manifest = sign_manifest_bytes(&key, combo_only, 3, "https://example/combo-only.ndjson");
        apply_global_shared_priors(combo_only_manifest.as_bytes(), combo_only, &key.public_bytes, &marker)
            .expect("valid combo-only update must replace both stores");
        assert_eq!(global_shared_priors_len(), 1);
        assert_eq!(global_protocol_threat_priors_len(), 0);

        let tampered = b"{\"combo_hash\": 1, \"alpha\": 99.0, \"beta\": 4.0}\n";
        let err = apply_global_shared_priors(manifest.as_bytes(), tampered, &key.public_bytes, &marker)
            .expect_err("tampered apply must fail");
        assert!(matches!(err, ApplyError::Manifest(ManifestError::HashMismatch)));
        assert_eq!(global_shared_priors_len(), 1, "fail-secure: registry must keep the previously-applied entry");
        assert_eq!(
            global_protocol_threat_priors_len(),
            0,
            "fail-secure: registry must keep the previously-applied threat state"
        );
    }

    #[test]
    fn persisted_marker_rejects_older_signed_bundle_after_restart() {
        let temp = tempfile::tempdir().expect("marker directory");
        let marker = temp.path().join("release.json");
        let key = generate_test_key();
        let newer = b"{\"combo_hash\": 80, \"alpha\": 2.0, \"beta\": 1.0}\n";
        let older = b"{\"combo_hash\": 81, \"alpha\": 2.0, \"beta\": 1.0}\n";
        let newer_manifest = sign_manifest_bytes(&key, newer, 80, "https://example/new.ndjson");
        let older_manifest = sign_manifest_bytes(&key, older, 79, "https://example/old.ndjson");

        let mut first = RegistryState::default();
        publish_verified(
            apply_priors(newer_manifest.as_bytes(), newer, &key.public_bytes).expect("valid newer bundle"),
            &marker,
            &mut first,
        )
        .expect("newer signed bundle must apply");
        let mut restarted = RegistryState::default();
        let older_applied =
            apply_priors(older_manifest.as_bytes(), older, &key.public_bytes).expect("valid old signature");
        assert!(matches!(
            publish_verified(older_applied, &marker, &mut restarted),
            Err(ApplyError::Rollback { accepted: 80, incoming: 79 })
        ));
        assert!(restarted.priors.is_empty());

        publish_verified(
            apply_priors(newer_manifest.as_bytes(), newer, &key.public_bytes).expect("valid newer bundle"),
            &marker,
            &mut restarted,
        )
        .expect("same bundle can restore registry after restart");
        assert!(restarted.priors.contains_key(&80));

        let conflicting = sign_manifest_bytes(&key, older, 80, "https://example/conflict.ndjson");
        assert!(matches!(
            publish_verified(
                apply_priors(conflicting.as_bytes(), older, &key.public_bytes).expect("valid conflicting bundle"),
                &marker,
                &mut restarted,
            ),
            Err(ApplyError::ConflictingRelease(80))
        ));
        assert!(restarted.priors.contains_key(&80));
    }

    #[test]
    fn invalid_or_unwritable_marker_does_not_publish_priors() {
        let temp = tempfile::tempdir().expect("marker directory");
        let key = generate_test_key();
        let manifest = sign_manifest_bytes(&key, SAMPLE_PRIORS, 100, "https://example/priors.ndjson");
        let mut registry =
            RegistryState { priors: HashMap::from([(7, PriorParams { alpha: 2.0, beta: 1.0 })]), ..Default::default() };

        let corrupt = temp.path().join("corrupt.json");
        std::fs::write(&corrupt, b"not-json").expect("write invalid marker");
        let applied = apply_priors(manifest.as_bytes(), SAMPLE_PRIORS, &key.public_bytes).expect("valid bundle");
        assert!(matches!(publish_verified(applied, &corrupt, &mut registry), Err(ApplyError::Watermark(_))));
        assert!(registry.priors.contains_key(&7));
        assert_eq!(std::fs::read(&corrupt).expect("read invalid marker"), b"not-json");

        let blocker = temp.path().join("file");
        std::fs::write(&blocker, b"not a directory").expect("write blocker");
        let applied = apply_priors(manifest.as_bytes(), SAMPLE_PRIORS, &key.public_bytes).expect("valid bundle");
        assert!(matches!(
            publish_verified(applied, &blocker.join("marker.json"), &mut registry),
            Err(ApplyError::Watermark(_))
        ));
        assert!(registry.priors.contains_key(&7));
    }
}
