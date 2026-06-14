use std::fmt;

use base64::Engine;
use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD, URL_SAFE, URL_SAFE_NO_PAD};

use crate::error::{Result, SshError};

/// How the SSH client authenticates to the server.
///
/// The [`fmt::Debug`] implementation on [`SshConfig`] redacts the secret-bearing
/// variants (`password`, `pem`, `passphrase`) so credentials never land in
/// logs, telemetry, or goldens.
#[derive(Clone)]
pub enum SshAuth {
    /// Password authentication. The inner string is the account password.
    Password(String),
    /// Public-key authentication backed by a PEM-encoded private key, with an
    /// optional decryption passphrase.
    PrivateKey {
        /// The PEM-encoded private key.
        pem: String,
        /// The passphrase that decrypts `pem`, if the key is encrypted.
        passphrase: Option<String>,
    },
}

impl SshAuth {
    /// Whether this auth carries usable material (a non-empty password or a
    /// non-empty private-key PEM). An empty passphrase is allowed — many keys
    /// are unencrypted.
    fn has_material(&self) -> bool {
        match self {
            Self::Password(password) => !password.is_empty(),
            Self::PrivateKey { pem, .. } => !pem.trim().is_empty(),
        }
    }
}

/// How the SSH client decides whether to trust the server's host key.
#[derive(Clone)]
pub enum SshHostKeyPolicy {
    /// Trust-on-first-use. If `pinned_fingerprint` is set the presented key
    /// must match it; if it is `None` the first-use key is surfaced to the UI
    /// (via [`SshError::HostKeyUntrusted`]) for explicit accept/reject rather
    /// than being silently trusted.
    Tofu {
        /// A previously-accepted fingerprint, if the user already pinned one.
        pinned_fingerprint: Option<String>,
    },
    /// Strict pinning. The presented key MUST match `fingerprint` or the
    /// connection is aborted with [`SshError::HostKeyMismatch`].
    Strict {
        /// The fingerprint the server's host key must equal.
        fingerprint: String,
    },
}

/// A validated SSH outbound configuration.
///
/// The [`fmt::Debug`] implementation is hand-written so the password and the
/// private-key PEM / passphrase never appear in logs, telemetry, or goldens.
/// The `host`, `username`, `port`, and host-key fingerprint are not secrets and
/// stay visible.
#[derive(Clone)]
pub struct SshConfig {
    pub host: String,
    pub port: u16,
    pub username: String,
    pub auth: SshAuth,
    pub host_key_policy: SshHostKeyPolicy,
}

impl SshConfig {
    /// Validate this configuration without driving any network I/O.
    ///
    /// Checks the port range, the non-empty username invariant, that auth
    /// material is present, and — for a strict host-key policy — that the
    /// pinned fingerprint parses.
    pub fn validate(&self) -> Result<()> {
        if self.host.trim().is_empty() {
            return Err(SshError::EmptyHost);
        }
        if self.port == 0 {
            return Err(SshError::InvalidPort);
        }
        if self.username.trim().is_empty() {
            return Err(SshError::EmptyUsername);
        }
        if !self.auth.has_material() {
            return Err(SshError::MissingAuth);
        }
        if let SshHostKeyPolicy::Strict { fingerprint } = &self.host_key_policy {
            parse_fingerprint(fingerprint)?;
        }
        Ok(())
    }
}

impl fmt::Debug for SshConfig {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("SshConfig")
            .field("host", &self.host)
            .field("port", &self.port)
            .field("username", &self.username)
            .field("auth", &self.auth)
            .field("host_key_policy", &self.host_key_policy)
            .finish()
    }
}

impl fmt::Debug for SshAuth {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Password(_) => formatter.debug_tuple("Password").field(&"<redacted>").finish(),
            Self::PrivateKey { passphrase, .. } => formatter
                .debug_struct("PrivateKey")
                .field("pem", &"<redacted>")
                .field("passphrase", &passphrase.as_ref().map(|_| "<redacted>"))
                .finish(),
        }
    }
}

impl fmt::Debug for SshHostKeyPolicy {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        // The fingerprint is a public host-key digest, not a secret: it is the
        // value the UI shows the user on accept/reject, so it stays visible.
        match self {
            Self::Tofu { pinned_fingerprint } => {
                formatter.debug_struct("Tofu").field("pinned_fingerprint", pinned_fingerprint).finish()
            }
            Self::Strict { fingerprint } => formatter.debug_struct("Strict").field("fingerprint", fingerprint).finish(),
        }
    }
}

/// The byte length of a SHA-256 digest.
const SHA256_DIGEST_LEN: usize = 32;

/// A parsed SSH host-key fingerprint.
///
/// RIPDPI canonicalises on the OpenSSH `SHA256:<base64>` form. The base64 body
/// is **decoded to its raw 32 bytes** at parse time and equality is byte-wise on
/// that digest, so a pin written in any base64 alphabet (standard `+/` or
/// URL-safe `-_`) with or without `=` padding compares equal to the standard,
/// unpadded form `russh` presents. The canonical standard-unpadded re-encoding
/// is retained in `sha256_base64` for error messages and UI display.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostKeyFingerprint {
    /// The raw 32-byte SHA-256 digest of the host key. Equality is defined by
    /// this field, making comparison alphabet- and padding-independent.
    digest: [u8; SHA256_DIGEST_LEN],
    /// The canonical standard-unpadded base64 re-encoding of `digest`, used for
    /// error messages and UI display (the value the user sees on accept/reject).
    pub sha256_base64: String,
}

/// Parse an OpenSSH-style `SHA256:<base64>` host-key fingerprint.
///
/// Only the SHA-256 form is accepted — the legacy colon-delimited MD5 form is
/// rejected because it is cryptographically weak for pinning. The base64 body is
/// decoded accepting BOTH the standard (`+/`) and URL-safe (`-_`) alphabets,
/// with or without `=` padding, and must decode to exactly 32 bytes (a SHA-256
/// digest). A body that decodes to any other length — e.g. `SHA256:AAAA` — is
/// rejected: it could never match a real host key. The parsed fingerprint stores
/// the raw digest so equality is independent of the alphabet / padding the pin
/// was written in.
pub fn parse_fingerprint(value: &str) -> Result<HostKeyFingerprint> {
    let trimmed = value.trim();
    let body = trimmed
        .strip_prefix("SHA256:")
        .ok_or_else(|| SshError::Ssh(format!("host-key fingerprint must use the SHA256: form, got `{trimmed}`")))?;
    if body.is_empty() {
        return Err(SshError::Ssh("host-key fingerprint SHA256 body must not be empty".to_string()));
    }
    // Try the four base64 variants in turn so a pin in any common alphabet /
    // padding combination decodes to the same digest. `russh` emits
    // standard-unpadded, so that is tried first.
    let decoded = STANDARD_NO_PAD
        .decode(body)
        .or_else(|_| URL_SAFE_NO_PAD.decode(body))
        .or_else(|_| STANDARD.decode(body))
        .or_else(|_| URL_SAFE.decode(body))
        .map_err(|_| SshError::Ssh(format!("host-key fingerprint SHA256 body is not valid base64: `{body}`")))?;
    let digest: [u8; SHA256_DIGEST_LEN] = decoded.as_slice().try_into().map_err(|_| {
        SshError::Ssh(format!(
            "host-key fingerprint SHA256 body must decode to {SHA256_DIGEST_LEN} bytes, got {}",
            decoded.len()
        ))
    })?;
    let sha256_base64 = STANDARD_NO_PAD.encode(digest);
    Ok(HostKeyFingerprint { digest, sha256_base64 })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_config() -> SshConfig {
        SshConfig {
            host: "ssh.example".to_string(),
            port: 22,
            username: "alice".to_string(),
            auth: SshAuth::Password("correct-horse".to_string()),
            host_key_policy: SshHostKeyPolicy::Tofu { pinned_fingerprint: None },
        }
    }

    #[test]
    fn valid_config_validates() {
        assert!(valid_config().validate().is_ok());
    }

    #[test]
    fn zero_port_is_rejected() {
        let mut config = valid_config();
        config.port = 0;
        assert!(matches!(config.validate(), Err(SshError::InvalidPort)));
    }

    #[test]
    fn empty_host_is_rejected() {
        let mut config = valid_config();
        config.host = "   ".to_string();
        assert!(matches!(config.validate(), Err(SshError::EmptyHost)));
    }

    #[test]
    fn empty_username_is_rejected() {
        let mut config = valid_config();
        config.username = "   ".to_string();
        assert!(matches!(config.validate(), Err(SshError::EmptyUsername)));
    }

    #[test]
    fn empty_password_is_missing_auth() {
        let mut config = valid_config();
        config.auth = SshAuth::Password(String::new());
        assert!(matches!(config.validate(), Err(SshError::MissingAuth)));
    }

    #[test]
    fn blank_private_key_is_missing_auth() {
        let mut config = valid_config();
        config.auth = SshAuth::PrivateKey { pem: "  \n ".to_string(), passphrase: None };
        assert!(matches!(config.validate(), Err(SshError::MissingAuth)));
    }

    #[test]
    fn strict_policy_requires_parseable_fingerprint() {
        let mut config = valid_config();
        config.host_key_policy = SshHostKeyPolicy::Strict { fingerprint: "deadbeef".to_string() };
        assert!(matches!(config.validate(), Err(SshError::Ssh(_))));

        config.host_key_policy =
            SshHostKeyPolicy::Strict { fingerprint: "SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg".to_string() };
        assert!(config.validate().is_ok());
    }

    #[test]
    fn fingerprint_parse_accepts_sha256_form() {
        let parsed = parse_fingerprint("SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg").expect("parse");
        assert_eq!(parsed.sha256_base64, "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg");
    }

    #[test]
    fn fingerprint_parse_rejects_md5_form() {
        let error = parse_fingerprint("MD5:00:11:22:33").expect_err("md5 must be rejected");
        assert!(matches!(error, SshError::Ssh(_)));
    }

    #[test]
    fn fingerprint_parse_rejects_empty_body() {
        let error = parse_fingerprint("SHA256:").expect_err("empty body must be rejected");
        assert!(matches!(error, SshError::Ssh(_)));
    }

    #[test]
    fn fingerprint_url_safe_pin_matches_standard_presented() {
        // The same 32-byte digest encoded URL-safe (`-_`) must parse equal to
        // its standard (`+/`) encoding — otherwise a user who pins a URL-safe
        // form would be self-locked-out by a standard-unpadded presented value.
        let standard = parse_fingerprint("SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg").expect("standard");
        let url_safe = parse_fingerprint("SHA256:n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg").expect("url-safe");
        assert_eq!(standard, url_safe, "URL-safe pin must compare equal to the standard presented form");
        assert_eq!(standard.sha256_base64, url_safe.sha256_base64, "both canonicalise to the same string");
    }

    #[test]
    fn fingerprint_padded_pin_matches_unpadded() {
        // A padded standard encoding of a digest whose length needs `=` padding
        // must parse equal to its unpadded form.
        let unpadded = parse_fingerprint("SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg").expect("unpadded");
        let padded = parse_fingerprint("SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg=").expect("padded");
        assert_eq!(unpadded, padded, "padded pin must compare equal to the unpadded form");
    }

    #[test]
    fn fingerprint_parse_rejects_wrong_length() {
        // `SHA256:AAAA` decodes to 3 bytes, not 32 — it passes the old charset
        // check but can never match a real host key, so it must be rejected at
        // parse time.
        let error = parse_fingerprint("SHA256:AAAA").expect_err("a non-32-byte digest must be rejected");
        assert!(matches!(error, SshError::Ssh(_)));
    }

    #[test]
    fn debug_redacts_password_but_keeps_host_and_username() {
        let config = valid_config();
        let rendered = format!("{config:?}");
        assert!(!rendered.contains("correct-horse"), "password must not appear in Debug output");
        assert!(rendered.contains("<redacted>"), "Debug output must mark the password as redacted");
        assert!(rendered.contains("ssh.example"), "host is not a secret and stays visible");
        assert!(rendered.contains("alice"), "username is not a secret and stays visible");
    }

    #[test]
    fn debug_redacts_private_key_pem_and_passphrase() {
        let config = SshConfig {
            auth: SshAuth::PrivateKey {
                pem: "-----BEGIN OPENSSH PRIVATE KEY-----SECRETBYTES".to_string(),
                passphrase: Some("super-secret-passphrase".to_string()),
            },
            ..valid_config()
        };
        let rendered = format!("{config:?}");
        assert!(!rendered.contains("SECRETBYTES"), "private-key PEM must not appear in Debug output");
        assert!(!rendered.contains("super-secret-passphrase"), "passphrase must not appear in Debug output");
        assert!(rendered.contains("<redacted>"), "Debug output must mark secrets as redacted");
    }
}
