use boring::ssl::{SslConnector, SslConnectorBuilder, SslMethod, SslVerifyMode};

use crate::{Error, apply, cert_blocklist, invariants::validate_profile_config, profile, trust};

/// Returns a configured builder that the caller can further customize before
/// calling `.build()`. Use this when you need to set additional options
/// (e.g., custom verify callback, session_id).
pub fn configure_builder(profile: &str) -> Result<SslConnectorBuilder, Error> {
    let mut builder = SslConnector::builder(SslMethod::tls())?;
    let config = profile::lookup_profile(profile);
    validate_profile_config(config)?;
    apply::apply_profile(&mut builder, config)?;
    trust::seed_default_trust(&mut builder)?;
    // The same SPKI blocklist as the rustls clients: BoringSSL runs this for
    // every certificate of the chain it built, after its own check.
    builder.set_verify_callback(SslVerifyMode::PEER, |preverified, context| {
        preverified
            && !context
                .current_cert()
                .and_then(|cert| cert.to_der().ok())
                .is_some_and(|der| cert_blocklist::der_cert_is_blocked(&der))
    });
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
