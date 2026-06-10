//! Error type shared across the `ripdpi-tls-spoof` modules.

use thiserror::Error;

/// Failure modes for relay-side TLS spoofing.
///
/// Privacy: no variant carries the decoy SNI hostname, the destination
/// address, or the spoof method *value* in its `Display` output. Variants
/// describe the *class* of failure only — see `network-fingerprint-privacy.md`
/// and AC7 redaction. Anything user/network-identifying stays out of the
/// formatted string so the error is safe to log verbatim.
#[derive(Debug, Error, PartialEq, Eq)]
#[non_exhaustive]
pub enum SpoofError {
    /// `decoy_sni` was empty.
    #[error("decoy SNI is empty")]
    EmptySni,

    /// `decoy_sni` parsed as an IP literal (v4 or v6). The TLS SNI extension
    /// must carry a DNS hostname, never an address literal.
    #[error("decoy SNI is an IP literal, not a hostname")]
    SniIsIpLiteral,

    /// `decoy_sni` is not a syntactically valid DNS hostname (bad length or
    /// disallowed characters).
    #[error("decoy SNI is not a valid DNS hostname")]
    InvalidSniHostname,

    /// An unknown spoof-method string was supplied to `SpoofMethod::from_str`.
    #[error("unknown spoof method")]
    UnknownMethod,

    /// The supplied bytes are not a well-formed TLS ClientHello, or have no
    /// `server_name` (SNI) extension to rewrite.
    #[error("input is not a ClientHello with an SNI extension")]
    MalformedClientHello,
}
