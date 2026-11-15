use serde::{Deserialize, Serialize};

#[derive(Clone, Default)]
pub struct MieruRelayConfig {
    pub username: Option<String>,
    pub password: Option<String>,
    pub protocol: String,
    pub multiplexing: String,
    pub mtu: i32,
    pub handshake_mode: String,
    pub traffic_pattern: Option<MieruTrafficPatternConfig>,
}

impl_redacted_debug!(MieruRelayConfig { protocol, multiplexing, mtu, handshake_mode });

/** Fully resolved values: the Kotlin owner applies upstream seeded defaults. */
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MieruTrafficPatternConfig {
    pub seed: i32,
    pub unlock_all: bool,
    pub tcp_fragment: MieruTcpFragmentConfig,
    pub nonce: MieruNoncePatternConfig,
    pub padding: MieruPaddingPatternConfig,
    pub low_entropy: MieruLowEntropyPatternConfig,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MieruTcpFragmentConfig {
    pub enable: bool,
    pub max_sleep_ms: u8,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MieruNoncePatternConfig {
    pub r#type: u8,
    #[serde(rename = "applyToAllUDPPacket")]
    pub apply_to_all_udp_packet: bool,
    pub min_len: u8,
    pub max_len: u8,
    #[serde(default)]
    pub custom_hex_strings: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MieruPaddingPatternConfig {
    pub max_middle_padding_len: u8,
    pub max_end_padding_len: u8,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MieruLowEntropyPatternConfig {
    pub mode: u8,
    pub mask_rotation: u8,
}

impl MieruTrafficPatternConfig {
    pub(crate) fn to_native(&self) -> std::io::Result<ripdpi_relay_tls_transports::MieruTrafficPattern> {
        let invalid = || std::io::Error::new(std::io::ErrorKind::InvalidInput, "invalid Mieru traffic pattern");
        if self.nonce.custom_hex_strings.len() > 256 {
            return Err(invalid());
        }
        let prefixes = self
            .nonce
            .custom_hex_strings
            .iter()
            .map(|value| {
                if value.len() > 24 || !value.len().is_multiple_of(2) {
                    return Err(invalid());
                }
                value
                    .as_bytes()
                    .as_chunks::<2>()
                    .0
                    .iter()
                    .map(|pair| {
                        let high = char::from(pair[0]).to_digit(16).ok_or_else(invalid)?;
                        let low = char::from(pair[1]).to_digit(16).ok_or_else(invalid)?;
                        u8::try_from(high * 16 + low).map_err(|_| invalid())
                    })
                    .collect::<std::io::Result<Vec<u8>>>()
            })
            .collect::<std::io::Result<Vec<Vec<u8>>>>()?;
        let pattern = ripdpi_relay_tls_transports::MieruTrafficPattern {
            fragment: self.tcp_fragment.enable,
            fragment_max_sleep_ms: self.tcp_fragment.max_sleep_ms,
            nonce_type: self.nonce.r#type,
            nonce_min_len: self.nonce.min_len,
            nonce_max_len: self.nonce.max_len,
            nonce_prefixes: prefixes,
            max_middle_padding: self.padding.max_middle_padding_len,
            max_end_padding: self.padding.max_end_padding_len,
            low_entropy_mode: self.low_entropy.mode,
            low_entropy_rotation: self.low_entropy.mask_rotation,
        };
        pattern.validate().map_err(|_| invalid())?;
        Ok(pattern)
    }
}
