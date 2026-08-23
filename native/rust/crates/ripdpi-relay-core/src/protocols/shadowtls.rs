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
    // Walk the error's source chain: a chain-relay failure arrives wrapped in
    // a hop-tagged payload whose source carries the typed handshake error.
    let mut current = error.get_ref().map(|inner| inner as &(dyn std::error::Error + 'static));
    let is_version_mismatch = loop {
        match current {
            None => break false,
            Some(inner) => match inner.downcast_ref::<ShadowTlsHandshakeError>() {
                Some(handshake) => break handshake.kind() == ShadowTlsFailureKind::VersionMismatch,
                None => current = inner.source(),
            },
        }
    };
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

#[cfg(test)]
mod hop_tagged_classification_tests {
    use super::*;

    /// A chain-relay failure arrives wrapped in the chain layer's
    /// `HopTaggedError` payload. The classifier must still find the typed
    /// handshake error through the source chain and map it to the
    /// user-actionable version-mismatch diagnostic.
    #[test]
    fn classifier_reaches_typed_error_through_a_hop_tagged_wrapper() {
        let tagged = io::Error::new(
            io::ErrorKind::ConnectionReset,
            super::super::chain::test_support::hop_tagged(
                "hop 1 (shadowtls_v3)",
                io::Error::new(io::ErrorKind::ConnectionReset, ShadowTlsHandshakeError::version_mismatch()),
            ),
        );

        let mapped = classify_shadowtls_handshake_error(tagged);
        assert!(
            mapped.to_string().contains("chain hop 1 (shadowtls_v3)")
                && mapped.to_string().contains(FailureClass::ShadowTlsVersionMismatch.as_str()),
            "the diagnostic must keep both the hop context and the failure token, got: {mapped}"
        );
    }

    #[test]
    fn classifier_passes_through_untyped_hop_tagged_errors() {
        let tagged = super::super::chain::test_support::hop_tagged(
            "hop 0 (trojan)",
            io::Error::new(io::ErrorKind::ConnectionRefused, "connection refused"),
        );
        let mapped = classify_shadowtls_handshake_error(io::Error::new(io::ErrorKind::ConnectionRefused, tagged));
        assert_eq!(io::ErrorKind::ConnectionRefused, mapped.kind());
        assert!(mapped.to_string().contains("chain hop 0 (trojan)"), "unexpected error: {mapped}");
    }
}
