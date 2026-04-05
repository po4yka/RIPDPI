use base64::prelude::*;

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
}

/// Configuration for a VLESS+Reality connection.
#[derive(Debug, Clone)]
pub struct VlessRealityConfig {
    pub server: String,
    pub port: u16,
    /// Parsed 16-byte UUID.
    pub uuid: [u8; 16],
    pub server_name: String,
    /// Decoded 32-byte X25519 public key.
    pub reality_public_key: [u8; 32],
    /// Decoded short ID (0-8 bytes).
    pub reality_short_id: Vec<u8>,
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
            reality_public_key,
            reality_short_id,
        })
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
        )
        .unwrap();
        assert_eq!(cfg.port, 443);
        assert_eq!(cfg.reality_short_id.len(), 4);
    }
}
