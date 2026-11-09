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

    /// Perform an ECH handshake and retry authenticated key rotation once.
    ///
    /// # Cancel safety:
    /// cancel-safe: each attempt owns its TCP and TLS state; dropping this future
    /// closes it and prevents the next attempt.
    async fn handshake_with_ech(
        &self,
        host: &str,
        port: u16,
        ech_config: &[u8],
        timeout: Duration,
    ) -> EchHandshakeOutcome {
        let Some(deadline) = tokio::time::Instant::now().checked_add(timeout) else {
            return EchHandshakeOutcome::SetupError { detail: "ech-timeout-out-of-range".into() };
        };
        let mut configs = ech_config.to_vec();
        let mut peer = None;
        for attempt in 0..2 {
            let config = match ech_client_config(configs) {
                Ok(config) => config,
                Err(detail) => return EchHandshakeOutcome::SetupError { detail },
            };
            let server_name = match ServerName::try_from(host.to_owned()) {
                Ok(name) => name,
                Err(error) => {
                    return EchHandshakeOutcome::SetupError { detail: format!("server-name-parse-error: {error}") };
                }
            };
            let connect = async {
                match peer {
                    Some(address) => tokio::net::TcpStream::connect(address).await,
                    None => tokio::net::TcpStream::connect((host, port)).await,
                }
            };
            let tcp = match tokio::time::timeout_at(deadline, connect).await {
                Ok(Ok(tcp)) => tcp,
                Ok(Err(error)) => {
                    return EchHandshakeOutcome::HandshakeFailure { detail: format!("tcp-connect-error: {error}") };
                }
                Err(_) => return EchHandshakeOutcome::HandshakeFailure { detail: "tcp-connect-timeout".into() },
            };
            peer = tcp.peer_addr().ok();
            let connector = TlsConnector::from(config);
            match tokio::time::timeout_at(deadline, connector.connect(server_name, tcp)).await {
                Err(_) => return EchHandshakeOutcome::HandshakeFailure { detail: "tls-handshake-timeout".into() },
                Ok(Err(error)) => {
                    let retry = error
                        .get_ref()
                        .and_then(|error| error.downcast_ref::<rustls::Error>())
                        .and_then(ripdpi_tls_profiles::ech::retry::rustls_ech_retry_configs);
                    if let Some(retry) = retry {
                        if attempt == 0 && peer.is_some() {
                            configs = retry;
                            continue;
                        }
                        return EchHandshakeOutcome::EchRejectedWithRetryConfig;
                    }
                    return EchHandshakeOutcome::HandshakeFailure { detail: format!("tls-handshake-error: {error}") };
                }
                Ok(Ok(tls)) => {
                    let conn = &tls.get_ref().1;
                    return match conn.ech_status() {
                        EchStatus::Accepted => EchHandshakeOutcome::EchAccepted {
                            selected_alpn: conn
                                .alpn_protocol()
                                .and_then(|value| std::str::from_utf8(value).ok().map(str::to_owned)),
                        },
                        _ => EchHandshakeOutcome::HandshakeFailure { detail: "ech-not-negotiated".into() },
                    };
                }
            }
        }
        EchHandshakeOutcome::HandshakeFailure { detail: "ech-retry-exhausted".into() }
    }
}

fn ech_client_config(configs: Vec<u8>) -> Result<Arc<rustls::ClientConfig>, String> {
    let config = EchConfig::new(EchConfigListBytes::from(configs), ALL_SUPPORTED_SUITES)
        .map_err(|error| format!("ech-config-parse-error: {error}"))?;
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let builder = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::aws_lc_rs::default_provider()))
        .with_ech(EchMode::Enable(config))
        .map_err(|error| format!("ech-clientconfig-build-error: {error}"))?;
    let builder = ripdpi_tls_profiles::cert_blocklist::with_blocklisted_roots(builder, roots)
        .map_err(|error| format!("verifier-build-error: {error}"))?;
    Ok(Arc::new(builder.with_no_client_auth()))
}
