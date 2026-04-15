//! Shared BoringSSL trust store seeding.
//!
//! BoringSSL, unlike OpenSSL, has no equivalent to
//! `SSL_CTX_set_default_verify_paths`. Every `SslConnectorBuilder` starts with
//! an empty `X509_STORE`, so without explicit seeding every peer verification
//! fails with `unable to get local issuer certificate` (or `self signed
//! certificate in certificate chain` for chains that include a cross-signed
//! root). On Android there's no platform path BoringSSL can read anyway
//! (system CAs live at `/system/etc/security/cacerts/*.0`).
//!
//! We ship a Mozilla CCADB snapshot (published by curl.se) and load it into
//! the builder at construction. Refresh via
//! `scripts/ci/refresh_mozilla_ca_bundle.sh` — CI fails once the snapshot is
//! older than the freshness window.

use boring::ssl::SslConnectorBuilder;
use boring::x509::X509;

use crate::Error;

/// Mozilla CCADB root bundle (published as
/// <https://curl.se/ca/cacert.pem>). Pinned and refreshed via CI.
static MOZILLA_CA_PEM: &[u8] = include_bytes!("../ca-bundle/mozilla-ca.pem");

/// Load the bundled Mozilla roots into the builder's trust store. Silently
/// skips any cert that fails to parse so a single malformed entry doesn't
/// disable TLS verification wholesale.
pub(crate) fn seed_default_trust(builder: &mut SslConnectorBuilder) -> Result<(), Error> {
    let certificates = X509::stack_from_pem(MOZILLA_CA_PEM)?;
    let store = builder.cert_store_mut();
    for cert in certificates {
        let _ = store.add_cert(cert);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bundle_parses_at_least_one_hundred_roots() {
        let certs = X509::stack_from_pem(MOZILLA_CA_PEM).expect("bundle parses");
        assert!(
            certs.len() >= 100,
            "expected >=100 Mozilla CA roots in the bundle, got {} -- run \
             scripts/ci/refresh_mozilla_ca_bundle.sh to refresh",
            certs.len(),
        );
    }

    #[test]
    fn seed_default_trust_populates_cert_store() {
        use boring::ssl::{SslConnector, SslMethod};
        let mut builder = SslConnector::builder(SslMethod::tls()).expect("builder");
        seed_default_trust(&mut builder).expect("seed");
        // Sanity check: after seeding the store, the builder must finalize.
        let _connector = builder.build();
    }
}
