use std::sync::{Arc, Once};

use ripdpi_tls_profiles::{EchFacadeError, EchSetup};
use rustls::{ClientConfig as RustlsClientConfig, RootCertStore};

static RUSTLS_PROVIDER: Once = Once::new();

pub(crate) fn default_tls_config() -> Arc<RustlsClientConfig> {
    tls_config_with_ech_setup(&EchSetup::Grease).expect("default NaiveProxy TLS config must support ECH GREASE")
}

pub(crate) fn tls_config_with_ech_setup(setup: &EchSetup) -> Result<Arc<RustlsClientConfig>, EchFacadeError> {
    ensure_rustls_provider();
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let mut config = ripdpi_tls_profiles::configure_rustls_ech(
        RustlsClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into()),
        setup,
    )?
    .with_root_certificates(roots)
    .with_no_client_auth();
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    Ok(Arc::new(config))
}

pub(crate) fn ensure_rustls_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        rustls::crypto::aws_lc_rs::default_provider().install_default().expect("install rustls aws-lc provider");
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_tls_profiles::EchSetup;

    #[test]
    fn naive_proxy_tls_config_uses_ech_facade_grease_for_outbound_tunnel() {
        let config = tls_config_with_ech_setup(&EchSetup::Grease).expect("ECH TLS config");

        assert_eq!(config.alpn_protocols, vec![b"h2".to_vec(), b"http/1.1".to_vec()]);
        assert!(rustls_client_hello_has_ech_extension(config), "NaiveProxy must send ECH or GREASE");
    }

    fn rustls_client_hello_has_ech_extension(config: Arc<RustlsClientConfig>) -> bool {
        let server_name = rustls::pki_types::ServerName::try_from("inner.example").expect("server name");
        let mut conn = rustls::ClientConnection::new(config, server_name).expect("client conn");
        let mut bytes = Vec::new();
        conn.write_tls(&mut bytes).expect("write ClientHello");
        let layout = ripdpi_packets::parse_tls_client_hello_layout(&bytes).expect("parse ClientHello");
        layout.extensions.iter().any(|extension| extension.ext_type == 0xfe0d)
    }
}
