use std::io;

use base64::{Engine as _, engine::general_purpose::STANDARD};
use boringtun::x25519::{PublicKey, StaticSecret};
use serde::Serialize;

/// A newly generated WireGuard identity. Debug output never includes its keys.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WarpKeyPair {
    private_key: String,
    public_key: String,
}

impl std::fmt::Debug for WarpKeyPair {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("WarpKeyPair([REDACTED])")
    }
}

/// Generate a WireGuard identity from operating-system entropy.
///
/// # Errors
/// Returns an error when operating-system entropy is unavailable.
pub fn generate_warp_keypair() -> io::Result<WarpKeyPair> {
    let mut private_key = [0u8; 32];
    getrandom::fill(&mut private_key).map_err(|error| io::Error::other(error.to_string()))?;
    // Match wg genkey's canonical Curve25519 secret encoding.
    private_key[0] &= 248;
    private_key[31] = (private_key[31] & 127) | 64;
    Ok(encode_keypair(private_key))
}

fn encode_keypair(private_key: [u8; 32]) -> WarpKeyPair {
    let secret = StaticSecret::from(private_key);
    let public_key = PublicKey::from(&secret);
    WarpKeyPair { private_key: STANDARD.encode(private_key), public_key: STANDARD.encode(public_key.as_bytes()) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keypair_matches_rfc7748_alice_public_key() {
        // RFC 7748 section 6.1 supplies this independent X25519 test vector.
        let private_key = [
            0x77, 0x07, 0x6d, 0x0a, 0x73, 0x18, 0xa5, 0x7d, 0x3c, 0x16, 0xc1, 0x72, 0x51, 0xb2, 0x66, 0x45, 0xdf, 0x4c,
            0x2f, 0x87, 0xeb, 0xc0, 0x99, 0x2a, 0xb1, 0x77, 0xfb, 0xa5, 0x1d, 0xb9, 0x2c, 0x2a,
        ];
        let expected_public = [
            0x85, 0x20, 0xf0, 0x09, 0x89, 0x30, 0xa7, 0x54, 0x74, 0x8b, 0x7d, 0xdc, 0xb4, 0x3e, 0xf7, 0x5a, 0x0d, 0xbf,
            0x3a, 0x0d, 0x26, 0x38, 0x1a, 0xf4, 0xeb, 0xa4, 0xa9, 0x8e, 0xaa, 0x9b, 0x4e, 0x6a,
        ];
        let pair = encode_keypair(private_key);
        assert_eq!(STANDARD.decode(&pair.public_key).unwrap(), expected_public);
        assert_eq!(STANDARD.decode(&pair.private_key).unwrap(), private_key);
        assert_eq!(format!("{pair:?}"), "WarpKeyPair([REDACTED])");
    }

    #[test]
    fn generated_keypairs_are_distinct_and_have_32_byte_keys() {
        let first = generate_warp_keypair().unwrap();
        let second = generate_warp_keypair().unwrap();
        assert_eq!(STANDARD.decode(&first.private_key).unwrap().len(), 32);
        assert_eq!(STANDARD.decode(&first.public_key).unwrap().len(), 32);
        let secret = STANDARD.decode(&first.private_key).unwrap();
        assert_eq!(secret[0] & 7, 0);
        assert_eq!(secret[31] & 192, 64);
        assert!(first.private_key != second.private_key, "independent requests must generate different private keys");
        assert!(first.public_key != second.public_key, "independent requests must generate different public keys");
    }
}
