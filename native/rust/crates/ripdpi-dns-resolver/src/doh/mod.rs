//! DoH (DNS-over-HTTPS) runtime client hardening types.
//!
//! This module provides:
//! - `DohRuntimeMode` — POST-first mode enum; GET requires explicit profile opt-in.
//! - `DohAuthPolicy` — TLS auth name + trust/pin policy for the DoH endpoint.
//! - `DohRedactor` — strips sensitive fields from logged events.
//! - `DohClientError` — error type whose variants never include a plaintext-DNS path.

mod redactor;

pub use redactor::DohRedactor;

use std::net::IpAddr;

// ---------------------------------------------------------------------------
// DohRuntimeMode
// ---------------------------------------------------------------------------

/// Wire-format method used for DoH resolution at runtime.
///
/// `Post` is the default and privacy-preserving choice (RFC 8484 §4.1).
/// `Get` must be explicitly enabled via a profile flag; it is never a silent
/// fallback because GET encodes the query in the URL, which leaks domain names
/// to intermediate proxies and TLS-terminating middleboxes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum DohRuntimeMode {
    /// Use HTTP POST with `Content-Type: application/dns-message` (default).
    #[default]
    Post,
    /// Use HTTP GET with the query base64url-encoded in the `dns` query
    /// parameter.  Only valid when the resolver profile explicitly sets
    /// `allow_doh_get = true`.
    Get,
}

// ---------------------------------------------------------------------------
// TrustPolicy
// ---------------------------------------------------------------------------

/// TLS trust model for the DoH endpoint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TrustPolicy {
    /// Trust the system/webpki root store (default).
    SystemRoots,
    /// Pin the connection to a specific bootstrap IP.  The TLS handshake is
    /// still validated against `auth_name`; only the network-layer destination
    /// is pinned to prevent DNS-based redirection.
    PinnedBootstrapIp(IpAddr),
}

// ---------------------------------------------------------------------------
// DohAuthPolicy
// ---------------------------------------------------------------------------

/// TLS authentication policy for a DoH endpoint.
///
/// `auth_name` is validated as the TLS SNI/SAN value during every handshake.
/// A mismatch is a hard error — the resolver never falls back to an
/// unauthenticated path.
#[derive(Debug, Clone)]
pub struct DohAuthPolicy {
    /// Expected TLS server name (SNI).  Must match the certificate presented
    /// by the DoH server.
    pub auth_name: String,
    /// Trust model to use when establishing the TLS connection.
    pub trust: TrustPolicy,
}

impl DohAuthPolicy {
    /// Create a policy that validates `auth_name` against the system root store.
    pub fn with_system_roots(auth_name: impl Into<String>) -> Self {
        Self { auth_name: auth_name.into(), trust: TrustPolicy::SystemRoots }
    }

    /// Create a policy that pins the connection to a specific bootstrap IP
    /// while still validating the TLS server name.
    pub fn with_pinned_ip(auth_name: impl Into<String>, ip: IpAddr) -> Self {
        Self { auth_name: auth_name.into(), trust: TrustPolicy::PinnedBootstrapIp(ip) }
    }

    /// Returns `true` when `presented_name` matches the configured `auth_name`
    /// (case-insensitive, as per RFC 5280).
    pub fn validates_name(&self, presented_name: &str) -> bool {
        self.auth_name.eq_ignore_ascii_case(presented_name)
    }

    /// Returns `true` when the bootstrap IP used for the connection matches
    /// the pinned IP in the policy (or when no pin is configured).
    pub fn validates_bootstrap_ip(&self, used_ip: IpAddr) -> bool {
        match &self.trust {
            TrustPolicy::SystemRoots => true,
            TrustPolicy::PinnedBootstrapIp(pinned) => *pinned == used_ip,
        }
    }
}

// ---------------------------------------------------------------------------
// DohClientError
// ---------------------------------------------------------------------------

/// Errors that can occur during a DoH exchange.
///
/// **Design invariant**: none of these variants trigger or encode a fallback
/// to plaintext (port-53) DNS.  The caller is responsible for integrating with
/// `StrictDnsResult::StrictFailure` from the resolver pool / row-67 work.
#[derive(Debug, thiserror::Error)]
pub enum DohClientError {
    /// The runtime mode is `Get` but the profile has not opted in.
    #[error("DoH GET is disabled; set allow_doh_get = true in the resolver profile to enable it")]
    GetNotEnabled,

    /// TLS server name presented by the peer did not match the configured
    /// `auth_name`.
    #[error("DoH TLS auth name mismatch: expected {expected:?}, got {presented:?}")]
    AuthNameMismatch { expected: String, presented: String },

    /// The bootstrap IP used for the connection does not match the pinned IP.
    #[error("DoH bootstrap IP mismatch: pinned {pinned}, used {used}")]
    BootstrapIpMismatch { pinned: IpAddr, used: IpAddr },

    /// The DoH server returned a non-2xx HTTP status.
    #[error("DoH server returned HTTP {0}")]
    HttpStatus(u16),

    /// A network or TLS I/O error occurred.  The message is redacted before
    /// being stored here.
    #[error("DoH transport error: {0}")]
    Transport(String),

    /// The DNS response wire format could not be decoded.
    #[error("DoH response parse failed")]
    ResponseParse,
}
