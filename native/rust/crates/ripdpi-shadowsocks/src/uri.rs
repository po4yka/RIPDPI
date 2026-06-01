//! SIP002 `ss://` URI parser.
//!
//! # SIP002 format
//!
//! ```text
//! ss://base64url(method:password)@host:port[#tag]
//! ```
//!
//! # Legacy whole-URI base64
//!
//! ```text
//! ss://base64(method:password@host:port)[#tag]
//! ```

use crate::cipher::{Cipher, CipherError, SecretString};

/// Errors produced by URI parsing.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum UriError {
    /// The URI does not start with `ss://`.
    #[error("URI must start with ss://")]
    BadScheme,

    /// The URI is structurally malformed (missing `@`, missing port, etc.).
    #[error("malformed URI: {0}")]
    Malformed(String),

    /// The cipher in the URI is unsupported.
    #[error("cipher error: {0}")]
    Cipher(#[from] CipherError),

    /// Base64 decode failed.
    #[error("base64 decode error")]
    Base64,
}

/// A parsed Shadowsocks node URI.
#[derive(Debug)]
pub struct ShadowsocksUri {
    pub cipher: Cipher,
    pub password: SecretString,
    pub host: String,
    pub port: u16,
    pub tag: Option<String>,
}

impl ShadowsocksUri {
    /// Parses a `ss://` URI in SIP002 or legacy base64 format.
    pub fn parse(uri: &str) -> Result<Self, UriError> {
        let uri = uri.trim();
        if !uri.starts_with("ss://") {
            return Err(UriError::BadScheme);
        }
        let rest = &uri["ss://".len()..];

        // Strip optional #tag
        let (rest, tag) = if let Some(hash) = rest.rfind('#') {
            let tag = url_decode(&rest[hash + 1..]);
            (&rest[..hash], Some(tag))
        } else {
            (rest, None)
        };

        // SIP002: contains '@' — userinfo@host:port
        if let Some(at) = rest.rfind('@') {
            let userinfo_raw = &rest[..at];
            let hostport = &rest[at + 1..];
            let (host, port) = parse_host_port(hostport)?;

            // userinfo is either base64url(method:password) or plain method:password
            let userinfo = if userinfo_raw.contains(':') {
                userinfo_raw.to_owned()
            } else {
                decode_base64(userinfo_raw).ok_or(UriError::Base64)?
            };

            let colon = userinfo.find(':').ok_or_else(|| UriError::Malformed("missing ':' in userinfo".to_owned()))?;
            let method = &userinfo[..colon];
            let password = userinfo[colon + 1..].to_owned();
            if password.is_empty() {
                return Err(UriError::Malformed("empty password".to_owned()));
            }

            let cipher = Cipher::from_name(method)?;
            return Ok(Self { cipher, password: SecretString::new(password), host, port, tag });
        }

        // Legacy: entire rest (before #) is base64(method:password@host:port)
        let decoded = decode_base64(rest).ok_or(UriError::Base64)?;
        let at =
            decoded.rfind('@').ok_or_else(|| UriError::Malformed("missing '@' in decoded legacy URI".to_owned()))?;
        let userinfo = &decoded[..at];
        let hostport = &decoded[at + 1..];
        let (host, port) = parse_host_port(hostport)?;

        let colon =
            userinfo.find(':').ok_or_else(|| UriError::Malformed("missing ':' in legacy userinfo".to_owned()))?;
        let method = &userinfo[..colon];
        let password = userinfo[colon + 1..].to_owned();
        if password.is_empty() {
            return Err(UriError::Malformed("empty password in legacy URI".to_owned()));
        }

        let cipher = Cipher::from_name(method)?;
        Ok(Self { cipher, password: SecretString::new(password), host, port, tag })
    }
}

fn parse_host_port(hostport: &str) -> Result<(String, u16), UriError> {
    // Handle IPv6 literal [::1]:port
    let (host, port_str) = if hostport.starts_with('[') {
        let bracket_end = hostport.find(']').ok_or_else(|| UriError::Malformed("unclosed '[' in host".to_owned()))?;
        let host = hostport[1..bracket_end].to_owned();
        let rest = &hostport[bracket_end + 1..];
        let port_str = rest.strip_prefix(':').unwrap_or(rest);
        (host, port_str.to_owned())
    } else {
        let colon = hostport.rfind(':').ok_or_else(|| UriError::Malformed("missing port".to_owned()))?;
        (hostport[..colon].to_owned(), hostport[colon + 1..].to_owned())
    };

    if host.is_empty() {
        return Err(UriError::Malformed("empty host".to_owned()));
    }
    let port = port_str.parse::<u16>().map_err(|_| UriError::Malformed(format!("invalid port: {port_str}")))?;
    if port == 0 {
        return Err(UriError::Malformed("port must be > 0".to_owned()));
    }
    Ok((host, port))
}

fn decode_base64(s: &str) -> Option<String> {
    use base64::engine::Engine;
    // Try URL-safe no-pad, then standard with padding.
    let padded = pad_base64(s);
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(s.trim())
        .or_else(|_| base64::engine::general_purpose::URL_SAFE.decode(padded.trim()))
        .or_else(|_| base64::engine::general_purpose::STANDARD.decode(s.trim()))
        .ok()
        .and_then(|b| String::from_utf8(b).ok())
}

fn pad_base64(s: &str) -> String {
    let rem = s.len() % 4;
    if rem == 0 { s.to_owned() } else { format!("{}{}", s, "=".repeat(4 - rem)) }
}

fn url_decode(s: &str) -> String {
    // Minimal percent-decode for the fragment tag.
    let mut out = String::with_capacity(s.len());
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let (Some(h), Some(l)) = (hex_digit(bytes[i + 1]), hex_digit(bytes[i + 2]))
        {
            out.push(char::from(h << 4 | l));
            i += 3;
            continue;
        }
        if bytes[i] == b'+' {
            out.push(' ');
        } else {
            out.push(char::from(bytes[i]));
        }
        i += 1;
    }
    out
}

fn hex_digit(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}
