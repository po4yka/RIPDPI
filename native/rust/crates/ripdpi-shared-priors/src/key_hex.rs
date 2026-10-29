//! Build-time parsing of the shared-priors release public key.
//!
//! Compiled both into the crate (for tests) and into `build.rs` (via `#[path]`),
//! so the rules live in exactly one place.

use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum KeyHexError {
    BadLength,
    BadHexDigit,
    AllZero,
}

impl fmt::Display for KeyHexError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::BadLength => write!(f, "must be exactly 64 hex characters"),
            Self::BadHexDigit => write!(f, "contains a non-hex character"),
            Self::AllZero => write!(f, "must not be the all-zero key"),
        }
    }
}

impl std::error::Error for KeyHexError {}

/// Resolve the build-time env value. Unset or empty means "not configured"
/// and yields the all-zero key; a set value must be 64 hex characters and not
/// all zero, otherwise the build must fail rather than silently ship no key.
pub(crate) fn resolve_pub_key(env: Option<&str>) -> Result<[u8; 32], KeyHexError> {
    let Some(hex) = env.map(str::trim).filter(|hex| !hex.is_empty()) else {
        return Ok([0u8; 32]);
    };
    let bytes = hex.as_bytes();
    if bytes.len() != 64 {
        return Err(KeyHexError::BadLength);
    }
    let mut out = [0u8; 32];
    let (pairs, _) = bytes.as_chunks::<2>();
    for (slot, pair) in out.iter_mut().zip(pairs) {
        let hi = hex_digit(pair[0]).ok_or(KeyHexError::BadHexDigit)?;
        let lo = hex_digit(pair[1]).ok_or(KeyHexError::BadHexDigit)?;
        *slot = (hi << 4) | lo;
    }
    if out == [0u8; 32] {
        return Err(KeyHexError::AllZero);
    }
    Ok(out)
}

fn hex_digit(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_valid_hex_in_either_case() {
        let key =
            resolve_pub_key(Some("00112233445566778899aabbccddeeff00112233445566778899AABBCCDDEEFF")).expect("valid");
        assert_eq!(key[1], 0x11);
        assert_eq!(key[15], 0xff);
        assert_eq!(key[31], 0xff);
    }

    #[test]
    fn unset_or_blank_means_not_configured() {
        assert_eq!(resolve_pub_key(None), Ok([0u8; 32]));
        assert_eq!(resolve_pub_key(Some("")), Ok([0u8; 32]));
        assert_eq!(resolve_pub_key(Some("  ")), Ok([0u8; 32]));
    }

    #[test]
    fn malformed_or_zero_values_are_errors() {
        assert_eq!(resolve_pub_key(Some("abcd")), Err(KeyHexError::BadLength));
        assert_eq!(resolve_pub_key(Some(&"g".repeat(64))), Err(KeyHexError::BadHexDigit));
        assert_eq!(resolve_pub_key(Some(&"0".repeat(64))), Err(KeyHexError::AllZero));
    }
}
