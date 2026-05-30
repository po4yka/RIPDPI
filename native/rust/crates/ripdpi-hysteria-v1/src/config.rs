use std::fmt;

use crate::error::{HysteriaV1Error, Result};

/// The Hysteria v1 authentication payload encoding.
///
/// Hysteria v1 authenticates with a single auth payload; the encoding selects
/// how the configured `auth_payload` string is interpreted before it is sent on
/// the wire.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HysteriaV1AuthType {
    /// The payload is a UTF-8 string used verbatim.
    String,
    /// The payload is base64-encoded.
    Base64,
}

impl HysteriaV1AuthType {
    /// Parse a Hysteria v1 `auth_type` token, accepting only `string` and
    /// `base64`.
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "string" => Ok(Self::String),
            "base64" => Ok(Self::Base64),
            other => Err(HysteriaV1Error::UnsupportedAuthType(other.to_string())),
        }
    }

    /// The canonical wire token for this auth type.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::String => "string",
            Self::Base64 => "base64",
        }
    }
}

/// The Hysteria v1 transport protocol.
///
/// v1 multiplexes traffic over a custom QUIC framing carried by one of three
/// obfuscation carriers: plain `udp`, the `wechat-video` masquerade, or
/// `faketcp` (raw-socket TCP mimicry).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HysteriaV1Protocol {
    Udp,
    WechatVideo,
    FakeTcp,
}

impl HysteriaV1Protocol {
    /// Parse a Hysteria v1 `protocol` token, accepting only `udp`,
    /// `wechat-video` and `faketcp`.
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "udp" => Ok(Self::Udp),
            "wechat-video" => Ok(Self::WechatVideo),
            "faketcp" => Ok(Self::FakeTcp),
            other => Err(HysteriaV1Error::UnsupportedProtocol(other.to_string())),
        }
    }

    /// The canonical wire token for this protocol.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Udp => "udp",
            Self::WechatVideo => "wechat-video",
            Self::FakeTcp => "faketcp",
        }
    }
}

/// A validated legacy Hysteria v1 outbound configuration.
///
/// The [`fmt::Debug`] implementation is hand-written so the `auth_payload` (the
/// account secret) and the `obfuscation` password never land in logs,
/// telemetry, or goldens. The remaining fields are not secrets and stay
/// visible.
#[derive(Clone)]
pub struct HysteriaV1Config {
    pub server: String,
    pub port: u16,
    pub auth_type: HysteriaV1AuthType,
    pub auth_payload: String,
    pub obfuscation: Option<String>,
    pub protocol: HysteriaV1Protocol,
    pub up_mbps: u32,
    pub down_mbps: u32,
    pub sni: Option<String>,
    pub alpn: Option<String>,
}

impl HysteriaV1Config {
    /// Validate this configuration without driving any network I/O.
    ///
    /// Checks the port range, the non-empty auth-payload invariant, and the
    /// mandatory non-zero up/down bandwidth (the v1 brutal controller REQUIRES
    /// bandwidth, unlike Hysteria2). The `auth_type` and `protocol` knobs are
    /// validated at construction time by [`HysteriaV1AuthType::parse`] and
    /// [`HysteriaV1Protocol::parse`]; they are constrained by type here.
    pub fn validate(&self) -> Result<()> {
        if self.port == 0 {
            return Err(HysteriaV1Error::InvalidPort);
        }
        if self.auth_payload.is_empty() {
            return Err(HysteriaV1Error::EmptyAuth);
        }
        if self.up_mbps == 0 || self.down_mbps == 0 {
            return Err(HysteriaV1Error::InvalidBandwidth);
        }
        Ok(())
    }
}

impl fmt::Debug for HysteriaV1Config {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("HysteriaV1Config")
            .field("server", &self.server)
            .field("port", &self.port)
            .field("auth_type", &self.auth_type)
            .field("auth_payload", &"<redacted>")
            .field("obfuscation", &self.obfuscation.as_ref().map(|_| "<redacted>"))
            .field("protocol", &self.protocol)
            .field("up_mbps", &self.up_mbps)
            .field("down_mbps", &self.down_mbps)
            .field("sni", &self.sni)
            .field("alpn", &self.alpn)
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_config() -> HysteriaV1Config {
        HysteriaV1Config {
            server: "hysteria.example".to_string(),
            port: 443,
            auth_type: HysteriaV1AuthType::String,
            auth_payload: "correct-horse".to_string(),
            obfuscation: Some("obfs-secret".to_string()),
            protocol: HysteriaV1Protocol::Udp,
            up_mbps: 10,
            down_mbps: 50,
            sni: Some("hysteria.example".to_string()),
            alpn: Some("h3".to_string()),
        }
    }

    #[test]
    fn valid_config_validates() {
        assert!(valid_config().validate().is_ok());
    }

    #[test]
    fn empty_auth_is_rejected() {
        let mut config = valid_config();
        config.auth_payload = String::new();
        assert!(matches!(config.validate(), Err(HysteriaV1Error::EmptyAuth)));
    }

    #[test]
    fn zero_port_is_rejected() {
        let mut config = valid_config();
        config.port = 0;
        assert!(matches!(config.validate(), Err(HysteriaV1Error::InvalidPort)));
    }

    #[test]
    fn zero_bandwidth_is_rejected() {
        let mut config = valid_config();
        config.up_mbps = 0;
        assert!(matches!(config.validate(), Err(HysteriaV1Error::InvalidBandwidth)));
        let mut config = valid_config();
        config.down_mbps = 0;
        assert!(matches!(config.validate(), Err(HysteriaV1Error::InvalidBandwidth)));
    }

    #[test]
    fn auth_type_parse_round_trips() {
        for (token, expected) in [("string", HysteriaV1AuthType::String), ("base64", HysteriaV1AuthType::Base64)] {
            assert_eq!(HysteriaV1AuthType::parse(token).unwrap(), expected);
            assert_eq!(expected.as_str(), token);
        }
    }

    #[test]
    fn unknown_auth_type_is_rejected() {
        let error = HysteriaV1AuthType::parse("hex").expect_err("unknown auth type must be rejected");
        match error {
            HysteriaV1Error::UnsupportedAuthType(token) => assert_eq!(token, "hex"),
            other => panic!("expected UnsupportedAuthType, got {other:?}"),
        }
    }

    #[test]
    fn protocol_parse_round_trips() {
        for (token, expected) in [
            ("udp", HysteriaV1Protocol::Udp),
            ("wechat-video", HysteriaV1Protocol::WechatVideo),
            ("faketcp", HysteriaV1Protocol::FakeTcp),
        ] {
            assert_eq!(HysteriaV1Protocol::parse(token).unwrap(), expected);
            assert_eq!(expected.as_str(), token);
        }
    }

    #[test]
    fn unknown_protocol_is_rejected() {
        let error = HysteriaV1Protocol::parse("quic").expect_err("unknown protocol must be rejected");
        match error {
            HysteriaV1Error::UnsupportedProtocol(token) => assert_eq!(token, "quic"),
            other => panic!("expected UnsupportedProtocol, got {other:?}"),
        }
    }

    #[test]
    fn debug_redacts_auth_payload_and_obfuscation() {
        let config = valid_config();
        let rendered = format!("{config:?}");
        assert!(!rendered.contains("correct-horse"), "auth payload must not appear in Debug output");
        assert!(!rendered.contains("obfs-secret"), "obfuscation must not appear in Debug output");
        assert!(rendered.contains("<redacted>"), "Debug output must mark secrets as redacted");
        assert!(rendered.contains("hysteria.example"), "server is not a secret and stays visible");
    }
}
