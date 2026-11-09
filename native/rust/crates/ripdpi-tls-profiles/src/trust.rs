//! Shared BoringSSL trust store seeding.
//!
//! `SslConnectorBuilder` loads filesystem trust paths, including roots from
//! `SSL_CERT_FILE` and `SSL_CERT_DIR`. Replace that store so standard clients
//! trust only the bundled roots and roots that callers add explicitly.
//!
//! We ship a Mozilla CCADB snapshot (published by curl.se) and load it into
//! the builder at construction. Refresh via
//! `scripts/ci/refresh_mozilla_ca_bundle.sh` — CI fails once the snapshot is
//! older than the freshness window.

use std::sync::OnceLock;

use boring::ssl::SslConnectorBuilder;
use boring::x509::X509;
use boring::x509::store::X509StoreBuilder;

use crate::Error;

/// Mozilla CCADB root bundle (published as
/// <https://curl.se/ca/cacert.pem>). Pinned and refreshed via CI.
static MOZILLA_CA_PEM: &[u8] = include_bytes!("../ca-bundle/mozilla-ca.pem");

/// Replace implicit trust sources with the bundled Mozilla roots.
/// Callers can add private roots to the mutable store after this returns.
pub(crate) fn seed_default_trust(builder: &mut SslConnectorBuilder) -> Result<(), Error> {
    let mut store = X509StoreBuilder::new()?;
    for cert in mozilla_roots()? {
        store.add_cert(cert)?;
    }
    builder.set_cert_store_builder(store);
    Ok(())
}

static MOZILLA_ROOTS: OnceLock<Vec<X509>> = OnceLock::new();

/// The bundle parsed once per process. Every connection builds a fresh
/// `SslConnectorBuilder`, and a full parse costs milliseconds of CPU on the
/// calling async worker; each store addition only increments a reference count.
fn mozilla_roots() -> Result<&'static [X509], Error> {
    if let Some(roots) = MOZILLA_ROOTS.get() {
        return Ok(roots);
    }
    let parsed = X509::stack_from_pem(MOZILLA_CA_PEM)?;
    Ok(MOZILLA_ROOTS.get_or_init(|| parsed))
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
    fn bundle_is_parsed_once_per_process() {
        let first = mozilla_roots().expect("roots");
        let second = mozilla_roots().expect("roots");
        assert!(std::ptr::eq(first, second), "the second call must reuse the first parse");
        assert_eq!(first.len(), X509::stack_from_pem(MOZILLA_CA_PEM).expect("bundle parses").len());
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
