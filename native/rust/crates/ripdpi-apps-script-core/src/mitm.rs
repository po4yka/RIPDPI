use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::sync::Arc;

use rcgen::{
    BasicConstraints, CertificateParams, DistinguishedName, DnType, ExtendedKeyUsagePurpose, IsCa, Issuer, KeyPair,
    KeyUsagePurpose, SanType,
};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::ServerConfig;

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
    ca_key_pair: KeyPair,
    cache: HashMap<String, Arc<ServerConfig>>,
}

impl MitmCertManager {
    pub fn new_in(base_dir: &Path) -> Result<Self, MitmError> {
        let ca_dir = base_dir.join(CA_DIR);
        let ca_key_path = base_dir.join(CA_KEY_FILE);
        let ca_cert_path = base_dir.join(CA_CERT_FILE);

        if ca_key_path.exists() && ca_cert_path.exists() {
            Self::load(&ca_key_path, &ca_cert_path)
        } else {
            fs::create_dir_all(&ca_dir)?;
            Self::generate(&ca_key_path, &ca_cert_path)
        }
    }

    pub fn get_server_config(&mut self, domain: &str) -> Result<Arc<ServerConfig>, MitmError> {
        if let Some(config) = self.cache.get(domain) {
            return Ok(config.clone());
        }

        let (leaf_der, leaf_key_der) = self.issue_leaf(domain)?;
        let chain = vec![leaf_der, self.ca_cert_der.clone()];
        let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(leaf_key_der));

        let mut config = ServerConfig::builder().with_no_client_auth().with_single_cert(chain, key)?;
        config.alpn_protocols = vec![b"http/1.1".to_vec()];

        let config = Arc::new(config);
        self.cache.insert(domain.to_string(), config.clone());
        Ok(config)
    }

    fn load(key_path: &Path, cert_path: &Path) -> Result<Self, MitmError> {
        let key_pem = fs::read_to_string(key_path)?;
        let cert_pem = fs::read_to_string(cert_path)?;

        let ca_key_pair = KeyPair::from_pem(&key_pem)?;
        let mut cert_bytes = cert_pem.as_bytes();
        let mut certs = rustls_pemfile::certs(&mut cert_bytes).collect::<Result<Vec<_>, _>>()?;
        let ca_cert_der =
            certs.drain(..1).next().ok_or_else(|| MitmError::Invalid("missing CA certificate".to_string()))?;
        Ok(Self { ca_cert_der, ca_params: build_ca_params(), ca_key_pair, cache: HashMap::new() })
    }

    fn generate(key_path: &Path, cert_path: &Path) -> Result<Self, MitmError> {
        let params = build_ca_params();
        let ca_key_pair = KeyPair::generate()?;
        let ca_cert = params.self_signed(&ca_key_pair)?;

        fs::write(cert_path, ca_cert.pem())?;
        fs::write(key_path, ca_key_pair.serialize_pem())?;

        Ok(Self { ca_cert_der: ca_cert.der().clone(), ca_params: params, ca_key_pair, cache: HashMap::new() })
    }

    fn issue_leaf(&self, domain: &str) -> Result<(CertificateDer<'static>, Vec<u8>), MitmError> {
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

        let leaf_key = KeyPair::generate()?;
        let issuer = Issuer::from_params(&self.ca_params, &self.ca_key_pair);
        let leaf = params.signed_by(&leaf_key, &issuer)?;
        Ok((leaf.der().clone(), leaf_key.serialize_der()))
    }
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

    #[test]
    fn generates_and_reloads_ca_material() {
        let temp_dir = std::env::temp_dir().join(format!("ripdpi-mitm-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&temp_dir);
        let manager = MitmCertManager::new_in(&temp_dir).expect("generate");
        drop(manager);
        let mut manager = MitmCertManager::new_in(&temp_dir).expect("reload");
        let config = manager.get_server_config("example.com").expect("server config");
        assert_eq!(config.alpn_protocols, vec![b"http/1.1".to_vec()]);
        let _ = fs::remove_dir_all(&temp_dir);
    }
}
