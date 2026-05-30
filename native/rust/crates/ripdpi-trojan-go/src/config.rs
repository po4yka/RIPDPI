use std::fmt;

use crate::error::{Result, TrojanGoError};

/// The Trojan-Go stream-multiplexing mode.
///
/// Trojan-Go layers SMUX v1 over the TLS connection when `mux` is enabled;
/// `Off` keeps one TCP stream per relayed target.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrojanGoMux {
    Off,
    SmuxV1,
}

impl TrojanGoMux {
    /// Parse a Trojan-Go `mux` token, accepting only `off` and `smux_v1`.
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "off" => Ok(Self::Off),
            "smux_v1" => Ok(Self::SmuxV1),
            other => Err(TrojanGoError::UnsupportedMux(other.to_string())),
        }
    }

    /// The canonical wire token for this mux mode.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::SmuxV1 => "smux_v1",
        }
    }
}

/// The optional Trojan-Go inner-protocol cipher.
///
/// Trojan-Go can wrap the relayed stream in a Shadowsocks-AEAD inner layer for
/// extra obfuscation. `None` is the plain Trojan-Go inner protocol; the
/// `ShadowsocksAead` variant carries the AEAD cipher name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TrojanGoInner {
    None,
    ShadowsocksAead(String),
}

impl TrojanGoInner {
    /// Parse an optional Trojan-Go inner cipher token.
    ///
    /// `None` / `"none"` map to the plain inner protocol; the two
    /// Shadowsocks-AEAD ciphers (`aes-256-gcm`, `chacha20-ietf-poly1305`) map to
    /// [`TrojanGoInner::ShadowsocksAead`]. Every other token is rejected.
    pub fn parse(value: Option<&str>) -> Result<Self> {
        match value.map(str::trim).filter(|token| !token.is_empty()) {
            None | Some("none") => Ok(Self::None),
            Some(cipher @ ("aes-256-gcm" | "chacha20-ietf-poly1305")) => Ok(Self::ShadowsocksAead(cipher.to_string())),
            Some(other) => Err(TrojanGoError::UnsupportedInnerCipher(other.to_string())),
        }
    }
}

/// A validated Trojan-Go outbound configuration.
///
/// The [`fmt::Debug`] implementation is hand-written so the `password` (a stable
/// account secret) never lands in logs, telemetry, or goldens.
#[derive(Clone)]
pub struct TrojanGoConfig {
    pub server: String,
    pub port: u16,
    pub password: String,
    /// TLS SNI override.
    pub sni: Option<String>,
    /// WebSocket transport path (`ws`).
    pub ws_path: Option<String>,
    /// WebSocket `Host` header override (`ws`).
    pub ws_host: Option<String>,
    pub mux: TrojanGoMux,
    pub inner: TrojanGoInner,
}

impl TrojanGoConfig {
    /// Validate this configuration without driving any network I/O.
    ///
    /// Checks the port range and the non-empty-password invariant. The `mux` and
    /// `inner` knobs are validated at construction time by [`TrojanGoMux::parse`]
    /// and [`TrojanGoInner::parse`]; they are constrained by type here.
    pub fn validate(&self) -> Result<()> {
        if self.port == 0 {
            return Err(TrojanGoError::InvalidPort);
        }
        if self.password.is_empty() {
            return Err(TrojanGoError::EmptyPassword);
        }
        Ok(())
    }
}

impl fmt::Debug for TrojanGoConfig {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("TrojanGoConfig")
            .field("server", &self.server)
            .field("port", &self.port)
            .field("password", &"<redacted>")
            .field("sni", &self.sni)
            .field("ws_path", &self.ws_path)
            .field("ws_host", &self.ws_host)
            .field("mux", &self.mux)
            .field("inner", &self.inner)
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_config() -> TrojanGoConfig {
        TrojanGoConfig {
            server: "trojan-go.example".to_string(),
            port: 443,
            password: "correct-horse".to_string(),
            sni: Some("trojan-go.example".to_string()),
            ws_path: None,
            ws_host: None,
            mux: TrojanGoMux::Off,
            inner: TrojanGoInner::None,
        }
    }

    #[test]
    fn valid_config_validates() {
        assert!(valid_config().validate().is_ok());
    }

    #[test]
    fn empty_password_is_rejected() {
        let mut config = valid_config();
        config.password = String::new();
        assert!(matches!(config.validate(), Err(TrojanGoError::EmptyPassword)));
    }

    #[test]
    fn zero_port_is_rejected() {
        let mut config = valid_config();
        config.port = 0;
        assert!(matches!(config.validate(), Err(TrojanGoError::InvalidPort)));
    }

    #[test]
    fn mux_parse_round_trips() {
        for (token, expected) in [("off", TrojanGoMux::Off), ("smux_v1", TrojanGoMux::SmuxV1)] {
            assert_eq!(TrojanGoMux::parse(token).unwrap(), expected);
            assert_eq!(expected.as_str(), token);
        }
    }

    #[test]
    fn unknown_mux_is_rejected() {
        let error = TrojanGoMux::parse("smux_v2").expect_err("unknown mux must be rejected");
        match error {
            TrojanGoError::UnsupportedMux(token) => assert_eq!(token, "smux_v2"),
            other => panic!("expected UnsupportedMux, got {other:?}"),
        }
    }

    #[test]
    fn inner_parse_accepts_none_and_aead() {
        assert_eq!(TrojanGoInner::parse(None).unwrap(), TrojanGoInner::None);
        assert_eq!(TrojanGoInner::parse(Some("none")).unwrap(), TrojanGoInner::None);
        assert_eq!(TrojanGoInner::parse(Some("")).unwrap(), TrojanGoInner::None);
        assert_eq!(
            TrojanGoInner::parse(Some("aes-256-gcm")).unwrap(),
            TrojanGoInner::ShadowsocksAead("aes-256-gcm".to_string()),
        );
    }

    #[test]
    fn unknown_inner_cipher_is_rejected() {
        let error = TrojanGoInner::parse(Some("rc4-md5")).expect_err("unknown inner cipher must be rejected");
        match error {
            TrojanGoError::UnsupportedInnerCipher(token) => assert_eq!(token, "rc4-md5"),
            other => panic!("expected UnsupportedInnerCipher, got {other:?}"),
        }
    }

    #[test]
    fn debug_redacts_password() {
        let config = valid_config();
        let rendered = format!("{config:?}");
        assert!(!rendered.contains("correct-horse"), "password must not appear in Debug output");
        assert!(rendered.contains("<redacted>"), "Debug output must mark the password as redacted");
    }
}
