use boring::ssl::{SslConnector, SslConnectorBuilder, SslMethod, SslVerifyMode};
use thiserror::Error;

mod apply;
mod chrome;
mod firefox;
mod profile;

pub use profile::ProfileConfig;

#[derive(Debug, Error)]
pub enum Error {
    #[error("BoringSSL error: {0}")]
    Ssl(#[from] boring::error::ErrorStack),
}

/// Returns a configured builder that the caller can further customize before
/// calling `.build()`. Use this when you need to set additional options
/// (e.g., custom verify callback, session_id).
pub fn configure_builder(profile: &str) -> Result<SslConnectorBuilder, Error> {
    let mut builder = SslConnector::builder(SslMethod::tls())?;
    let config = profile::lookup_profile(profile);
    apply::apply_profile(&mut builder, config)?;
    Ok(builder)
}

/// Returns a finalized `SslConnector` for synchronous TLS connections.
pub fn build_connector(profile: &str, verify: bool) -> Result<SslConnector, Error> {
    let mut builder = configure_builder(profile)?;
    if !verify {
        builder.set_verify(SslVerifyMode::NONE);
    }
    Ok(builder.build())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chrome_builder_succeeds() {
        let connector = build_connector("chrome_stable", true);
        assert!(connector.is_ok(), "Chrome profile should build: {:?}", connector.err());
    }

    #[test]
    fn firefox_builder_succeeds() {
        let connector = build_connector("firefox_stable", true);
        assert!(connector.is_ok(), "Firefox profile should build: {:?}", connector.err());
    }

    #[test]
    fn unknown_profile_falls_back_to_chrome() {
        let connector = build_connector("unknown_profile", true);
        assert!(connector.is_ok());
    }

    #[test]
    fn configure_builder_allows_customization() {
        let mut builder = configure_builder("chrome_stable").unwrap();
        builder.set_verify(SslVerifyMode::NONE);
        let _ = builder.build();
    }
}
