//! Client -> relay spoof-intent signaling (AC2).
//!
//! The on-device client cannot perform the raw-socket injection itself
//! (non-rooted Android has no `CAP_NET_RAW`/`CAP_NET_ADMIN`), so it instead
//! tells the relay hop *what* to spoof. [`SpoofRequest`] is the wire model for
//! that intent. It is transport-agnostic serde — the relay control channel
//! (whatever carries it) is out of scope here.

use serde::{Deserialize, Serialize};

use crate::config::{SpoofMethod, validate_decoy_sni};
use crate::error::SpoofError;

/// A client's request that the relay spoof a decoy ClientHello in front of the
/// real handshake to `destination`.
///
/// `destination` is kept as a `host:port` / address string (the relay resolves
/// and connects); modelling it as a plain `String` avoids forcing the client to
/// pre-resolve and keeps the signaling layer dependency-light.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SpoofRequest {
    /// Real upstream the relay should connect to, as `host:port`.
    pub destination: String,
    /// Decoy hostname the relay injects into the forged SNI.
    pub decoy_sni: String,
    /// Wire-corruption method for the forged decoy segment.
    pub method: SpoofMethod,
}

impl SpoofRequest {
    /// Validate the request's decoy SNI (non-empty, not an IP literal, valid
    /// hostname). Errors never echo the hostname value (AC7 redaction).
    pub fn validate(&self) -> Result<(), SpoofError> {
        validate_decoy_sni(&self.decoy_sni)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serde_json_round_trip() {
        let req = SpoofRequest {
            destination: "example.com:443".to_string(),
            decoy_sni: "www.wikipedia.org".to_string(),
            method: SpoofMethod::WrongMd5,
        };
        let json = serde_json::to_string(&req).unwrap();
        let back: SpoofRequest = serde_json::from_str(&json).unwrap();
        assert_eq!(req, back);
        // method serializes by its enum variant name.
        assert!(json.contains("WrongMd5"));
    }

    #[test]
    fn validate_rejects_ip_literal_decoy() {
        let req = SpoofRequest {
            destination: "10.0.0.5:443".to_string(),
            decoy_sni: "10.0.0.5".to_string(),
            method: SpoofMethod::WrongAck,
        };
        assert_eq!(req.validate(), Err(SpoofError::SniIsIpLiteral));
    }

    #[test]
    fn validate_accepts_hostname_decoy() {
        let req = SpoofRequest {
            destination: "[2001:db8::1]:443".to_string(),
            decoy_sni: "decoy.example.com".to_string(),
            method: SpoofMethod::WrongTimestamp,
        };
        assert_eq!(req.validate(), Ok(()));
    }
}
