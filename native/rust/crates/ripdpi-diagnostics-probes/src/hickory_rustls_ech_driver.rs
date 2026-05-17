// PARENT-INTEGRATION:
//
// 1. Add to ripdpi-diagnostics-probes/Cargo.toml [dependencies]:
//      hickory-resolver = { workspace = true }
//    (already pinned in workspace as version = "0.26" with features
//     ["tls-ring", "https-ring", "tokio", "webpki-roots"])
//
//    tokio features used: "rt", "time" — already present in the crate's
//    dep declaration.
//
// 2. Add to lib.rs:
//      pub mod hickory_rustls_ech_driver;
//      pub use hickory_rustls_ech_driver::HickoryRustlsEchHandshakeDriver;
//
// 3. rustls ECH: the `handshake_with_ech` method intentionally returns
//    SetupError until the workspace enables the `unstable-ech` Cargo feature
//    on rustls 0.23. To wire it:
//      - In workspace Cargo.toml add:
//          [dependencies.rustls]
//          features = ["unstable-ech"]
//      - Replace the SetupError body below with a real ClientConfig::ech_mode
//        call. The ECH API may change between rustls minor versions; schedule
//        an audit after each rustls bump.

//! Real DNS + TLS ECH handshake driver backed by `hickory-resolver`.
//!
//! Implements [`EchHandshakeDriver`] using:
//!
//! - **DNS half**: `hickory_resolver::TokioAsyncResolver` with Cloudflare's
//!   default config, querying `RecordType::HTTPS` and extracting the
//!   `SvcParamKey::EchConfigList` value from SVCB/HTTPS resource records.
//!
//! - **TLS half**: stub returning [`EchHandshakeOutcome::SetupError`] until
//!   rustls `unstable-ech` is enabled workspace-wide (see `PARENT-INTEGRATION`
//!   comment at the top of this file).

use std::time::Duration;

use hickory_resolver::{
    config::{ResolverConfig, ResolverOpts, CLOUDFLARE},
    net::runtime::TokioRuntimeProvider,
    proto::rr::rdata::svcb::{SvcParamKey, SvcParamValue},
    proto::rr::{RData, RecordType},
    TokioResolver,
};

use crate::ech_handshake::{EchHandshakeDriver, EchHandshakeOutcome};

/// Real ECH handshake driver using `hickory-resolver` for HTTPS RR lookup.
///
/// The DNS half resolves `HTTPS` resource records via Cloudflare's resolvers
/// and extracts the `ech=` (`SvcParamKey::EchConfigList`) parameter bytes.
///
/// The TLS half is a documented stub: it returns
/// [`EchHandshakeOutcome::SetupError`] with detail
/// `"rustls-ech-feature-not-enabled"` until the workspace enables the
/// `unstable-ech` feature on rustls 0.23 (see module-level comment).
pub struct HickoryRustlsEchHandshakeDriver {
    /// Maximum wall-clock time allowed for each HTTPS RR lookup.
    ///
    /// Defaults to 5 seconds. The hickory resolver respects this via
    /// [`ResolverOpts::timeout`]; DNS retries are bounded accordingly.
    pub lookup_timeout: Duration,
}

impl HickoryRustlsEchHandshakeDriver {
    /// Construct a driver with the default 5-second lookup timeout.
    pub fn new() -> Self {
        Self { lookup_timeout: Duration::from_secs(5) }
    }
}

impl Default for HickoryRustlsEchHandshakeDriver {
    fn default() -> Self {
        Self::new()
    }
}

impl EchHandshakeDriver for HickoryRustlsEchHandshakeDriver {
    /// Resolve the HTTPS RR for `host` and return the raw ECHConfigList bytes.
    ///
    /// Uses `hickory_resolver::TokioAsyncResolver` with Cloudflare's default
    /// config (`ResolverConfig::cloudflare()`). Walks every returned HTTPS
    /// record's `svc_params` and returns the first `EchConfigList` value found.
    ///
    /// # Return values
    ///
    /// - `Ok(Some(bytes))` — ECH config bytes extracted from the first matching
    ///   HTTPS RR.
    /// - `Ok(None)` — HTTPS RR(s) present but none carried an `EchConfigList`
    ///   param; also returned when no HTTPS RR exists at all.
    /// - `Err(detail)` — Lookup failed (timeout, network unreachable, malformed
    ///   response). `detail` is a short string safe to embed in a verdict reason.
    async fn lookup_ech_config(&self, host: &str) -> Result<Option<Vec<u8>>, String> {
        let mut opts = ResolverOpts::default();
        opts.timeout = self.lookup_timeout;

        let resolver = TokioResolver::builder_with_config(
            ResolverConfig::udp_and_tcp(&CLOUDFLARE),
            TokioRuntimeProvider::default(),
        )
        .with_options(opts)
        .build()
        .map_err(|e| format!("hickory-build-error: {e}"))?;

        let lookup: hickory_resolver::lookup::Lookup =
            resolver.lookup(host, RecordType::HTTPS).await.map_err(|e| format!("hickory-lookup-error: {e}"))?;

        for record in lookup.answers() {
            let rdata: &RData = &record.data;
            let svc_params = match rdata {
                RData::HTTPS(https) => &https.0.svc_params,
                RData::SVCB(svcb) => &svcb.svc_params,
                _ => continue,
            };

            for (key, value) in svc_params {
                if *key == SvcParamKey::EchConfigList {
                    if let SvcParamValue::EchConfigList(ech) = value {
                        return Ok(Some(ech.0.clone()));
                    }
                }
            }
        }

        Ok(None)
    }

    /// Perform a TLS handshake with ECH enabled.
    ///
    /// # Current status
    ///
    /// This method is a documented integration hook. rustls 0.23 ECH support
    /// lives behind the `unstable-ech` Cargo feature which is not yet enabled
    /// in this workspace. Until it is, this method always returns
    /// [`EchHandshakeOutcome::SetupError`] with detail
    /// `"rustls-ech-feature-not-enabled"`.
    ///
    /// See the `PARENT-INTEGRATION` comment at the top of this module for the
    /// steps required to wire the real handshake.
    async fn handshake_with_ech(
        &self,
        _host: &str,
        _port: u16,
        _ech_config: &[u8],
        _timeout: Duration,
    ) -> EchHandshakeOutcome {
        // PARENT-INTEGRATION: replace this body once [dependencies.rustls]
        // features = ["unstable-ech"] is added to the workspace Cargo.toml.
        // Use rustls::ClientConfig::builder_with_provider(...)
        //     .with_ech(EchMode::Enable(EchConfig::new(...)))
        //     .with_root_certificates(...) to build the config, then drive
        // the handshake with tokio-rustls::TlsConnector.
        EchHandshakeOutcome::SetupError { detail: "rustls-ech-feature-not-enabled".to_string() }
    }
}
