use base64::prelude::*;

use crate::mux::{MuxConfigError, VlessMuxConfig};

/// Errors that can occur when parsing VLESS+Reality configuration from strings.
#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("invalid UUID: {0}")]
    InvalidUuid(String),
    #[error("invalid port: {0}")]
    InvalidPort(i32),
    #[error("invalid reality public key (base64): {0}")]
    InvalidPublicKey(String),
    #[error("invalid reality short ID (hex): {0}")]
    InvalidShortId(String),
    #[error("invalid mux config: {0}")]
    InvalidMux(#[from] MuxConfigError),
}

/// Configuration for a VLESS+Reality connection.
#[derive(Debug, Clone)]
pub struct VlessRealityConfig {
    pub server: String,
    pub port: u16,
    /// Parsed 16-byte UUID.
    pub uuid: [u8; 16],
    pub server_name: String,
    pub tls_fingerprint_profile: String,
    /// Decoded 32-byte X25519 public key.
    pub reality_public_key: [u8; 32],
    /// Decoded short ID (0-8 bytes).
    pub reality_short_id: Vec<u8>,
    /// Wire-multiplexing config when the subscription requested `mux:
    /// sing-mux` / `mux: yamux`; `None` for one-connection-per-flow.
    pub mux: Option<VlessMuxConfig>,
}

impl VlessRealityConfig {
    /// Build a config from string representations (as passed from the Kotlin layer).
    pub fn from_strings(
        server: &str,
        port: i32,
        uuid_str: &str,
        server_name: &str,
        reality_public_key_b64: &str,
        reality_short_id_hex: &str,
        tls_fingerprint_profile: &str,
    ) -> Result<Self, ConfigError> {
        let port = u16::try_from(port).map_err(|_| ConfigError::InvalidPort(port))?;

        let uuid = parse_uuid(uuid_str).map_err(|_| ConfigError::InvalidUuid(uuid_str.to_owned()))?;

        let pub_key_bytes = BASE64_STANDARD
            .decode(reality_public_key_b64)
            .map_err(|_| ConfigError::InvalidPublicKey(reality_public_key_b64.to_owned()))?;
        if pub_key_bytes.len() != 32 {
            return Err(ConfigError::InvalidPublicKey(format!("expected 32 bytes, got {}", pub_key_bytes.len())));
        }
        let mut reality_public_key = [0u8; 32];
        reality_public_key.copy_from_slice(&pub_key_bytes);

        let reality_short_id = if reality_short_id_hex.is_empty() {
            Vec::new()
        } else {
            hex::decode(reality_short_id_hex)
                .map_err(|_| ConfigError::InvalidShortId(reality_short_id_hex.to_owned()))?
        };
        if reality_short_id.len() > 8 {
            return Err(ConfigError::InvalidShortId(format!("expected 0-8 bytes, got {}", reality_short_id.len())));
        }

        Ok(Self {
            server: server.to_owned(),
            port,
            uuid,
            server_name: server_name.to_owned(),
            tls_fingerprint_profile: tls_fingerprint_profile.to_owned(),
            reality_public_key,
            reality_short_id,
            mux: None,
        })
    }

    /// Attach a wire-multiplexing config (builder style). NekoBox / sing-box
    /// subscriptions that carry a `mux` block produce a [`VlessMuxConfig`]
    /// which is threaded onto the profile here.
    pub fn with_mux(mut self, mux: VlessMuxConfig) -> Self {
        self.mux = Some(mux);
        self
    }

    /// Parse a `mux` block from its string fields and attach it to this
    /// config. The field semantics match [`VlessMuxConfig::from_strings`]:
    /// `0` for the numeric fields means "use the default".
    pub fn with_mux_strings(
        self,
        protocol: &str,
        max_concurrent_streams: u32,
        per_connection_kbps: u32,
        sing_mux_padding_max: u32,
    ) -> Result<Self, ConfigError> {
        let mux =
            VlessMuxConfig::from_strings(protocol, max_concurrent_streams, per_connection_kbps, sing_mux_padding_max)?;
        Ok(self.with_mux(mux))
    }
}

/// Parse a UUID string (with or without dashes) into 16 bytes.
fn parse_uuid(s: &str) -> Result<[u8; 16], ()> {
    let hex_only: String = s.chars().filter(|c| *c != '-').collect();
    if hex_only.len() != 32 {
        return Err(());
    }
    let bytes = hex::decode(&hex_only).map_err(|_| ())?;
    let mut uuid = [0u8; 16];
    uuid.copy_from_slice(&bytes);
    Ok(uuid)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_uuid_with_dashes() {
        let uuid = parse_uuid("550e8400-e29b-41d4-a716-446655440000").unwrap();
        assert_eq!(uuid[0], 0x55);
        assert_eq!(uuid[15], 0x00);
    }

    #[test]
    fn parse_uuid_without_dashes() {
        let uuid = parse_uuid("550e8400e29b41d4a716446655440000").unwrap();
        assert_eq!(uuid[0], 0x55);
    }

    #[test]
    fn from_strings_valid() {
        // 32-byte key encoded as base64
        let key = BASE64_STANDARD.encode([0xABu8; 32]);
        let cfg = VlessRealityConfig::from_strings(
            "example.com",
            443,
            "550e8400-e29b-41d4-a716-446655440000",
            "www.example.com",
            &key,
            "abcd1234",
            "chrome_stable",
        )
        .unwrap();
        assert_eq!(cfg.port, 443);
        assert_eq!(cfg.reality_short_id.len(), 4);
        // A plain `from_strings` profile carries no mux block by default.
        assert!(cfg.mux.is_none());
    }

    fn sample_config() -> VlessRealityConfig {
        let key = BASE64_STANDARD.encode([0xABu8; 32]);
        VlessRealityConfig::from_strings(
            "example.com",
            443,
            "550e8400-e29b-41d4-a716-446655440000",
            "www.example.com",
            &key,
            "abcd1234",
            "chrome_stable",
        )
        .expect("valid base config")
    }

    #[test]
    fn with_mux_strings_attaches_a_sing_mux_block() {
        let cfg = sample_config().with_mux_strings("sing-mux", 16, 4096, 256).expect("valid mux block");
        let mux = cfg.mux.expect("mux block present");
        assert_eq!(mux.protocol, crate::mux::VlessMuxProtocol::SingMux);
        assert_eq!(mux.max_concurrent_streams, 16);
        assert_eq!(mux.per_connection_kbps, Some(4096));
        assert_eq!(mux.sing_mux_padding_max, Some(256));
    }

    #[test]
    fn with_mux_strings_attaches_a_yamux_block() {
        let cfg = sample_config().with_mux_strings("yamux", 0, 0, 0).expect("valid mux block");
        let mux = cfg.mux.expect("mux block present");
        assert_eq!(mux.protocol, crate::mux::VlessMuxProtocol::Yamux);
    }

    #[test]
    fn with_mux_strings_rejects_unknown_protocol() {
        let err = sample_config().with_mux_strings("not-a-mux", 0, 0, 0).expect_err("unknown mux must be rejected");
        assert!(matches!(err, ConfigError::InvalidMux(_)));
    }

    #[test]
    fn with_mux_builder_threads_a_config_through() {
        let mux = VlessMuxConfig::new(crate::mux::VlessMuxProtocol::SingMux);
        let cfg = sample_config().with_mux(mux);
        assert_eq!(cfg.mux, Some(mux));
    }
}
