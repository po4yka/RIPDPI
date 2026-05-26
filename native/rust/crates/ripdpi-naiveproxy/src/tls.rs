use std::sync::{Arc, Once};

use rustls::{ClientConfig as RustlsClientConfig, RootCertStore};

static RUSTLS_PROVIDER: Once = Once::new();

pub(crate) fn default_tls_config() -> Arc<RustlsClientConfig> {
    ensure_rustls_provider();
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let mut config = RustlsClientConfig::builder().with_root_certificates(roots).with_no_client_auth();
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    Arc::new(config)
}

pub(crate) fn ensure_rustls_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        rustls::crypto::aws_lc_rs::default_provider().install_default().expect("install rustls aws-lc provider");
    });
}
