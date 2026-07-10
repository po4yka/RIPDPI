use std::io;

use url::Url;

use crate::auth::build_static_auth_header;
use crate::config::{MasqueAuthMode, MasqueConfig};
use crate::tls::{apply_h2_client_auth, load_client_identity};
use crate::url::parse_proxy_origin;

pub(crate) fn validate_config(config: &MasqueConfig) -> io::Result<()> {
    if let Some(mode) = config.auth_mode.as_deref().map(str::trim).filter(|value| !value.is_empty()) {
        match mode.to_ascii_lowercase().as_str() {
            "bearer" | "token" | "preshared" | "privacy_pass" | "cloudflare_mtls" => {}
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!("unsupported MASQUE auth mode {mode:?}"),
                ));
            }
        }
    }
    let _ = parse_proxy_origin(config)?;
    let _ = build_static_auth_header(config)?;
    match config.effective_auth_mode() {
        MasqueAuthMode::CloudflareMtls => {
            let _ = load_client_identity(config)?;
            if config.use_http2_fallback {
                let mut builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
                    .map_err(|error| io::Error::other(format!("failed to build H2 TLS profile: {error}")))?;
                apply_h2_client_auth(&mut builder, config)?;
            }
        }
        MasqueAuthMode::PrivacyPass => {
            let provider_url =
                config.privacy_pass_provider_url.as_ref().filter(|value| !value.trim().is_empty()).ok_or_else(
                    || {
                        io::Error::new(
                            io::ErrorKind::InvalidInput,
                            "MASQUE privacy_pass mode requires a deployer-supplied token provider URL",
                        )
                    },
                )?;
            let parsed = Url::parse(provider_url).map_err(|error| {
                io::Error::new(io::ErrorKind::InvalidInput, format!("invalid Privacy Pass provider URL: {error}"))
            })?;
            let is_https = parsed.scheme() == "https";
            let is_loopback_http = parsed.scheme() == "http"
                && parsed.host_str().is_some_and(|host| matches!(host, "127.0.0.1" | "localhost" | "::1"));
            if !(is_https || is_loopback_http) {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "Privacy Pass provider URL must use https unless it targets loopback for local testing",
                ));
            }
        }
        MasqueAuthMode::None | MasqueAuthMode::Bearer | MasqueAuthMode::Preshared => {}
    }
    Ok(())
}
