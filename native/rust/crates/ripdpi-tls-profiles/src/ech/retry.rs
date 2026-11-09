//! Retry evidence produced only after rustls authenticates ECH rejection.

use rustls::internal::msgs::codec::Codec;

/// Return authenticated retry configs, or no retry for any other TLS error.
///
/// The pinned rustls backend emits this error only after verifying the public
/// name and finishing the outer handshake. Empty rollback signals fail closed.
pub fn rustls_ech_retry_configs(error: &rustls::Error) -> Option<Vec<u8>> {
    let rustls::Error::PeerIncompatible(rustls::PeerIncompatible::ServerRejectedEncryptedClientHello(Some(configs))) =
        error
    else {
        return None;
    };
    if configs.is_empty() {
        return None;
    }
    let bytes = configs.get_encoding();
    rustls::client::EchConfig::new(
        rustls::pki_types::EchConfigListBytes::from(bytes.clone()),
        rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES,
    )
    .ok()?;
    Some(bytes)
}

/// Build retry setup metadata from a server's authenticated config list.
/// Authentication is supplied by the caller's TLS rejection result.
pub fn outbound_ech_retry_config(bytes: Vec<u8>) -> Option<crate::OutboundEchConfig> {
    use rustls::internal::msgs::{codec::Reader, handshake::EchConfigPayload};
    let configs = Vec::<EchConfigPayload>::read(&mut Reader::init(&bytes)).ok()?;
    let public_name = configs.into_iter().find_map(|config| match config {
        EchConfigPayload::V18(contents) => Some(contents.public_name.as_ref().to_owned()),
        EchConfigPayload::Unknown { .. } => None,
    })?;
    crate::OutboundEchConfig::new(public_name, bytes).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ordinary_errors_and_rollback_do_not_authorize_retry() {
        assert!(
            rustls_ech_retry_configs(&rustls::Error::AlertReceived(rustls::AlertDescription::HandshakeFailure))
                .is_none()
        );
        assert!(
            rustls_ech_retry_configs(&rustls::Error::PeerIncompatible(
                rustls::PeerIncompatible::ServerRejectedEncryptedClientHello(None),
            ))
            .is_none()
        );
        assert!(
            rustls_ech_retry_configs(&rustls::Error::PeerIncompatible(
                rustls::PeerIncompatible::ServerRejectedEncryptedClientHello(Some(Vec::new())),
            ))
            .is_none()
        );
    }
}
