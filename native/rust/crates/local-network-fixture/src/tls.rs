use std::io;
use std::sync::Arc;

use rcgen::generate_simple_self_signed;
use rustls::pki_types::PrivateKeyDer;
use rustls::ServerConfig;

use crate::types::FixtureConfig;
use crate::util;

pub(crate) struct TlsMaterial {
    pub(crate) certificate_pem: String,
    pub(crate) server_config: Arc<ServerConfig>,
}

impl TlsMaterial {
    pub(crate) fn generate(config: &FixtureConfig) -> io::Result<Self> {
        let certificate = generate_simple_self_signed(vec![
            config.fixture_domain.clone(),
            "localhost".to_string(),
            "127.0.0.1".to_string(),
        ])
        .map_err(util::other_io)?;
        let cert_der = certificate.cert.der().clone();
        let certificate_pem = certificate.cert.pem();
        let key_der = PrivateKeyDer::Pkcs8(certificate.signing_key.serialize_der().into());
        let server_config = Arc::new(
            ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .expect("ring provider supports default TLS versions")
                .with_no_client_auth()
                .with_single_cert(vec![cert_der], key_der)
                .map_err(util::other_io)?,
        );

        Ok(Self { certificate_pem, server_config })
    }
}
