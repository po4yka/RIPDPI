use std::io;

use ripdpi_failure_classifier::FailureClass;
use ripdpi_relay_tls_transports::{ShadowTlsFailureKind, ShadowTlsHandshakeError};

/// Map a `ripdpi-shadowtls` handshake error into a relay error carrying the
/// `FailureClass::ShadowTlsVersionMismatch` token. A ShadowTLS v2 server rejects
/// this v3-only client; without this mapping the failure surfaces as a generic
/// TLS handshake error. The token plus the typed error's actionable `Display`
/// flow through the SOCKS handshake-error telemetry (`record_handshake_error` →
/// `last_handshake_error`) to the service diagnostic surface, satisfying the
/// "v2-server attempts produce a user-actionable diagnostic" contract in
/// `docs/architecture/shadowtls-version-policy.md`. Non-version errors pass
/// through unchanged. Mirrors `classify_tuic_handshake_error`.
pub(crate) fn classify_shadowtls_handshake_error(error: io::Error) -> io::Error {
    let is_version_mismatch = error
        .get_ref()
        .and_then(|inner| inner.downcast_ref::<ShadowTlsHandshakeError>())
        .is_some_and(|handshake| handshake.kind() == ShadowTlsFailureKind::VersionMismatch);
    if !is_version_mismatch {
        return error;
    }
    // The discriminator downstream consumes is the leading token string
    // (`shadowtls_version_mismatch`), NOT `ErrorKind` — `Unsupported` is shared
    // with other relay errors. Key on the token, mirroring the TUIC mapping.
    io::Error::new(io::ErrorKind::Unsupported, format!("{}: {error}", FailureClass::ShadowTlsVersionMismatch.as_str()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_mismatch_maps_to_failure_class_token_and_actionable_message() {
        // The runtime path: a typed ShadowTLS version error becomes a relay error
        // whose recorded handshake-error string carries
        // FailureClass::ShadowTlsVersionMismatch and a server-upgrade hint.
        let shadowtls_error = io::Error::other(ShadowTlsHandshakeError::version_mismatch());
        let mapped = classify_shadowtls_handshake_error(shadowtls_error);

        assert_eq!(mapped.kind(), io::ErrorKind::Unsupported);
        let message = mapped.to_string();
        assert!(
            message.starts_with(FailureClass::ShadowTlsVersionMismatch.as_str()),
            "telemetry string must lead with the failure-class token, got: {message}",
        );
        assert!(message.contains("v3"), "diagnostic must be user-actionable (server upgrade to v3), got: {message}");
    }

    #[test]
    fn non_version_errors_pass_through_unchanged() {
        let original = io::Error::new(io::ErrorKind::ConnectionReset, "upstream reset");
        let mapped = classify_shadowtls_handshake_error(original);
        assert_eq!(mapped.kind(), io::ErrorKind::ConnectionReset);
        assert_eq!(mapped.to_string(), "upstream reset");
    }
}
