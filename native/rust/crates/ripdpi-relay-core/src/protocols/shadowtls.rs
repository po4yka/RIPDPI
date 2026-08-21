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
    // The discriminator downstream consumes used to be the leading token
    // string (`shadowtls_version_mismatch`); it now travels as typed data on
    // the error payload via `crate::error::relay_failure_class`. Display text
    // stays byte-identical for the telemetry surface.
    crate::error::classified_error(
        FailureClass::ShadowTlsVersionMismatch,
        io::ErrorKind::Unsupported,
        error.to_string(),
    )
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
    #[test]
    fn classified_handshake_error_exposes_the_failure_class_for_downcast() {
        use crate::error::relay_failure_class;

        let mapped =
            super::classify_shadowtls_handshake_error(io::Error::other(ShadowTlsHandshakeError::version_mismatch()));

        assert_eq!(
            Some(FailureClass::ShadowTlsVersionMismatch),
            relay_failure_class(&mapped),
            "the failure class must travel as typed data, not only as a display-token prefix"
        );
    }
}
