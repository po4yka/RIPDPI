//! Configuration for the relay-side TLS spoofer.
//!
//! The whole subsystem is **default-off**: [`TlsSpoofConfig::default`] yields
//! `enabled = false`, and every entry point short-circuits to a no-op unless
//! `enabled` is explicitly set true and [`TlsSpoofConfig::validate`] passes.

use std::net::IpAddr;
use std::str::FromStr;

use serde::{Deserialize, Serialize};

use crate::error::SpoofError;

/// Maximum length of a single DNS label (RFC 1035 §2.3.4).
const MAX_LABEL_LEN: usize = 63;
/// Maximum length of a full DNS hostname (RFC 1035 §2.3.4).
const MAX_HOSTNAME_LEN: usize = 253;

/// How the forged decoy ClientHello segment is corrupted on the wire so the
/// DPI middlebox records the decoy SNI but the *real* server's TCP stack
/// silently discards the segment (it never reaches the handshake).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum SpoofMethod {
    /// Emit the decoy segment with an ACK number outside the peer's accepted
    /// window. The DPI box parses the payload; the real server drops it.
    #[default]
    WrongAck,
    /// Attach a bogus TCP MD5 Signature option (Kind=19, RFC 2385). A server
    /// not configured for MD5 silently drops the segment; the DPI box still
    /// sees the ClientHello.
    WrongMd5,
    /// Attach a deliberately stale TCP timestamp option (Kind=8, RFC 7323).
    /// PAWS (RFC 7323 §5.3) makes the real server discard the segment as an
    /// old retransmission while the DPI box still parses it. Requires the
    /// upstream connection to have negotiated TCP timestamps; injection fails
    /// closed when they are absent.
    WrongTimestamp,
}

impl SpoofMethod {
    /// Stable lowercase wire/string identifier for this method.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        // Exhaustive match — no wildcard arm, so adding a variant is a compile
        // error here (forces this and `from_str` to stay in sync).
        match self {
            SpoofMethod::WrongAck => "wrong_ack",
            SpoofMethod::WrongMd5 => "wrong_md5",
            SpoofMethod::WrongTimestamp => "wrong_timestamp",
        }
    }
}

impl FromStr for SpoofMethod {
    type Err = SpoofError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        // Exhaustive match against the canonical identifiers; unknown -> Err.
        match s {
            "wrong_ack" => Ok(SpoofMethod::WrongAck),
            "wrong_md5" => Ok(SpoofMethod::WrongMd5),
            "wrong_timestamp" => Ok(SpoofMethod::WrongTimestamp),
            _ => Err(SpoofError::UnknownMethod),
        }
    }
}

/// Relay-side spoofer configuration. Default-off (`enabled = false`,
/// empty `decoy_sni`, [`SpoofMethod::WrongAck`]).
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct TlsSpoofConfig {
    /// Master switch. When false the spoofer is a strict no-op.
    pub enabled: bool,
    /// Wire-corruption method for the forged decoy segment.
    pub method: SpoofMethod,
    /// Decoy hostname injected into the forged ClientHello's SNI extension.
    /// Must be a valid DNS hostname and must NOT be an IP literal.
    pub decoy_sni: String,
}

impl TlsSpoofConfig {
    /// Validate the configuration. Returns `Ok(())` for a usable config.
    ///
    /// Errors never include the offending hostname value (AC7 redaction).
    pub fn validate(&self) -> Result<(), SpoofError> {
        validate_decoy_sni(&self.decoy_sni)
    }
}

/// Validate a decoy SNI string: non-empty, not an IP literal, valid DNS
/// hostname charset/length.
///
/// Shared by [`TlsSpoofConfig::validate`] and the signaling layer.
pub(crate) fn validate_decoy_sni(decoy_sni: &str) -> Result<(), SpoofError> {
    if decoy_sni.is_empty() {
        return Err(SpoofError::EmptySni);
    }
    // Reject anything that parses as an IP address (v4 or v6). The SNI
    // extension must carry a DNS name; address literals are forbidden and are
    // also a privacy/correctness footgun.
    if decoy_sni.parse::<IpAddr>().is_ok() {
        return Err(SpoofError::SniIsIpLiteral);
    }
    if !is_valid_dns_hostname(decoy_sni) {
        return Err(SpoofError::InvalidSniHostname);
    }
    Ok(())
}

/// Basic DNS hostname syntax check (RFC 1035 preferred name syntax, relaxed to
/// allow leading digits per RFC 1123): total length <= 253, each
/// dot-separated label 1..=63 chars of `[A-Za-z0-9-]`, no leading/trailing
/// hyphen in a label, no empty labels. A single trailing dot (FQDN root) is
/// tolerated.
fn is_valid_dns_hostname(host: &str) -> bool {
    let host = host.strip_suffix('.').unwrap_or(host);
    if host.is_empty() || host.len() > MAX_HOSTNAME_LEN {
        return false;
    }
    host.split('.').all(is_valid_label)
}

fn is_valid_label(label: &str) -> bool {
    let bytes = label.as_bytes();
    if bytes.is_empty() || bytes.len() > MAX_LABEL_LEN {
        return false;
    }
    if bytes[0] == b'-' || bytes[bytes.len() - 1] == b'-' {
        return false;
    }
    bytes.iter().all(|&b| b.is_ascii_alphanumeric() || b == b'-')
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg(decoy: &str) -> TlsSpoofConfig {
        TlsSpoofConfig { enabled: true, method: SpoofMethod::WrongAck, decoy_sni: decoy.to_string() }
    }

    #[test]
    fn default_is_disabled() {
        let c = TlsSpoofConfig::default();
        assert!(!c.enabled);
        assert!(c.decoy_sni.is_empty());
    }

    #[test]
    fn valid_hostname_accepted() {
        assert_eq!(cfg("www.wikipedia.org").validate(), Ok(()));
        assert_eq!(cfg("example.com").validate(), Ok(()));
        assert_eq!(cfg("a-b.sub-domain.example.co.uk").validate(), Ok(()));
        assert_eq!(cfg("xn--80ak6aa92e.com").validate(), Ok(())); // punycode
        assert_eq!(cfg("localhost").validate(), Ok(()));
        assert_eq!(cfg("fqdn.example.com.").validate(), Ok(())); // trailing dot
    }

    #[test]
    fn ipv4_literal_rejected() {
        assert_eq!(cfg("1.2.3.4").validate(), Err(SpoofError::SniIsIpLiteral));
        assert_eq!(cfg("127.0.0.1").validate(), Err(SpoofError::SniIsIpLiteral));
    }

    #[test]
    fn ipv6_literal_rejected() {
        assert_eq!(cfg("::1").validate(), Err(SpoofError::SniIsIpLiteral));
        assert_eq!(cfg("2001:db8::1").validate(), Err(SpoofError::SniIsIpLiteral));
        assert_eq!(cfg("fe80::1").validate(), Err(SpoofError::SniIsIpLiteral));
    }

    #[test]
    fn empty_rejected() {
        assert_eq!(cfg("").validate(), Err(SpoofError::EmptySni));
    }

    #[test]
    fn malformed_hostname_rejected() {
        assert_eq!(cfg("-leading.example.com").validate(), Err(SpoofError::InvalidSniHostname));
        assert_eq!(cfg("trailing-.example.com").validate(), Err(SpoofError::InvalidSniHostname));
        assert_eq!(cfg("double..dot").validate(), Err(SpoofError::InvalidSniHostname));
        assert_eq!(cfg("under_score.com").validate(), Err(SpoofError::InvalidSniHostname));
        assert_eq!(cfg("space host.com").validate(), Err(SpoofError::InvalidSniHostname));
        let too_long_label = format!("{}.com", "a".repeat(64));
        assert_eq!(cfg(&too_long_label).validate(), Err(SpoofError::InvalidSniHostname));
        let too_long_host = format!("{}.com", "a.".repeat(170));
        assert_eq!(cfg(&too_long_host).validate(), Err(SpoofError::InvalidSniHostname));
    }

    #[test]
    fn method_round_trips_through_str() {
        for m in [SpoofMethod::WrongAck, SpoofMethod::WrongMd5, SpoofMethod::WrongTimestamp] {
            assert_eq!(SpoofMethod::from_str(m.as_str()), Ok(m));
        }
        assert_eq!(SpoofMethod::WrongAck.as_str(), "wrong_ack");
        assert_eq!(SpoofMethod::WrongMd5.as_str(), "wrong_md5");
        assert_eq!(SpoofMethod::WrongTimestamp.as_str(), "wrong_timestamp");
    }

    #[test]
    fn unknown_method_string_rejected() {
        assert_eq!(SpoofMethod::from_str("nope"), Err(SpoofError::UnknownMethod));
        assert_eq!(SpoofMethod::from_str(""), Err(SpoofError::UnknownMethod));
        assert_eq!(SpoofMethod::from_str("WrongAck"), Err(SpoofError::UnknownMethod));
    }
}
