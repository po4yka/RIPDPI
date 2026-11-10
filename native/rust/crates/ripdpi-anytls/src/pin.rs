//! Leaf-certificate SHA-256 pin: the safe alternative to skipping TLS verification
//! for servers with a self-signed certificate (the stock anytls-go server).

use boring::ssl::{SslAlert, SslConnectorBuilder, SslRef, SslVerifyError, SslVerifyMode};
use sha2::{Digest, Sha256};

/// SHA-256 digest of the server leaf certificate in DER form.
pub type CertificatePinSha256 = [u8; 32];

/// Parse exactly 64 lowercase hex digits (the Kotlin side normalizes first).
pub fn parse_certificate_pin_sha256(raw: &str) -> Result<CertificatePinSha256, String> {
    if raw.len() != 64 || !raw.bytes().all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)) {
        return Err("expected 64 lowercase hex digits".to_owned());
    }
    let mut pin = [0_u8; 32];
    for (slot, pair) in pin.iter_mut().zip(raw.as_bytes().as_chunks::<2>().0) {
        let digit = |byte: u8| if byte.is_ascii_digit() { byte - b'0' } else { byte - b'a' + 10 };
        *slot = (digit(pair[0]) << 4) | digit(pair[1]);
    }
    Ok(pin)
}

/// With a pin, replace chain and host name verification by [`verify_pinned_leaf`].
pub(crate) fn require_pin(builder: &mut SslConnectorBuilder, pin: Option<CertificatePinSha256>) {
    if let Some(pin) = pin {
        builder.set_custom_verify_callback(SslVerifyMode::PEER, move |ssl| verify_pinned_leaf(ssl, &pin));
    }
}

/// Accept the handshake only when the leaf certificate digest equals `pin`.
/// The chain and the host name are not checked: the pin replaces them.
fn verify_pinned_leaf(ssl: &mut SslRef, pin: &CertificatePinSha256) -> Result<(), SslVerifyError> {
    let leaf = ssl.peer_certificate().ok_or(SslVerifyError::Invalid(SslAlert::BAD_CERTIFICATE))?;
    let der = leaf.to_der().map_err(|_| SslVerifyError::Invalid(SslAlert::BAD_CERTIFICATE))?;
    if Sha256::digest(&der).as_slice() == pin.as_slice() {
        Ok(())
    } else {
        Err(SslVerifyError::Invalid(SslAlert::BAD_CERTIFICATE))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_lowercase_hex_and_rejects_other_input() {
        let pin = parse_certificate_pin_sha256(&format!("{}ff", "00".repeat(31))).expect("valid pin");
        assert_eq!(pin[0], 0);
        assert_eq!(pin[31], 0xff);
        assert!(parse_certificate_pin_sha256(&"AB".repeat(32)).is_err());
        assert!(parse_certificate_pin_sha256("abcd").is_err());
        assert!(parse_certificate_pin_sha256(&"zz".repeat(32)).is_err());
    }
}
