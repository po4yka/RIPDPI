use serde::{Deserialize, Serialize};

use super::constants::{
    ADAPTIVE_FAKE_TTL_DEFAULT_DELTA, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK, ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
    ADAPTIVE_FAKE_TTL_DEFAULT_MIN, FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT, FAKE_TLS_SNI_MODE_FIXED,
    FAKE_TLS_SOURCE_PROFILE,
};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiFakePacketConfig {
    pub fake_ttl: i32,
    #[serde(default)]
    pub adaptive_fake_ttl_enabled: bool,
    #[serde(default = "default_adaptive_fake_ttl_delta")]
    pub adaptive_fake_ttl_delta: i32,
    #[serde(default = "default_adaptive_fake_ttl_min")]
    pub adaptive_fake_ttl_min: i32,
    #[serde(default = "default_adaptive_fake_ttl_max")]
    pub adaptive_fake_ttl_max: i32,
    #[serde(default = "default_adaptive_fake_ttl_fallback")]
    pub adaptive_fake_ttl_fallback: i32,
    pub fake_sni: String,
    #[serde(default = "default_fake_payload_profile")]
    pub http_fake_profile: String,
    #[serde(default = "default_fake_tls_source")]
    pub fake_tls_source: String,
    #[serde(default)]
    pub fake_tls_secondary_profile: String,
    #[serde(default)]
    pub fake_tcp_timestamp_enabled: bool,
    #[serde(default)]
    pub fake_tcp_timestamp_delta_ticks: i32,
    #[serde(default)]
    pub fake_tls_use_original: bool,
    #[serde(default)]
    pub fake_tls_randomize: bool,
    #[serde(default)]
    pub fake_tls_dup_session_id: bool,
    #[serde(default)]
    pub fake_tls_pad_encap: bool,
    #[serde(default)]
    pub fake_tls_size: i32,
    #[serde(default = "default_fake_tls_sni_mode")]
    pub fake_tls_sni_mode: String,
    #[serde(default = "default_fake_payload_profile")]
    pub tls_fake_profile: String,
    #[serde(default = "default_fake_payload_profile")]
    pub udp_fake_profile: String,
    #[serde(default = "default_fake_offset_marker")]
    pub fake_offset_marker: String,
    pub oob_char: u8,
    pub drop_sack: bool,
    /// Apply the TCP_MD5SIG option to fake/decoy packets so a middlebox is
    /// desynced while the real server drops them. Root-only (CAP_NET_ADMIN):
    /// the conversion forces this to `false` unless root mode is enabled, and
    /// the runtime degrades gracefully when the option is unavailable.
    #[serde(default)]
    pub md5sig: bool,
    /// Override the TLS record-layer minor-version byte (the third byte of the
    /// TLS record header) on the genuine first-flight payload. `tls_minor_enabled`
    /// gates the override; when disabled the byte is left untouched. Maps to the
    /// CLI `-m/--tlsminor` knob and `DesyncActions::tlsminor: Option<u8>`.
    #[serde(default)]
    pub tls_minor_enabled: bool,
    #[serde(default)]
    pub tls_minor_value: u8,
    #[serde(default)]
    pub window_clamp: Option<u32>,
    #[serde(default)]
    pub wsize_window: Option<u32>,
    #[serde(default)]
    pub wsize_scale: Option<i32>,
    #[serde(default)]
    pub strip_timestamps: bool,
    #[serde(default)]
    pub ip_id_mode: String,
    #[serde(default)]
    pub quic_bind_low_port: bool,
    #[serde(default)]
    pub quic_migrate_after_handshake: bool,
    #[serde(default)]
    pub quic_fake_version: Option<u32>,
    /// Entropy detection mode: "popcount", "shannon", "combined", or empty (disabled).
    #[serde(default)]
    pub entropy_mode: String,
    /// Popcount target in permil (e.g. 3400 = 3.4). 0 = default (3.4).
    #[serde(default)]
    pub entropy_padding_target_permil: Option<u32>,
    /// Maximum entropy padding bytes. 0 = default (256).
    #[serde(default)]
    pub entropy_padding_max: Option<u32>,
    /// Shannon entropy target in permil (e.g. 7920 = 7.92). 0 = default (7.92).
    #[serde(default)]
    pub shannon_entropy_target_permil: Option<u32>,
    #[serde(default = "default_tls_fingerprint_profile")]
    pub tls_fingerprint_profile: String,
}

impl Default for ProxyUiFakePacketConfig {
    fn default() -> Self {
        Self {
            fake_ttl: 8,
            adaptive_fake_ttl_enabled: false,
            adaptive_fake_ttl_delta: default_adaptive_fake_ttl_delta(),
            adaptive_fake_ttl_min: default_adaptive_fake_ttl_min(),
            adaptive_fake_ttl_max: default_adaptive_fake_ttl_max(),
            adaptive_fake_ttl_fallback: default_adaptive_fake_ttl_fallback(),
            fake_sni: "www.iana.org".to_string(),
            http_fake_profile: default_fake_payload_profile(),
            fake_tls_source: default_fake_tls_source(),
            fake_tls_secondary_profile: String::new(),
            fake_tcp_timestamp_enabled: false,
            fake_tcp_timestamp_delta_ticks: 0,
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: default_fake_tls_sni_mode(),
            tls_fake_profile: default_fake_payload_profile(),
            udp_fake_profile: default_fake_payload_profile(),
            fake_offset_marker: default_fake_offset_marker(),
            oob_char: b'a',
            drop_sack: false,
            md5sig: false,
            tls_minor_enabled: false,
            tls_minor_value: 0,
            window_clamp: None,
            wsize_window: None,
            wsize_scale: None,
            strip_timestamps: false,
            ip_id_mode: String::new(),
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            quic_fake_version: None,
            entropy_mode: String::new(),
            entropy_padding_target_permil: None,
            entropy_padding_max: None,
            shannon_entropy_target_permil: None,
            tls_fingerprint_profile: default_tls_fingerprint_profile(),
        }
    }
}

fn default_fake_tls_sni_mode() -> String {
    FAKE_TLS_SNI_MODE_FIXED.to_string()
}

fn default_fake_tls_source() -> String {
    FAKE_TLS_SOURCE_PROFILE.to_string()
}

fn default_fake_payload_profile() -> String {
    FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string()
}

fn default_tls_fingerprint_profile() -> String {
    "chrome_stable".to_string()
}

fn default_fake_offset_marker() -> String {
    "0".to_string()
}

fn default_adaptive_fake_ttl_delta() -> i32 {
    ADAPTIVE_FAKE_TTL_DEFAULT_DELTA
}

fn default_adaptive_fake_ttl_min() -> i32 {
    ADAPTIVE_FAKE_TTL_DEFAULT_MIN
}

fn default_adaptive_fake_ttl_max() -> i32 {
    ADAPTIVE_FAKE_TTL_DEFAULT_MAX
}

fn default_adaptive_fake_ttl_fallback() -> i32 {
    ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK
}
