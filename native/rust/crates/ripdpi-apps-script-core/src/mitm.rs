use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use rcgen::{
    BasicConstraints, CertificateParams, DistinguishedName, DnType, ExtendedKeyUsagePurpose, IsCa, Issuer, KeyPair,
    KeyUsagePurpose, PublicKeyData, SanType,
};
use rustls::ServerConfig;
use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use tokio::sync::Mutex;

#[derive(Debug, thiserror::Error)]
pub enum MitmError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("rcgen: {0}")]
    Rcgen(#[from] rcgen::Error),
    #[error("rustls: {0}")]
    Rustls(#[from] rustls::Error),
    #[error("invalid certificate material: {0}")]
    Invalid(String),
}

const CERT_NAME: &str = "RIPDPI Apps Script Relay";
const CA_DIR: &str = "relay-ca";
const CA_KEY_FILE: &str = "relay-ca/ca.key";
const CA_CERT_FILE: &str = "relay-ca/ca.crt";

pub struct MitmCertManager {
    ca_cert_der: CertificateDer<'static>,
    ca_params: CertificateParams,
    ca_key_pem: String,
    cache: HashMap<String, Arc<ServerConfig>>,
}

impl MitmCertManager {
    pub fn new_in(base_dir: &Path) -> Result<Self, MitmError> {
        let _ = rustls::crypto::ring::default_provider().install_default();

        let ca_dir = base_dir.join(CA_DIR);
        let ca_key_path = base_dir.join(CA_KEY_FILE);
        let ca_cert_path = base_dir.join(CA_CERT_FILE);

        if ca_key_path.exists() && ca_cert_path.exists() {
            match Self::load(&ca_key_path, &ca_cert_path) {
                Ok(manager) => return Ok(manager),
                // A torn write (process killed mid-write) or two racing
                // generators can leave unusable or mismatched files. Without
                // a new CA the relay could never start again.
                Err(error) => tracing::warn!(
                    ring = "relay",
                    subsystem = "relay",
                    source = "relay",
                    kind = "mitm_ca_regenerated",
                    "stored Apps Script MITM CA is unusable, generating a new one: {error}"
                ),
            }
        }
        fs::create_dir_all(&ca_dir)?;
        Self::generate(&ca_key_path, &ca_cert_path)
    }

    /// Returns the per-domain leaf `ServerConfig`, generating and signing it on
    /// first use.
    ///
    /// The CPU-bound rcgen key generation and signing run inside
    /// `tokio::task::spawn_blocking` so they never block the async runtime, and
    /// the manager lock is held only for the (fast) cache lookups before and
    /// after — never across the signing work. See
    /// `.claude/rules/llm-rust-prompts.md` and the `rust-async-internals`
    /// skill: no Mutex is held across CPU-bound work.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the only awaits are the two short mutex acquisitions and the
    /// spawn_blocking join. Cancellation at any await point leaves only the
    /// (idempotent) per-domain cache untouched or populated by a racing task;
    /// no partial external state is produced.
    pub async fn get_server_config(manager: &Arc<Mutex<Self>>, domain: &str) -> Result<Arc<ServerConfig>, MitmError> {
        // Fast path: take a short lock, return a cached config, and clone the
        // CA material the blocking task needs as Send + 'static inputs.
        let (ca_cert_der, ca_params, ca_key_pem) = {
            let guard = manager.lock().await;
            if let Some(config) = guard.cache.get(domain) {
                return Ok(config.clone());
            }
            (guard.ca_cert_der.clone(), guard.ca_params.clone(), guard.ca_key_pem.clone())
        };

        // CPU-bound key generation + signing off the async runtime, with no
        // lock held.
        let domain_owned = domain.to_string();
        let config = tokio::task::spawn_blocking(move || -> Result<Arc<ServerConfig>, MitmError> {
            let (leaf_der, leaf_key_der) = issue_leaf(&ca_params, &ca_key_pem, &domain_owned)?;
            build_server_config(leaf_der, leaf_key_der, ca_cert_der)
        })
        .await
        .map_err(|error| MitmError::Invalid(format!("cert signing task failed: {error}")))??;

        // Re-lock only to insert; adopt a racing task's config if one landed
        // first so all callers share a single cached config per domain.
        let mut guard = manager.lock().await;
        let stored = guard.cache.entry(domain.to_string()).or_insert(config).clone();
        Ok(stored)
    }

    fn load(key_path: &Path, cert_path: &Path) -> Result<Self, MitmError> {
        let ca_key_pem = fs::read_to_string(key_path)?;
        // Validate the stored key material eagerly so a corrupt CA key fails at
        // construction rather than on the first signing task.
        let ca_key_pair = KeyPair::from_pem(&ca_key_pem)?;
        let ca_cert_der = CertificateDer::from_pem_file(cert_path)
            .map_err(|error| MitmError::Invalid(format!("failed to load CA certificate: {error}")))?;
        // The DER certificate embeds its SubjectPublicKeyInfo verbatim, so a
        // certificate for another key does not contain this byte sequence.
        let spki = ca_key_pair.subject_public_key_info();
        if !ca_cert_der.windows(spki.len()).any(|window| window == spki.as_slice()) {
            return Err(MitmError::Invalid("CA certificate does not match the CA key".to_string()));
        }
        Ok(Self { ca_cert_der, ca_params: build_ca_params(), ca_key_pem, cache: HashMap::new() })
    }

    fn generate(key_path: &Path, cert_path: &Path) -> Result<Self, MitmError> {
        let params = build_ca_params();
        let ca_key_pair = KeyPair::generate()?;
        let ca_cert = params.self_signed(&ca_key_pair)?;

        let ca_key_pem = ca_key_pair.serialize_pem();
        // Key first, certificate last: a crash between the two leaves no
        // certificate, so the next start generates a new pair.
        write_durably(key_path, ca_key_pem.as_bytes())?;
        write_durably(cert_path, ca_cert.pem().as_bytes())?;

        Ok(Self { ca_cert_der: ca_cert.der().clone(), ca_params: params, ca_key_pem, cache: HashMap::new() })
    }
}

/// Replaces `path` with `contents` through an fsynced temporary file and a
/// rename, so a reader never sees a partly written file.
fn write_durably(path: &Path, contents: &[u8]) -> std::io::Result<()> {
    // A unique temporary name keeps two racing generators from writing into
    // one file. Ordering: the counter only makes names unique.
    static NEXT_TEMP_ID: AtomicU64 = AtomicU64::new(0);
    let temp_id = NEXT_TEMP_ID.fetch_add(1, Ordering::Relaxed);
    let mut temp_name = path.as_os_str().to_owned();
    temp_name.push(format!(".{}.{temp_id}.tmp", std::process::id()));
    let temp_path = std::path::PathBuf::from(temp_name);

    let result = (|| {
        let mut file = fs::File::create(&temp_path)?;
        file.write_all(contents)?;
        file.sync_all()?;
        fs::rename(&temp_path, path)?;
        if let Some(parent) = path.parent() {
            fs::File::open(parent)?.sync_all()?;
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp_path);
    }
    result
}

/// Generates a fresh leaf key pair and signs a leaf certificate for `domain`
/// under the CA described by `ca_params` / `ca_key_pem`. CPU-bound; intended to
/// run inside `spawn_blocking`.
fn issue_leaf(
    ca_params: &CertificateParams,
    ca_key_pem: &str,
    domain: &str,
) -> Result<(CertificateDer<'static>, Vec<u8>), MitmError> {
    let mut params = CertificateParams::default();
    let mut distinguished_name = DistinguishedName::new();
    distinguished_name.push(DnType::CommonName, domain);
    params.distinguished_name = distinguished_name;
    params.subject_alt_names.push(SanType::DnsName(
        domain
            .try_into()
            .map_err(|error: rcgen::Error| MitmError::Invalid(format!("invalid dns name {domain}: {error}")))?,
    ));
    params.key_usages = vec![KeyUsagePurpose::DigitalSignature, KeyUsagePurpose::KeyEncipherment];
    params.extended_key_usages = vec![ExtendedKeyUsagePurpose::ServerAuth];
    let now = time::OffsetDateTime::now_utc();
    params.not_before = now - time::Duration::minutes(5);
    params.not_after = now + time::Duration::days(365);

    let ca_key_pair = KeyPair::from_pem(ca_key_pem)?;
    let leaf_key = KeyPair::generate()?;
    let issuer = Issuer::from_params(ca_params, &ca_key_pair);
    let leaf = params.signed_by(&leaf_key, &issuer)?;
    Ok((leaf.der().clone(), leaf_key.serialize_der()))
}

/// Builds the per-domain `ServerConfig` (leaf + CA chain, http/1.1 ALPN).
fn build_server_config(
    leaf_der: CertificateDer<'static>,
    leaf_key_der: Vec<u8>,
    ca_cert_der: CertificateDer<'static>,
) -> Result<Arc<ServerConfig>, MitmError> {
    let chain = vec![leaf_der, ca_cert_der];
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(leaf_key_der));

    let mut config = ServerConfig::builder().with_no_client_auth().with_single_cert(chain, key)?;
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(Arc::new(config))
}

fn build_ca_params() -> CertificateParams {
    let mut params = CertificateParams::default();
    let mut distinguished_name = DistinguishedName::new();
    distinguished_name.push(DnType::CommonName, CERT_NAME);
    distinguished_name.push(DnType::OrganizationName, CERT_NAME);
    params.distinguished_name = distinguished_name;
    params.is_ca = IsCa::Ca(BasicConstraints::Constrained(0));
    params.key_usages = vec![KeyUsagePurpose::DigitalSignature, KeyUsagePurpose::KeyCertSign, KeyUsagePurpose::CrlSign];
    let now = time::OffsetDateTime::now_utc();
    params.not_before = now - time::Duration::minutes(5);
    params.not_after = now + time::Duration::days(3650);
    params
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn generates_and_reloads_ca_material() {
        let temp_dir = std::env::temp_dir().join(format!("ripdpi-mitm-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&temp_dir);
        let manager = MitmCertManager::new_in(&temp_dir).expect("generate");
        drop(manager);
        let manager = Arc::new(Mutex::new(MitmCertManager::new_in(&temp_dir).expect("reload")));
        let config = MitmCertManager::get_server_config(&manager, "example.com").await.expect("server config");
        assert_eq!(config.alpn_protocols, vec![b"http/1.1".to_vec()]);
        // Second call returns the cached config for the same domain.
        let cached = MitmCertManager::get_server_config(&manager, "example.com").await.expect("cached config");
        assert!(Arc::ptr_eq(&config, &cached));
        let _ = fs::remove_dir_all(&temp_dir);
    }

    fn fresh_dir(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("ripdpi-mitm-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn truncated_ca_key_is_replaced_by_a_new_ca() {
        let dir = fresh_dir("truncated-key");
        drop(MitmCertManager::new_in(&dir).expect("generate"));
        let key_path = dir.join(CA_KEY_FILE);
        let key = fs::read(&key_path).expect("read key");
        fs::write(&key_path, &key[..key.len() / 2]).expect("truncate key");
        let old_cert = fs::read(dir.join(CA_CERT_FILE)).expect("read cert");

        drop(MitmCertManager::new_in(&dir).expect("start after a torn key write"));

        let new_cert = fs::read(dir.join(CA_CERT_FILE)).expect("read new cert");
        assert_ne!(old_cert, new_cert, "a new CA must replace the unusable one");
        drop(MitmCertManager::new_in(&dir).expect("reload the new CA"));
        assert_eq!(new_cert, fs::read(dir.join(CA_CERT_FILE)).expect("cert after reload"), "a valid CA is kept");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn ca_certificate_for_another_key_is_rejected() {
        let dir = fresh_dir("mismatch");
        let other = fresh_dir("mismatch-other");
        drop(MitmCertManager::new_in(&dir).expect("generate"));
        drop(MitmCertManager::new_in(&other).expect("generate other"));
        fs::copy(other.join(CA_KEY_FILE), dir.join(CA_KEY_FILE)).expect("mix key from another CA");

        let error =
            MitmCertManager::load(&dir.join(CA_KEY_FILE), &dir.join(CA_CERT_FILE)).err().expect("mismatch rejected");
        assert!(matches!(error, MitmError::Invalid(_)), "unexpected error: {error}");
        let _ = fs::remove_dir_all(&dir);
        let _ = fs::remove_dir_all(&other);
    }
}
