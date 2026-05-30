use std::fmt;

use crate::error::{MieruError, Result};

/// The lowest MTU Mieru accepts for its custom UDP/TCP transport.
const MIN_MTU: u16 = 1280;
/// The highest MTU Mieru accepts for its custom UDP/TCP transport.
const MAX_MTU: u16 = 1500;

/// The Mieru transport protocol.
///
/// Mieru tunnels traffic over either a custom TCP or a custom UDP carrier; both
/// apply the same replay-resistant handshake on top.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MieruProtocol {
    Tcp,
    Udp,
}

impl MieruProtocol {
    /// Parse a Mieru `protocol` token, accepting only `tcp` and `udp`.
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "tcp" => Ok(Self::Tcp),
            "udp" => Ok(Self::Udp),
            other => Err(MieruError::UnsupportedProtocol(other.to_string())),
        }
    }

    /// The canonical wire token for this protocol.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Tcp => "tcp",
            Self::Udp => "udp",
        }
    }
}

/// The Mieru multiplexing level.
///
/// Mieru can multiplex relayed streams over a shared session; the level trades
/// connection reuse against per-stream isolation. `Off` keeps one session per
/// relayed target.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MieruMux {
    Off,
    Low,
    Middle,
    High,
}

impl MieruMux {
    /// Parse a Mieru `multiplexing` token, accepting only `off`, `low`,
    /// `middle` and `high`.
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "off" => Ok(Self::Off),
            "low" => Ok(Self::Low),
            "middle" => Ok(Self::Middle),
            "high" => Ok(Self::High),
            other => Err(MieruError::UnsupportedMultiplexing(other.to_string())),
        }
    }

    /// The canonical wire token for this multiplexing level.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::Low => "low",
            Self::Middle => "middle",
            Self::High => "high",
        }
    }
}

/// A validated Mieru outbound configuration.
///
/// The [`fmt::Debug`] implementation is hand-written so the `password` (a stable
/// account secret) never lands in logs, telemetry, or goldens. The `username`
/// is not a secret and stays visible.
#[derive(Clone)]
pub struct MieruConfig {
    pub server: String,
    pub port: u16,
    pub username: String,
    pub password: String,
    pub protocol: MieruProtocol,
    pub multiplexing: MieruMux,
    pub mtu: u16,
}

impl MieruConfig {
    /// Validate this configuration without driving any network I/O.
    ///
    /// Checks the port range, the non-empty username / password invariants, and
    /// the MTU bounds. The `protocol` and `multiplexing` knobs are validated at
    /// construction time by [`MieruProtocol::parse`] and [`MieruMux::parse`];
    /// they are constrained by type here.
    pub fn validate(&self) -> Result<()> {
        if self.port == 0 {
            return Err(MieruError::InvalidPort);
        }
        if self.username.is_empty() {
            return Err(MieruError::EmptyUsername);
        }
        if self.password.is_empty() {
            return Err(MieruError::EmptyPassword);
        }
        if !(MIN_MTU..=MAX_MTU).contains(&self.mtu) {
            return Err(MieruError::InvalidMtu);
        }
        Ok(())
    }
}

impl fmt::Debug for MieruConfig {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("MieruConfig")
            .field("server", &self.server)
            .field("port", &self.port)
            .field("username", &self.username)
            .field("password", &"<redacted>")
            .field("protocol", &self.protocol)
            .field("multiplexing", &self.multiplexing)
            .field("mtu", &self.mtu)
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_config() -> MieruConfig {
        MieruConfig {
            server: "mieru.example".to_string(),
            port: 443,
            username: "alice".to_string(),
            password: "correct-horse".to_string(),
            protocol: MieruProtocol::Tcp,
            multiplexing: MieruMux::Middle,
            mtu: 1400,
        }
    }

    #[test]
    fn valid_config_validates() {
        assert!(valid_config().validate().is_ok());
    }

    #[test]
    fn empty_username_is_rejected() {
        let mut config = valid_config();
        config.username = String::new();
        assert!(matches!(config.validate(), Err(MieruError::EmptyUsername)));
    }

    #[test]
    fn empty_password_is_rejected() {
        let mut config = valid_config();
        config.password = String::new();
        assert!(matches!(config.validate(), Err(MieruError::EmptyPassword)));
    }

    #[test]
    fn zero_port_is_rejected() {
        let mut config = valid_config();
        config.port = 0;
        assert!(matches!(config.validate(), Err(MieruError::InvalidPort)));
    }

    #[test]
    fn out_of_range_mtu_is_rejected() {
        let mut config = valid_config();
        config.mtu = 1279;
        assert!(matches!(config.validate(), Err(MieruError::InvalidMtu)));
        config.mtu = 1501;
        assert!(matches!(config.validate(), Err(MieruError::InvalidMtu)));
    }

    #[test]
    fn protocol_parse_round_trips() {
        for (token, expected) in [("tcp", MieruProtocol::Tcp), ("udp", MieruProtocol::Udp)] {
            assert_eq!(MieruProtocol::parse(token).unwrap(), expected);
            assert_eq!(expected.as_str(), token);
        }
    }

    #[test]
    fn unknown_protocol_is_rejected() {
        let error = MieruProtocol::parse("quic").expect_err("unknown protocol must be rejected");
        match error {
            MieruError::UnsupportedProtocol(token) => assert_eq!(token, "quic"),
            other => panic!("expected UnsupportedProtocol, got {other:?}"),
        }
    }

    #[test]
    fn mux_parse_round_trips() {
        for (token, expected) in
            [("off", MieruMux::Off), ("low", MieruMux::Low), ("middle", MieruMux::Middle), ("high", MieruMux::High)]
        {
            assert_eq!(MieruMux::parse(token).unwrap(), expected);
            assert_eq!(expected.as_str(), token);
        }
    }

    #[test]
    fn unknown_mux_is_rejected() {
        let error = MieruMux::parse("extreme").expect_err("unknown mux must be rejected");
        match error {
            MieruError::UnsupportedMultiplexing(token) => assert_eq!(token, "extreme"),
            other => panic!("expected UnsupportedMultiplexing, got {other:?}"),
        }
    }

    #[test]
    fn debug_redacts_password_but_keeps_username() {
        let config = valid_config();
        let rendered = format!("{config:?}");
        assert!(!rendered.contains("correct-horse"), "password must not appear in Debug output");
        assert!(rendered.contains("<redacted>"), "Debug output must mark the password as redacted");
        assert!(rendered.contains("alice"), "username is not a secret and stays visible");
    }
}
