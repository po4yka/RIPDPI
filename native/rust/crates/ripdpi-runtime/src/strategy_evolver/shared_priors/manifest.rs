//! Manifest verification for shared-priors bundles.
//!
//! The runtime never trusts a priors payload it didn't verify. Verification
//! has two parts:
//!
//!   1. SHA-256 of the priors payload must match `priors_sha256` in the
//!      manifest.
//!   2. The manifest must carry an ed25519 signature over a canonical
//!      signing-input that binds (a) the priors hash, (b) the issuance
//!      timestamp, and (c) a domain-separation tag, signed with the
//!      project's release key.
//!
//! The signed message is structurally simple:
//!
//! ```text
//! signed_input = b"ripdpi-shared-priors-v1\0"  (24 bytes)
//!              || priors_sha256                (32 bytes)
//!              || issued_at_unix.to_le_bytes() (8 bytes)
//! ```
//!
//! Until a real release key is published, [`SHARED_PRIORS_PUB_KEY`] is the
//! all-zero placeholder; [`is_production_key_set`] returns `false` and
//! [`verify_manifest`] short-circuits with [`ManifestError::NoProductionKey`].
//! Production builds must replace the placeholder with the release public key.

use base64::Engine;
use ring::signature;

/// Currently the only supported manifest schema version.
pub const SHARED_PRIORS_MANIFEST_VERSION: u32 = 1;

/// ed25519 public key for the shared-priors release channel. All zeros is
/// the placeholder until the project's release-signing key is generated and
/// the public half is embedded here. Until then, [`is_production_key_set`]
/// reports `false` and [`verify_manifest`] rejects every signature with
/// [`ManifestError::NoProductionKey`].
pub const SHARED_PRIORS_PUB_KEY: [u8; 32] = [0u8; 32];

/// Domain-separation tag prefixing the signing input. Prevents cross-protocol
/// signature reuse — signatures intended for other RIPDPI release artefacts
/// will not validate against shared-priors verification.
pub const SIGNING_DOMAIN_TAG: &[u8; 24] = b"ripdpi-shared-priors-v1\0";

/// Manifest fields exposed after verification succeeds.
#[derive(Debug, Clone)]
pub struct SharedPriorsManifest {
    pub version: u32,
    pub priors_url: String,
    pub priors_sha256_hex: String,
    pub issued_at_unix: i64,
    pub signature_b64: String,
}

#[derive(Debug)]
pub enum ManifestError {
    InvalidJson(String),
    UnsupportedVersion(u32),
    BadHashFormat,
    BadSignatureFormat,
    HashMismatch,
    BadSignature,
    NoProductionKey,
}

impl std::fmt::Display for ManifestError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidJson(msg) => write!(f, "invalid manifest json: {msg}"),
            Self::UnsupportedVersion(v) => write!(f, "unsupported manifest version: {v}"),
            Self::BadHashFormat => write!(f, "priors_sha256 must be 64 hex chars"),
            Self::BadSignatureFormat => write!(f, "signature_ed25519 must decode to 64 bytes of base64"),
            Self::HashMismatch => write!(f, "priors payload sha256 did not match manifest"),
            Self::BadSignature => write!(f, "manifest signature did not verify"),
            Self::NoProductionKey => write!(f, "no production shared-priors public key embedded in this build"),
        }
    }
}

impl std::error::Error for ManifestError {}

#[derive(serde::Deserialize)]
struct ManifestWire {
    version: u32,
    priors_url: String,
    priors_sha256: String,
    issued_at_unix: i64,
    signature_ed25519: String,
}

/// Returns `true` when a non-placeholder ed25519 public key is embedded in
/// this build. Production code should treat `false` as "the shared-priors
/// channel is disabled in this release".
pub const fn is_production_key_set() -> bool {
    let mut i = 0;
    while i < 32 {
        if SHARED_PRIORS_PUB_KEY[i] != 0 {
            return true;
        }
        i += 1;
    }
    false
}

/// Verify a shared-priors manifest against the priors payload.
///
/// Pure function: on success returns the parsed manifest; on failure
/// returns the first encountered error without exposing intermediate state.
/// Fail-secure — callers can assume that if the embedded key is the
/// placeholder, this function rejects every input.
pub fn verify_manifest(
    manifest_bytes: &[u8],
    priors_bytes: &[u8],
    public_key: &[u8; 32],
) -> Result<SharedPriorsManifest, ManifestError> {
    let parsed: ManifestWire =
        serde_json::from_slice(manifest_bytes).map_err(|err| ManifestError::InvalidJson(err.to_string()))?;
    if parsed.version != SHARED_PRIORS_MANIFEST_VERSION {
        return Err(ManifestError::UnsupportedVersion(parsed.version));
    }

    let manifest_hash = decode_hex_32(&parsed.priors_sha256).ok_or(ManifestError::BadHashFormat)?;
    let computed_hash = sha256_32(priors_bytes);
    if computed_hash != manifest_hash {
        return Err(ManifestError::HashMismatch);
    }

    let signature_bytes = decode_base64_64(&parsed.signature_ed25519).ok_or(ManifestError::BadSignatureFormat)?;

    if public_key == &SHARED_PRIORS_PUB_KEY && !is_production_key_set() {
        return Err(ManifestError::NoProductionKey);
    }

    let signed_message = build_signing_input(&manifest_hash, parsed.issued_at_unix);
    let unparsed = signature::UnparsedPublicKey::new(&signature::ED25519, public_key.as_slice());
    unparsed.verify(&signed_message, &signature_bytes).map_err(|_| ManifestError::BadSignature)?;

    Ok(SharedPriorsManifest {
        version: parsed.version,
        priors_url: parsed.priors_url,
        priors_sha256_hex: parsed.priors_sha256,
        issued_at_unix: parsed.issued_at_unix,
        signature_b64: parsed.signature_ed25519,
    })
}

pub fn build_signing_input(priors_hash: &[u8; 32], issued_at_unix: i64) -> Vec<u8> {
    let mut buf = Vec::with_capacity(SIGNING_DOMAIN_TAG.len() + 32 + 8);
    buf.extend_from_slice(SIGNING_DOMAIN_TAG);
    buf.extend_from_slice(priors_hash);
    buf.extend_from_slice(&issued_at_unix.to_le_bytes());
    buf
}

pub fn sha256_32(bytes: &[u8]) -> [u8; 32] {
    let digest = ring::digest::digest(&ring::digest::SHA256, bytes);
    let mut out = [0u8; 32];
    out.copy_from_slice(digest.as_ref());
    out
}

fn decode_hex_32(s: &str) -> Option<[u8; 32]> {
    if s.len() != 64 {
        return None;
    }
    let bytes = s.as_bytes();
    let mut out = [0u8; 32];
    for (i, slot) in out.iter_mut().enumerate() {
        let hi = hex_digit(bytes[2 * i])?;
        let lo = hex_digit(bytes[2 * i + 1])?;
        *slot = (hi << 4) | lo;
    }
    Some(out)
}

fn hex_digit(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

fn decode_base64_64(s: &str) -> Option<[u8; 64]> {
    let decoded = base64::engine::general_purpose::STANDARD.decode(s).ok()?;
    if decoded.len() != 64 {
        return None;
    }
    let mut out = [0u8; 64];
    out.copy_from_slice(&decoded);
    Some(out)
}

#[cfg(test)]
pub mod test_support {
    use base64::Engine;
    use ring::rand::SystemRandom;
    use ring::signature::{Ed25519KeyPair, KeyPair};

    use super::{build_signing_input, sha256_32, SHARED_PRIORS_MANIFEST_VERSION};

    pub struct TestKey {
        pub keypair: Ed25519KeyPair,
        pub public_bytes: [u8; 32],
    }

    pub fn generate_test_key() -> TestKey {
        let rng = SystemRandom::new();
        let pkcs8 = Ed25519KeyPair::generate_pkcs8(&rng).expect("ed25519 keygen");
        let keypair = Ed25519KeyPair::from_pkcs8(pkcs8.as_ref()).expect("ed25519 parse");
        let public_bytes_slice = keypair.public_key().as_ref();
        let mut public_bytes = [0u8; 32];
        public_bytes.copy_from_slice(public_bytes_slice);
        TestKey { keypair, public_bytes }
    }

    pub fn sign_manifest_bytes(key: &TestKey, priors_payload: &[u8], issued_at_unix: i64, priors_url: &str) -> String {
        let priors_hash = sha256_32(priors_payload);
        let signed_input = build_signing_input(&priors_hash, issued_at_unix);
        let signature = key.keypair.sign(&signed_input);
        let signature_b64 = base64::engine::general_purpose::STANDARD.encode(signature.as_ref());
        let priors_sha256_hex = priors_hash.iter().fold(String::with_capacity(64), |mut acc, byte| {
            acc.push_str(&format!("{byte:02x}"));
            acc
        });
        format!(
            "{{\"version\":{version},\"priors_url\":\"{url}\",\"priors_sha256\":\"{hash}\",\"issued_at_unix\":{ts},\"signature_ed25519\":\"{sig}\"}}",
            version = SHARED_PRIORS_MANIFEST_VERSION,
            url = priors_url,
            hash = priors_sha256_hex,
            ts = issued_at_unix,
            sig = signature_b64,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use test_support::{generate_test_key, sign_manifest_bytes};

    const SAMPLE_PRIORS: &[u8] = b"{\"combo_hash\": 1, \"alpha\": 12.0, \"beta\": 4.0}\n";

    #[test]
    fn manifest_verifies_with_matching_signature() {
        let key = generate_test_key();
        let manifest = sign_manifest_bytes(&key, SAMPLE_PRIORS, 1_745_798_400, "https://example/priors.ndjson");
        let parsed = verify_manifest(manifest.as_bytes(), SAMPLE_PRIORS, &key.public_bytes)
            .expect("manifest with valid signature should verify");
        assert_eq!(parsed.version, SHARED_PRIORS_MANIFEST_VERSION);
        assert_eq!(parsed.issued_at_unix, 1_745_798_400);
        assert_eq!(parsed.priors_url, "https://example/priors.ndjson");
    }

    #[test]
    fn manifest_rejects_tampered_payload() {
        let key = generate_test_key();
        let manifest = sign_manifest_bytes(&key, SAMPLE_PRIORS, 1_745_798_400, "https://example/priors.ndjson");
        let tampered = b"{\"combo_hash\": 1, \"alpha\": 99.0, \"beta\": 4.0}\n";
        let err = verify_manifest(manifest.as_bytes(), tampered, &key.public_bytes)
            .expect_err("tampered payload should fail");
        assert!(matches!(err, ManifestError::HashMismatch), "got {err:?}");
    }

    #[test]
    fn manifest_rejects_signature_under_wrong_key() {
        let key_a = generate_test_key();
        let key_b = generate_test_key();
        let manifest = sign_manifest_bytes(&key_a, SAMPLE_PRIORS, 1_745_798_400, "https://example/priors.ndjson");
        let err = verify_manifest(manifest.as_bytes(), SAMPLE_PRIORS, &key_b.public_bytes)
            .expect_err("signature under a different key must not verify");
        assert!(matches!(err, ManifestError::BadSignature), "got {err:?}");
    }

    #[test]
    fn manifest_rejects_unsupported_version() {
        let manifest = b"{\"version\":99,\"priors_url\":\"u\",\"priors_sha256\":\"00\",\"issued_at_unix\":0,\"signature_ed25519\":\"\"}";
        let err = verify_manifest(manifest, b"", &[0u8; 32]).expect_err("v99 is unsupported");
        assert!(matches!(err, ManifestError::UnsupportedVersion(99)), "got {err:?}");
    }

    #[test]
    fn manifest_rejects_invalid_json() {
        let err = verify_manifest(b"not json", b"", &[0u8; 32]).expect_err("non-json must fail");
        assert!(matches!(err, ManifestError::InvalidJson(_)), "got {err:?}");
    }

    #[test]
    fn placeholder_public_key_short_circuits_to_no_production_key() {
        // The all-zero placeholder must always reject, even when the rest
        // of the manifest verifies. This is the production guard for
        // builds that have not embedded a real signing key yet.
        let key = generate_test_key();
        let manifest = sign_manifest_bytes(&key, SAMPLE_PRIORS, 1_745_798_400, "https://example/priors.ndjson");
        let err = verify_manifest(manifest.as_bytes(), SAMPLE_PRIORS, &SHARED_PRIORS_PUB_KEY)
            .expect_err("placeholder key must reject");
        assert!(matches!(err, ManifestError::NoProductionKey), "got {err:?}");
    }

    #[test]
    fn manifest_rejects_truncated_signature() {
        // SHA-256 of an empty payload, so the hash check passes and we reach
        // the signature-format check. 44-char base64 (with == padding) decodes
        // to 32 bytes — short of the required 64-byte ed25519 signature.
        let empty_sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        let manifest = format!(
            "{{\"version\":1,\"priors_url\":\"u\",\"priors_sha256\":\"{empty_sha256}\",\"issued_at_unix\":0,\"signature_ed25519\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==\"}}"
        );
        let err = verify_manifest(manifest.as_bytes(), b"", &[1u8; 32]).expect_err("short signature must fail");
        assert!(matches!(err, ManifestError::BadSignatureFormat), "got {err:?}");
    }
}
