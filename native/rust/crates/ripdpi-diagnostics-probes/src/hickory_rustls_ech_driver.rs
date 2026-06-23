//! Real DNS + TLS ECH handshake driver backed by `hickory-resolver`.
//!
//! Implements [`EchHandshakeDriver`] using:
//!
//! - **DNS half**: `hickory_resolver::TokioAsyncResolver` with Cloudflare's
//!   default config, querying `RecordType::HTTPS` and extracting the
//!   `SvcParamKey::EchConfigList` value from SVCB/HTTPS resource records.
//!
//! - **TLS half**: real rustls 0.23 ECH handshake via
//!   [`rustls::client::EchConfig`] + [`tokio_rustls::TlsConnector`].
//!   ECH status is read from [`rustls::client::EchStatus`] after the
//!   handshake completes and mapped to [`EchHandshakeOutcome`].

use std::sync::Arc;
use std::time::Duration;

use hickory_resolver::{
    TokioResolver,
    config::{CLOUDFLARE, ResolverConfig, ResolverOpts},
    net::runtime::TokioRuntimeProvider,
    proto::rr::rdata::svcb::{SvcParamKey, SvcParamValue},
    proto::rr::{RData, RecordType},
};
use rustls::RootCertStore;
use rustls::client::{EchConfig, EchMode, EchStatus};
use rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES;
use rustls::pki_types::{EchConfigListBytes, ServerName};
use tokio_rustls::TlsConnector;

use crate::probes::ech_handshake::{EchHandshakeDriver, EchHandshakeOutcome};

/// Real ECH handshake driver using `hickory-resolver` for HTTPS RR lookup
/// and `rustls` 0.23 + `tokio-rustls` for the TLS handshake.
///
/// The DNS half resolves `HTTPS` resource records via Cloudflare's resolvers
/// and extracts the `ech=` (`SvcParamKey::EchConfigList`) parameter bytes.
///
/// The TLS half performs a genuine ECH-enabled TLS 1.3 handshake using
/// [`EchConfig::new`] with [`ALL_SUPPORTED_SUITES`] (aws-lc-rs HPKE suites)
/// and reads the outcome from [`EchStatus`] on the completed connection.
///
/// # ⚠️ Dormant — protect landmine (DIAG-3)
///
/// NOT dispatched by `ripdpi-monitor-engine` / `ripdpi-diagnostics-runner`.
/// Its TLS connect is **Direct + UNPROTECTED** (no `VpnService.protect`). Do
/// **not** wire it into the runner registry without first routing the connect
/// through the protect-aware `ripdpi-diagnostics-runner` seam, or it
/// reintroduces a DIAG-1/DIAG-2-class TUN-recursion loop when the VPN is up.
/// See `.claude/rules/vpnservice-protect-invariant.md`.
#[doc(hidden)]
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
                if *key == SvcParamKey::EchConfigList
                    && let SvcParamValue::EchConfigList(ech) = value
                {
                    return Ok(Some(ech.0.clone()));
                }
            }
        }

        Ok(None)
    }

    /// Perform a real TLS 1.3 handshake with ECH enabled via rustls 0.23.
    ///
    /// Steps:
    /// 1. Parse `ech_config` bytes into an [`EchConfig`] selecting a compatible
    ///    HPKE suite from [`ALL_SUPPORTED_SUITES`] (aws-lc-rs).
    /// 2. Build a [`rustls::ClientConfig`] with ECH enabled and the system
    ///    WebPKI roots via `webpki_roots`.
    /// 3. Open a TCP connection and drive the TLS handshake, both bounded by
    ///    `timeout`.
    /// 4. Inspect [`EchStatus`] on the completed connection and map to
    ///    [`EchHandshakeOutcome`].
    async fn handshake_with_ech(
        &self,
        host: &str,
        port: u16,
        ech_config: &[u8],
        timeout: Duration,
    ) -> EchHandshakeOutcome {
        // Step 1: parse ECHConfigList bytes.
        let ech_config_list = EchConfigListBytes::from(ech_config.to_vec());
        let ech_cfg = match EchConfig::new(ech_config_list, ALL_SUPPORTED_SUITES) {
            Ok(c) => c,
            Err(e) => {
                return EchHandshakeOutcome::SetupError { detail: format!("ech-config-parse-error: {e}") };
            }
        };

        // Step 2: build root store and ClientConfig with ECH.
        let mut root_store = RootCertStore::empty();
        root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

        let client_config =
            match rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::aws_lc_rs::default_provider()))
                .with_ech(EchMode::Enable(ech_cfg))
            {
                Ok(builder) => builder.with_root_certificates(root_store).with_no_client_auth(),
                Err(e) => {
                    return EchHandshakeOutcome::SetupError { detail: format!("ech-clientconfig-build-error: {e}") };
                }
            };

        // Step 3a: TCP connect with timeout.
        let tcp = match tokio::time::timeout(timeout, tokio::net::TcpStream::connect((host, port))).await {
            Err(_elapsed) => {
                return EchHandshakeOutcome::HandshakeFailure { detail: "tcp-connect-timeout".to_string() };
            }
            Ok(Err(e)) => {
                return EchHandshakeOutcome::HandshakeFailure { detail: format!("tcp-connect-error: {e}") };
            }
            Ok(Ok(stream)) => stream,
        };

        // Step 3b: parse ServerName.
        let server_name = match ServerName::try_from(host.to_string()) {
            Ok(n) => n,
            Err(e) => {
                return EchHandshakeOutcome::SetupError { detail: format!("server-name-parse-error: {e}") };
            }
        };

        // Step 3c: TLS handshake with timeout.
        let connector = TlsConnector::from(Arc::new(client_config));
        let tls = match tokio::time::timeout(timeout, connector.connect(server_name, tcp)).await {
            Err(_elapsed) => {
                return EchHandshakeOutcome::HandshakeFailure { detail: "tls-handshake-timeout".to_string() };
            }
            Ok(Err(e)) => {
                return EchHandshakeOutcome::HandshakeFailure { detail: format!("tls-handshake-error: {e}") };
            }
            Ok(Ok(stream)) => stream,
        };

        // Step 4: map ECH status to outcome.
        let (_io, conn) = tls.get_ref();
        let alpn = conn.alpn_protocol().and_then(|b| std::str::from_utf8(b).ok().map(String::from));

        match conn.ech_status() {
            EchStatus::Accepted => EchHandshakeOutcome::EchAccepted { selected_alpn: alpn },
            EchStatus::Rejected => EchHandshakeOutcome::EchRejectedWithRetryConfig,
            EchStatus::NotOffered | EchStatus::Grease | EchStatus::Offered => {
                EchHandshakeOutcome::HandshakeFailure { detail: "ech-not-negotiated".to_string() }
            }
        }
    }
}
