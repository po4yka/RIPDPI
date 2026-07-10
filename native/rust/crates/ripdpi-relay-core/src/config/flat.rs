/// The current relay native-config wire schema version this build emits and
/// treats as the default.
///
/// Mirrors the Kotlin `RelayNativeConfigSchemaVersion` constant. Version 7
/// generalized the chain-relay section model to an ordered, bounded hop list
/// (2..=4 hops); version 8 removed the legacy VMess / Trojan-Go / Hysteria-v1
/// relay kinds (ADR 0004). The flat wire field set is unchanged across
/// v6/v7/v8, so legacy v6 payloads migrate forward losslessly and are still
/// accepted — see [`MIN_SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION`].
const SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION: u32 = 8;

/// The oldest relay native-config wire schema version this build still accepts.
///
/// Mirrors the Kotlin `RelayNativeConfigMinSchemaVersion` constant. A v6
/// payload carries the same flat two-hop field set as v7, so it deserializes
/// without conversion and is folded into the 2-element hop list on the Kotlin
/// side. Any version below this floor or above
/// [`SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION`] is rejected.
const MIN_SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION: u32 = 6;

/// `serde(default)` provider for the additive `schemaVersion` envelope field.
///
/// A legacy payload with no `schemaVersion` key is treated as this build's
/// current relay schema version, matching the Kotlin default.
fn default_native_config_schema_version() -> u32 {
    SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION
}

fn default_vless_flow() -> String {
    "xtls-rprx-vision".to_string()
}

fn default_xhttp_mode() -> String {
    "auto".to_string()
}

fn default_vless_transport() -> String {
    "reality_tcp".to_string()
}

fn default_cloudflare_tunnel_mode() -> String {
    "consume_existing".to_string()
}

fn default_tuic_congestion_control() -> String {
    "bbr".to_string()
}

fn default_tls_fingerprint_profile() -> String {
    "chrome_stable".to_string()
}

fn default_mieru_protocol() -> String {
    "tcp".to_string()
}

fn default_mieru_multiplexing() -> String {
    "middle".to_string()
}

const fn default_mieru_mtu() -> i32 {
    1400
}

fn default_ssh_auth_type() -> String {
    "password".to_string()
}

/// Rejects a `schemaVersion` envelope value this build does not support.
///
/// Accepts the inclusive range
/// `[MIN_SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION, SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION]`
/// — the v6→v7 chain-relay generalization left the flat wire shape unchanged,
/// so both versions deserialize identically.
fn validate_schema_version(found: u32) -> Result<(), RelayConfigError> {
    if (MIN_SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION..=SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION)
        .contains(&found)
    {
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
                "unsupported native config schemaVersion {found}; this build supports \
                 {MIN_SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION}..={SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION}"
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
    #[serde(default)]
    pub outbound_bind_ip: String,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    #[serde(default)]
    pub reality_public_key: String,
    #[serde(default)]
    pub reality_short_id: String,
    #[serde(default = "default_vless_flow")]
    pub vless_flow: String,
    #[serde(default = "default_vless_transport")]
    pub vless_transport: String,
    #[serde(default)]
    pub xhttp_path: String,
    #[serde(default)]
    pub xhttp_host: String,
    #[serde(default = "default_xhttp_mode")]
    pub xhttp_mode: String,
    #[serde(default = "default_cloudflare_tunnel_mode")]
    pub cloudflare_tunnel_mode: String,
    #[serde(default)]
    pub cloudflare_publish_local_origin_url: String,
    #[serde(default)]
    pub cloudflare_credentials_ref: String,
    #[serde(default)]
    pub chain_entry: Option<ResolvedChainRelayHopConfig>,
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
    pub chain_exit: Option<ResolvedChainRelayHopConfig>,
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
    /// Ordered N-hop chain list (length 2..=4 once validated, when non-empty).
    ///
    /// Additive v7 wire field. A populated list is the authored source of
    /// truth for a 3- or 4-hop chain; the legacy `chain_entry*` / `chain_exit*`
    /// scalars above remain the derived hop[0] / hop[last] mirror for backward
    /// compatibility. A v6 payload (or any plain 2-hop chain) omits this field,
    /// leaving it empty so the conversion folds the entry/exit scalars into a
    /// 2-element list via `ChainRelayConfig::ordered_hops`.
    #[serde(default)]
    pub chain_hops: Vec<ResolvedChainRelayHopConfig>,
    #[serde(default)]
    pub masque_url: String,
    #[serde(default)]
    pub masque_use_http2_fallback: bool,
    #[serde(default)]
    pub masque_cloudflare_geohash_enabled: bool,
    #[serde(default)]
    pub tuic_zero_rtt: bool,
    #[serde(default = "default_tuic_congestion_control")]
    pub tuic_congestion_control: String,
    #[serde(default)]
    pub shadow_tls_inner_profile_id: String,
    #[serde(default)]
    pub shadow_tls_inner: Option<ResolvedShadowTlsInnerRelayConfig>,
    #[serde(default)]
    pub trojan_root_certificate_pem: Option<String>,
    #[serde(default)]
    pub anytls_root_certificate_pem: Option<String>,
    #[serde(default)]
    pub naive_path: String,
    #[serde(default)]
    pub tor_state_dir: String,
    #[serde(default)]
    pub tor_cache_dir: String,
    #[serde(default)]
    pub tor_bridge_lines: Vec<String>,
    #[serde(default)]
    pub tor_transports: Vec<TorPluggableTransportConfig>,
    pub local_socks_host: String,
    pub local_socks_port: i32,
    pub udp_enabled: bool,
    pub tcp_fallback_enabled: bool,
    #[serde(default)]
    pub quic_bind_low_port: bool,
    #[serde(default)]
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
    pub hysteria_insecure: bool,
    #[serde(default)]
    pub tuic_uuid: Option<String>,
    #[serde(default)]
    pub tuic_password: Option<String>,
    #[serde(default)]
    pub shadow_tls_password: Option<String>,
    #[serde(default)]
    pub trojan_password: Option<String>,
    #[serde(default)]
    pub anytls_password: Option<String>,
    #[serde(default)]
    pub shadowsocks_method: String,
    #[serde(default)]
    pub shadowsocks_password: Option<String>,
    #[serde(default)]
    pub naive_username: Option<String>,
    #[serde(default)]
    pub naive_password: Option<String>,
    #[serde(default = "default_tls_fingerprint_profile")]
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
    #[serde(default)]
    pub mieru_server: String,
    #[serde(default)]
    pub mieru_port: i32,
    #[serde(default)]
    pub mieru_username: Option<String>,
    #[serde(default)]
    pub mieru_password: Option<String>,
    #[serde(default = "default_mieru_protocol")]
    pub mieru_protocol: String,
    #[serde(default = "default_mieru_multiplexing")]
    pub mieru_multiplexing: String,
    #[serde(default = "default_mieru_mtu")]
    pub mieru_mtu: i32,
    #[serde(default)]
    pub ssh_host: String,
    #[serde(default)]
    pub ssh_port: i32,
    #[serde(default)]
    pub ssh_username: Option<String>,
    #[serde(default = "default_ssh_auth_type")]
    pub ssh_auth_type: String,
    #[serde(default)]
    pub ssh_password: Option<String>,
    #[serde(default)]
    pub ssh_private_key: Option<String>,
    #[serde(default)]
    pub ssh_private_key_passphrase: Option<String>,
    #[serde(default)]
    pub ssh_host_key_fingerprint: Option<String>,
    #[serde(default)]
    pub ssh_strict_host_key: bool,
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, json};

    use crate::config::ResolvedRelayRuntimeConfig;

    fn relay_config_json_object() -> serde_json::Map<String, Value> {
        let mut value = json!({
            "enabled": true,
            "kind": "hysteria2",
            "profileId": "default",
            "outboundBindIp": "",
            "server": "relay.example",
            "serverPort": 443,
            "serverName": "relay.example",
            "localSocksHost": "127.0.0.1",
            "localSocksPort": 1080,
            "udpEnabled": false,
            "tcpFallbackEnabled": true,
            "quicBindLowPort": false,
            "quicMigrateAfterHandshake": false,
            "tlsFingerprintProfile": "chrome_stable",
            "hysteriaPassword": "secret"
        });
        value.as_object_mut().expect("relay config object").clone()
    }

    #[test]
    fn legacy_payload_without_schema_version_defaults_to_current_version() {
        let object = relay_config_json_object();
        assert!(!object.contains_key("schemaVersion"), "legacy payload must not carry schemaVersion");

        let config: ResolvedRelayRuntimeConfig = serde_json::from_value(Value::Object(object))
            .expect("legacy payload without schemaVersion should deserialize");

        assert_eq!("hysteria2", config.kind_id());
        let reserialized = serde_json::to_value(&config).expect("reserialize relay config");
        assert_eq!(reserialized["schemaVersion"], json!(8), "absent schemaVersion defaults to 8");
    }

    #[test]
    fn payload_with_explicit_schema_version_six_deserializes() {
        let mut object = relay_config_json_object();
        object.insert("schemaVersion".to_string(), json!(6));

        let config: ResolvedRelayRuntimeConfig = serde_json::from_value(Value::Object(object))
            .expect("legacy payload with schemaVersion 6 should still deserialize");

        assert_eq!("hysteria2", config.kind_id());
    }

    #[test]
    fn payload_with_explicit_schema_version_seven_deserializes() {
        let mut object = relay_config_json_object();
        object.insert("schemaVersion".to_string(), json!(7));

        let config: ResolvedRelayRuntimeConfig = serde_json::from_value(Value::Object(object))
            .expect("payload with schemaVersion 7 should deserialize");

        assert_eq!("hysteria2", config.kind_id());
    }

    #[test]
    fn payload_with_explicit_schema_version_eight_deserializes() {
        let mut object = relay_config_json_object();
        object.insert("schemaVersion".to_string(), json!(8));

        let config: ResolvedRelayRuntimeConfig = serde_json::from_value(Value::Object(object))
            .expect("payload with schemaVersion 8 should deserialize");

        assert_eq!("hysteria2", config.kind_id());
    }

    #[test]
    fn payload_with_unsupported_schema_version_is_rejected() {
        let mut object = relay_config_json_object();
        object.insert("schemaVersion".to_string(), json!(9));

        let err = serde_json::from_value::<ResolvedRelayRuntimeConfig>(Value::Object(object))
            .expect_err("payload with schemaVersion 9 should be rejected");

        assert!(
            err.to_string().contains("unsupported native config schemaVersion 9"),
            "error should name the found version, got: {err}"
        );
    }

    #[test]
    fn sparse_kotlin_payload_uses_wire_compatible_defaults() {
        let mut object = relay_config_json_object();
        for key in [
            "outboundBindIp",
            "vlessTransport",
            "cloudflareTunnelMode",
            "tuicCongestionControl",
            "quicBindLowPort",
            "quicMigrateAfterHandshake",
            "tlsFingerprintProfile",
            "mieruProtocol",
            "mieruMultiplexing",
            "mieruMtu",
            "sshAuthType",
        ] {
            object.remove(key);
        }

        let config: ResolvedRelayRuntimeConfig = serde_json::from_value(Value::Object(object.clone()))
            .expect("sparse Kotlin payload should deserialize");
        let value = serde_json::to_value(config).expect("serialize migrated config");

        assert_eq!(value["outboundBindIp"], json!(""));
        assert_eq!(value["quicBindLowPort"], json!(false));
        assert_eq!(value["quicMigrateAfterHandshake"], json!(false));
        assert_eq!(value["tlsFingerprintProfile"], json!("chrome_stable"));

        let round_trip_kind = |kind: &str| {
            let mut payload = object.clone();
            payload.insert("kind".to_string(), json!(kind));
            let config: ResolvedRelayRuntimeConfig =
                serde_json::from_value(Value::Object(payload)).expect("kind-specific sparse payload");
            serde_json::to_value(config).expect("serialize kind-specific config")
        };
        assert_eq!(round_trip_kind("vless")["vlessTransport"], json!("reality_tcp"));
        assert_eq!(round_trip_kind("cloudflare_tunnel")["cloudflareTunnelMode"], json!("consume_existing"));
        assert_eq!(round_trip_kind("tuic_v5")["tuicCongestionControl"], json!("bbr"));
        let mieru = round_trip_kind("mieru");
        assert_eq!(mieru["mieruProtocol"], json!("tcp"));
        assert_eq!(mieru["mieruMultiplexing"], json!("middle"));
        assert_eq!(mieru["mieruMtu"], json!(1400));
        assert_eq!(round_trip_kind("ssh")["sshAuthType"], json!("password"));
    }

    #[test]
    fn payload_below_supported_schema_floor_is_rejected() {
        let mut object = relay_config_json_object();
        object.insert("schemaVersion".to_string(), json!(5));

        let err = serde_json::from_value::<ResolvedRelayRuntimeConfig>(Value::Object(object))
            .expect_err("payload with schemaVersion 5 should be rejected");

        assert!(err.to_string().contains("unsupported native config schemaVersion 5"));
    }
}
