/// The single relay native-config wire schema version this build understands.
///
/// Mirrors the Kotlin `RelayNativeConfigSchemaVersion` constant. A payload
/// carrying any other version is rejected by [`validate_schema_version`].
const SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION: u32 = 2;

/// `serde(default)` provider for the additive `schemaVersion` envelope field.
///
/// A legacy payload with no `schemaVersion` key is treated as this build's
/// current relay schema version, matching the Kotlin default.
fn default_native_config_schema_version() -> u32 {
    SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION
}

/// Rejects a `schemaVersion` envelope value this build does not support.
fn validate_schema_version(found: u32) -> Result<(), RelayConfigError> {
    if found == SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION {
        Ok(())
    } else {
        Err(RelayConfigError::UnsupportedSchemaVersion { found })
    }
}

/// Typed relay config error surfaced through the [`ResolvedRelayRuntimeConfig`]
/// deserialize path (wrapped in a `serde` error via `Error::custom`).
///
/// `relay-core` does not depend on `thiserror`, so `Display` / `Error` are
/// implemented by hand for this single-variant enum.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum RelayConfigError {
    UnsupportedSchemaVersion { found: u32 },
}

impl std::fmt::Display for RelayConfigError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RelayConfigError::UnsupportedSchemaVersion { found } => write!(
                formatter,
                "unsupported native config schemaVersion {found}; \
                 this build supports {SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION}"
            ),
        }
    }
}

impl std::error::Error for RelayConfigError {}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct FlatResolvedRelayRuntimeConfig {
    #[serde(default = "default_native_config_schema_version")]
    pub schema_version: u32,
    pub enabled: bool,
    pub kind: String,
    pub profile_id: String,
    pub outbound_bind_ip: String,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    #[serde(default)]
    pub reality_public_key: String,
    #[serde(default)]
    pub reality_short_id: String,
    #[serde(default)]
    pub vless_transport: String,
    #[serde(default)]
    pub xhttp_path: String,
    #[serde(default)]
    pub xhttp_host: String,
    #[serde(default)]
    pub cloudflare_tunnel_mode: String,
    #[serde(default)]
    pub cloudflare_publish_local_origin_url: String,
    #[serde(default)]
    pub cloudflare_credentials_ref: String,
    #[serde(default)]
    pub chain_entry_server: String,
    #[serde(default)]
    pub chain_entry_port: i32,
    #[serde(default)]
    pub chain_entry_server_name: String,
    #[serde(default)]
    pub chain_entry_public_key: String,
    #[serde(default)]
    pub chain_entry_short_id: String,
    #[serde(default)]
    pub chain_entry_profile_id: String,
    #[serde(default)]
    pub chain_exit_server: String,
    #[serde(default)]
    pub chain_exit_port: i32,
    #[serde(default)]
    pub chain_exit_server_name: String,
    #[serde(default)]
    pub chain_exit_public_key: String,
    #[serde(default)]
    pub chain_exit_short_id: String,
    #[serde(default)]
    pub chain_exit_profile_id: String,
    #[serde(default)]
    pub masque_url: String,
    #[serde(default)]
    pub masque_use_http2_fallback: bool,
    #[serde(default)]
    pub masque_cloudflare_geohash_enabled: bool,
    #[serde(default)]
    pub tuic_zero_rtt: bool,
    #[serde(default)]
    pub tuic_congestion_control: String,
    #[serde(default)]
    pub shadow_tls_inner_profile_id: String,
    #[serde(default)]
    pub shadow_tls_inner: Option<ResolvedShadowTlsInnerRelayConfig>,
    #[serde(default)]
    pub trojan_root_certificate_pem: Option<String>,
    #[serde(default)]
    pub naive_path: String,
    pub local_socks_host: String,
    pub local_socks_port: i32,
    pub udp_enabled: bool,
    pub tcp_fallback_enabled: bool,
    pub quic_bind_low_port: bool,
    pub quic_migrate_after_handshake: bool,
    #[serde(default)]
    pub vless_uuid: Option<String>,
    #[serde(default)]
    pub chain_entry_uuid: Option<String>,
    #[serde(default)]
    pub chain_exit_uuid: Option<String>,
    #[serde(default)]
    pub hysteria_password: Option<String>,
    #[serde(default)]
    pub hysteria_salamander_key: Option<String>,
    #[serde(default)]
    pub tuic_uuid: Option<String>,
    #[serde(default)]
    pub tuic_password: Option<String>,
    #[serde(default)]
    pub shadow_tls_password: Option<String>,
    #[serde(default)]
    pub trojan_password: Option<String>,
    #[serde(default)]
    pub naive_username: Option<String>,
    #[serde(default)]
    pub naive_password: Option<String>,
    pub tls_fingerprint_profile: String,
    #[serde(default)]
    pub masque_auth_mode: Option<String>,
    #[serde(default)]
    pub masque_auth_token: Option<String>,
    #[serde(default)]
    pub masque_client_certificate_chain_pem: Option<String>,
    #[serde(default)]
    pub masque_client_private_key_pem: Option<String>,
    #[serde(default)]
    pub masque_cloudflare_geohash_header: Option<String>,
    #[serde(default)]
    pub masque_privacy_pass_provider_url: Option<String>,
    #[serde(default)]
    pub masque_privacy_pass_provider_auth_token: Option<String>,
    #[serde(default)]
    pub cloudflare_tunnel_token: Option<String>,
    #[serde(default)]
    pub cloudflare_tunnel_credentials_json: Option<String>,
    #[serde(default)]
    pub finalmask: ResolvedRelayFinalmaskConfig,
}
