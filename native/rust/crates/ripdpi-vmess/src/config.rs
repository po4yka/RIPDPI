use std::fmt;

use crate::error::{Result, VmessError};

/// The AEAD-only VMess security ciphers this foundation accepts.
///
/// Legacy ciphers (`auto`, `none`, `md5`, `aes-128-cfb`, ...) are rejected by
/// [`VmessSecurity::parse`] with [`VmessError::LegacySecurityRejected`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VmessSecurity {
    Aes128Gcm,
    Chacha20Poly1305,
}

impl VmessSecurity {
    /// Parse a VMess `security` token, accepting only the two AEAD ciphers and
    /// rejecting every legacy / insecure mode by name.
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "aes-128-gcm" => Ok(Self::Aes128Gcm),
            "chacha20-poly1305" => Ok(Self::Chacha20Poly1305),
            other => Err(VmessError::LegacySecurityRejected(other.to_string())),
        }
    }

    /// The canonical wire token for this cipher.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Aes128Gcm => "aes-128-gcm",
            Self::Chacha20Poly1305 => "chacha20-poly1305",
        }
    }
}

/// The VMess stream transport.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VmessTransport {
    Tcp,
    Ws,
    H2,
    Grpc,
}

impl VmessTransport {
    /// Parse a VMess `transport` token.
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "tcp" => Ok(Self::Tcp),
            "ws" => Ok(Self::Ws),
            "h2" => Ok(Self::H2),
            "grpc" => Ok(Self::Grpc),
            other => Err(VmessError::UnsupportedTransport(other.to_string())),
        }
    }

    /// The canonical wire token for this transport.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Tcp => "tcp",
            Self::Ws => "ws",
            Self::H2 => "h2",
            Self::Grpc => "grpc",
        }
    }
}

/// A validated VMess outbound configuration.
///
/// The [`fmt::Debug`] implementation is hand-written so the `uuid` (a stable
/// account secret) never lands in logs, telemetry, or goldens.
#[derive(Clone)]
pub struct VmessConfig {
    pub server: String,
    pub port: u16,
    pub uuid: String,
    /// The legacy alterId knob. Only `0` is valid; any other value is rejected
    /// by [`VmessConfig::validate`] with [`VmessError::AlterIdUnsupported`].
    pub alter_id: u32,
    pub security: VmessSecurity,
    pub transport: VmessTransport,
    /// WebSocket transport path (`ws`).
    pub ws_path: Option<String>,
    /// WebSocket `Host` header override (`ws`).
    pub ws_host: Option<String>,
    /// gRPC service name (`grpc`).
    pub grpc_service: Option<String>,
    /// HTTP/2 transport path (`h2`).
    pub h2_path: Option<String>,
    /// HTTP/2 `Host` header override (`h2`).
    pub h2_host: Option<String>,
}

impl VmessConfig {
    /// Validate this configuration without driving any network I/O.
    ///
    /// Checks the port range, RFC 4122 UUID shape, and the alterId-must-be-zero
    /// invariant. The security cipher is validated at construction time by
    /// [`VmessSecurity::parse`]; it is AEAD-only by type.
    pub fn validate(&self) -> Result<()> {
        if self.port == 0 {
            return Err(VmessError::InvalidPort);
        }
        if !is_rfc4122_uuid(&self.uuid) {
            return Err(VmessError::InvalidUuid);
        }
        if self.alter_id != 0 {
            return Err(VmessError::AlterIdUnsupported);
        }
        Ok(())
    }
}

impl fmt::Debug for VmessConfig {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("VmessConfig")
            .field("server", &self.server)
            .field("port", &self.port)
            .field("uuid", &"<redacted>")
            .field("alter_id", &self.alter_id)
            .field("security", &self.security)
            .field("transport", &self.transport)
            .field("ws_path", &self.ws_path)
            .field("ws_host", &self.ws_host)
            .field("grpc_service", &self.grpc_service)
            .field("h2_path", &self.h2_path)
            .field("h2_host", &self.h2_host)
            .finish()
    }
}

/// Validate the RFC 4122 `8-4-4-4-12` hex shape (case-insensitive).
///
/// This is a structural check only: it verifies the canonical grouping and
/// hex-digit content, which is sufficient to reject malformed profiles before
/// the (stubbed) handshake.
fn is_rfc4122_uuid(value: &str) -> bool {
    const GROUP_LENGTHS: [usize; 5] = [8, 4, 4, 4, 12];
    let mut groups = value.split('-');
    for expected in GROUP_LENGTHS {
        let Some(group) = groups.next() else {
            return false;
        };
        if group.len() != expected || !group.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return false;
        }
    }
    groups.next().is_none()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_config() -> VmessConfig {
        VmessConfig {
            server: "vmess.example".to_string(),
            port: 443,
            uuid: "b831381d-6324-4d53-ad4f-8cda48b30811".to_string(),
            alter_id: 0,
            security: VmessSecurity::Aes128Gcm,
            transport: VmessTransport::Tcp,
            ws_path: None,
            ws_host: None,
            grpc_service: None,
            h2_path: None,
            h2_host: None,
        }
    }

    #[test]
    fn valid_aead_config_validates() {
        assert!(valid_config().validate().is_ok());
    }

    #[test]
    fn security_parse_accepts_aead_ciphers() {
        assert_eq!(VmessSecurity::parse("aes-128-gcm").unwrap(), VmessSecurity::Aes128Gcm);
        assert_eq!(VmessSecurity::parse("chacha20-poly1305").unwrap(), VmessSecurity::Chacha20Poly1305);
    }

    #[test]
    fn security_parse_rejects_legacy_modes() {
        for legacy in ["auto", "none", "md5", "aes-128-cfb"] {
            let error = VmessSecurity::parse(legacy).expect_err("legacy cipher must be rejected");
            match error {
                VmessError::LegacySecurityRejected(token) => assert_eq!(token, legacy),
                other => panic!("expected LegacySecurityRejected for {legacy}, got {other:?}"),
            }
        }
    }

    #[test]
    fn non_zero_alter_id_is_rejected() {
        let mut config = valid_config();
        config.alter_id = 64;
        assert!(matches!(config.validate(), Err(VmessError::AlterIdUnsupported)));
    }

    #[test]
    fn malformed_uuid_is_rejected() {
        let mut config = valid_config();
        config.uuid = "not-a-uuid".to_string();
        assert!(matches!(config.validate(), Err(VmessError::InvalidUuid)));
    }

    #[test]
    fn zero_port_is_rejected() {
        let mut config = valid_config();
        config.port = 0;
        assert!(matches!(config.validate(), Err(VmessError::InvalidPort)));
    }

    #[test]
    fn debug_redacts_uuid() {
        let config = valid_config();
        let rendered = format!("{config:?}");
        assert!(!rendered.contains("b831381d-6324-4d53-ad4f-8cda48b30811"), "uuid must not appear in Debug output");
        assert!(rendered.contains("<redacted>"), "Debug output must mark the uuid as redacted");
    }

    #[test]
    fn transport_parse_round_trips() {
        for (token, expected) in [
            ("tcp", VmessTransport::Tcp),
            ("ws", VmessTransport::Ws),
            ("h2", VmessTransport::H2),
            ("grpc", VmessTransport::Grpc),
        ] {
            assert_eq!(VmessTransport::parse(token).unwrap(), expected);
            assert_eq!(expected.as_str(), token);
        }
        assert!(matches!(VmessTransport::parse("quic"), Err(VmessError::UnsupportedTransport(_))));
    }
}
