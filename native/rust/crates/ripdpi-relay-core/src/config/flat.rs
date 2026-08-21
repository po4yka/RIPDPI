/// The only relay native-config wire schema version this build accepts.
/// Mirrors the Kotlin `RelayNativeConfigSchemaVersion` constant.
const SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION: u32 = 10;

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

fn default_masque_tcp_protocol() -> String {
    "http2".to_string()
}

fn default_tuic_congestion_control() -> String {
    "bbr".to_string()
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
fn validate_schema_version(found: u32) -> Result<(), RelayConfigError> {
    if found == SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION {
        Ok(())
    } else {
        Err(RelayConfigError::UnsupportedSchemaVersion { found })
    }
}

fn validate_required_relay_identity(flat: &FlatResolvedRelayRuntimeConfig) -> Result<(), RelayConfigError> {
    if flat.kind == "chain_relay" && flat.chain_hops.is_empty() {
        if flat.chain_entry.is_none() {
            return Err(RelayConfigError::MissingResolvedChainHop { role: "entry" });
        }
        if flat.chain_exit.is_none() {
            return Err(RelayConfigError::MissingResolvedChainHop { role: "exit" });
        }
    }
    Ok(())
}

/// Typed relay config error surfaced through the [`ResolvedRelayRuntimeConfig`]
/// deserialize path (wrapped in a `serde` error via `Error::custom`).
///
/// `relay-core` does not depend on `thiserror`, so `Display` / `Error` are
/// implemented by hand for this single-variant enum.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum RelayConfigError {
    UnsupportedSchemaVersion { found: u32 },
    MissingResolvedChainHop { role: &'static str },
}

impl std::fmt::Display for RelayConfigError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RelayConfigError::UnsupportedSchemaVersion { found } => write!(
                formatter,
                "unsupported native config schemaVersion {found}; this build supports \
                 {SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION}"
            ),
            RelayConfigError::MissingResolvedChainHop { role } => write!(
                formatter,
                "relay schemaVersion {SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION} requires resolved chain {role} config with explicit tlsFingerprintProfile"
            ),
        }
    }
}

impl std::error::Error for RelayConfigError {}

// `deny_unknown_fields` is the wire-contract guard for the Kotlin boundary:
// a misspelled or renamed field must fail closed here instead of silently
// falling back to its default on both sides of the schema-version gate.
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct FlatResolvedRelayRuntimeConfig {
    pub schema_version: u32,
    pub enabled: bool,
    pub kind: String,
    pub profile_id: String,
    #[serde(default)]
    pub outbound_bind_ip: String,
    #[serde(default)]
    pub socket_protection: SocketProtection,
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
    #[serde(default)]
    pub vless_mux_protocol: String,
    #[serde(default)]
    pub vless_mux_max_concurrent_streams: u32,
    #[serde(default)]
    pub vless_mux_per_connection_kbps: u32,
    #[serde(default)]
    pub vless_mux_padding_max: u32,
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
    #[serde(default = "default_masque_tcp_protocol")]
    pub masque_tcp_protocol: String,
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

    use crate::config::*;

    const DEBUG_SECRET: &str = "relay-debug-secret-sentinel";

    fn secret() -> String {
        DEBUG_SECRET.to_string()
    }

    fn assert_debug_redacts(value: &impl std::fmt::Debug) {
        let rendered = format!("{value:?}");
        assert!(
            rendered.contains(REDACTED_CREDENTIALS),
            "Debug output should make redaction explicit: {rendered}"
        );
        assert!(
            !rendered.contains(DEBUG_SECRET),
            "Debug output leaked a relay credential: {rendered}"
        );
    }

    fn relay_config_json_object() -> serde_json::Map<String, Value> {
        let mut value = json!({
            "schemaVersion": 10,
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
    fn payload_without_schema_version_is_rejected() {
        let mut object = relay_config_json_object();
        object.remove("schemaVersion");

        let err = serde_json::from_value::<ResolvedRelayRuntimeConfig>(Value::Object(object))
            .expect_err("payload without schemaVersion should be rejected");

        assert!(err.to_string().contains("schemaVersion"), "error should name schemaVersion: {err}");
    }

    #[test]
    fn retired_schema_versions_are_rejected() {
        for version in [6, 7, 8, 9] {
            let mut object = relay_config_json_object();
            object.insert("schemaVersion".to_string(), json!(version));

            let err = serde_json::from_value::<ResolvedRelayRuntimeConfig>(Value::Object(object))
                .expect_err("retired schema version should be rejected");

            assert!(err.to_string().contains(&format!("schemaVersion {version}")));
        }
    }

    #[test]
    fn payload_with_current_schema_version_deserializes() {
        let config: ResolvedRelayRuntimeConfig =
            serde_json::from_value(Value::Object(relay_config_json_object())).expect("current payload");

        assert_eq!("hysteria2", config.kind_id());
    }

    #[test]
    fn payload_without_top_level_tls_fingerprint_is_rejected() {
        let mut object = relay_config_json_object();
        object.remove("tlsFingerprintProfile");

        let err = serde_json::from_value::<ResolvedRelayRuntimeConfig>(Value::Object(object))
            .expect_err("payload without tlsFingerprintProfile should be rejected");

        assert!(err.to_string().contains("tlsFingerprintProfile"), "unexpected error: {err}");
    }

    #[test]
    fn payload_without_shadowtls_inner_tls_fingerprint_is_rejected() {
        let mut object = relay_config_json_object();
        object.insert("kind".to_string(), json!("shadowtls_v3"));
        object.insert(
            "shadowTlsInner".to_string(),
            json!({
                "kind": "vless_reality",
                "profileId": "inner",
                "server": "inner.example",
                "serverPort": 443,
                "serverName": "inner.example",
                "realityPublicKey": "public",
                "realityShortId": "short",
                "vlessUuid": "00000000-0000-0000-0000-000000000001"
            }),
        );

        let err = serde_json::from_value::<ResolvedRelayRuntimeConfig>(Value::Object(object))
            .expect_err("ShadowTLS inner payload without tlsFingerprintProfile should be rejected");

        assert!(err.to_string().contains("tlsFingerprintProfile"), "unexpected error: {err}");
    }

    #[test]
    fn scalar_only_chain_payload_is_rejected() {
        let mut object = relay_config_json_object();
        object.insert("kind".to_string(), json!("chain_relay"));
        object.insert("chainEntryServer".to_string(), json!("entry.example"));
        object.insert("chainExitServer".to_string(), json!("exit.example"));

        let err = serde_json::from_value::<ResolvedRelayRuntimeConfig>(Value::Object(object))
            .expect_err("scalar-only chain payload should be rejected");

        assert!(err.to_string().contains("resolved chain entry config"));
        assert!(err.to_string().contains("tlsFingerprintProfile"));
    }

    #[test]
    fn payload_with_future_schema_version_is_rejected() {
        let mut object = relay_config_json_object();
        object.insert("schemaVersion".to_string(), json!(11));

        let err = serde_json::from_value::<ResolvedRelayRuntimeConfig>(Value::Object(object))
            .expect_err("payload with schemaVersion 11 should be rejected");

        assert!(
            err.to_string().contains("unsupported native config schemaVersion 11"),
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
    fn payload_below_current_schema_version_is_rejected() {
        let mut object = relay_config_json_object();
        object.insert("schemaVersion".to_string(), json!(5));

        let err = serde_json::from_value::<ResolvedRelayRuntimeConfig>(Value::Object(object))
            .expect_err("payload with schemaVersion 5 should be rejected");

        assert!(err.to_string().contains("unsupported native config schemaVersion 5"));
    }

    #[test]
    fn shadowtls_inner_vless_flow_round_trips() {
        let mut object = relay_config_json_object();
        object.insert("kind".to_string(), json!("shadowtls_v3"));
        object.insert(
            "shadowTlsInner".to_string(),
            json!({
                "kind": "vless_reality",
                "profileId": "inner",
                "server": "inner.example",
                "serverPort": 443,
                "serverName": "inner.example",
                "realityPublicKey": "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
                "realityShortId": "",
                "vlessFlow": "xtls-rprx-vision-udp443",
                "vlessUuid": "00000000-0000-0000-0000-000000000001",
                "tlsFingerprintProfile": "firefox_stable"
            }),
        );

        let config: ResolvedRelayRuntimeConfig =
            serde_json::from_value(Value::Object(object)).expect("ShadowTLS inner config");
        let value = serde_json::to_value(config).expect("serialize ShadowTLS config");

        assert_eq!(value["shadowTlsInner"]["vlessFlow"], json!("xtls-rprx-vision-udp443"));
        assert_eq!(value["shadowTlsInner"]["vlessTransport"], json!("reality_tcp"));
        assert_eq!(value["shadowTlsInner"]["tlsFingerprintProfile"], json!("firefox_stable"));
    }

    #[test]
    fn public_config_debug_redacts_credentials() {
        assert_debug_redacts(&AnyTlsRelayConfig {
            password: Some(secret()),
            root_certificate_pem: Some(secret()),
        });
        assert_debug_redacts(&Hysteria2RelayConfig {
            password: Some(secret()),
            salamander_key: Some(secret()),
            insecure: false,
        });
        assert_debug_redacts(&TuicRelayConfig {
            uuid: Some(secret()),
            password: Some(secret()),
            zero_rtt: false,
            congestion_control: "bbr".to_string(),
        });
        assert_debug_redacts(&VlessRelayConfig {
            uuid: Some(secret()),
            xhttp_path: secret(),
            xhttp_host: secret(),
            ..VlessRelayConfig::default()
        });
        assert_debug_redacts(&VlessRealityRelayConfig {
            reality_public_key: secret(),
            reality_short_id: secret(),
            xhttp_path: secret(),
            xhttp_host: secret(),
            uuid: Some(secret()),
            ..VlessRealityRelayConfig::default()
        });
        assert_debug_redacts(&MieruRelayConfig {
            username: Some(secret()),
            password: Some(secret()),
            ..MieruRelayConfig::default()
        });
        assert_debug_redacts(&SshRelayConfig {
            username: Some(secret()),
            password: Some(secret()),
            private_key: Some(secret()),
            private_key_passphrase: Some(secret()),
            host_key_fingerprint: Some(secret()),
            ..SshRelayConfig::default()
        });
        assert_debug_redacts(&CloudflareTunnelRelayConfig {
            uuid: Some(secret()),
            xhttp_path: secret(),
            xhttp_host: secret(),
            publish_local_origin_url: secret(),
            credentials_ref: secret(),
            tunnel_token: Some(secret()),
            tunnel_credentials_json: Some(secret()),
            ..CloudflareTunnelRelayConfig::default()
        });
        assert_debug_redacts(&MasqueRelayConfig {
            url: secret(),
            auth_token: Some(secret()),
            client_certificate_chain_pem: Some(secret()),
            client_private_key_pem: Some(secret()),
            cloudflare_geohash_header: Some(secret()),
            privacy_pass_provider_url: Some(secret()),
            privacy_pass_provider_auth_token: Some(secret()),
            ..MasqueRelayConfig::default()
        });

        let inner = ResolvedShadowTlsInnerRelayConfig {
            kind: "vless_reality".to_string(),
            profile_id: "inner".to_string(),
            server: "inner.example".to_string(),
            server_port: 443,
            server_name: "inner.example".to_string(),
            reality_public_key: secret(),
            reality_short_id: secret(),
            vless_flow: "xtls-rprx-vision".to_string(),
            vless_transport: "reality_tcp".to_string(),
            xhttp_mode: "auto".to_string(),
            vless_uuid: Some(secret()),
            tls_fingerprint_profile: "chrome_stable".to_string(),
        };
        assert_debug_redacts(&inner);
        assert_debug_redacts(&ShadowTlsRelayConfig {
            password: Some(secret()),
            inner_profile_id: "inner".to_string(),
            inner: Some(inner.clone()),
        });
        assert_debug_redacts(&TrojanRelayConfig {
            password: Some(secret()),
            root_certificate_pem: Some(secret()),
        });
        assert_debug_redacts(&ShadowsocksRelayConfig {
            method: "2022-blake3-aes-256-gcm".to_string(),
            password: Some(secret()),
        });
        assert_debug_redacts(&NaiveProxyRelayConfig {
            path: secret(),
            username: Some(secret()),
            password: Some(secret()),
        });

        let transport = TorPluggableTransportConfig {
            protocols: vec!["obfs4".to_string()],
            binary_path: "/tmp/obfs4proxy".to_string(),
            arguments: vec![secret()],
            run_on_startup: true,
        };
        assert_debug_redacts(&transport);
        assert_debug_redacts(&TorRelayConfig {
            bridge_lines: vec![secret()],
            transports: vec![transport],
            ..TorRelayConfig::default()
        });

        let hop = ResolvedChainRelayHopConfig {
            reality_public_key: secret(),
            reality_short_id: secret(),
            xhttp_path: secret(),
            xhttp_host: secret(),
            cloudflare_publish_local_origin_url: secret(),
            cloudflare_credentials_ref: secret(),
            masque_url: secret(),
            trojan_root_certificate_pem: Some(secret()),
            anytls_root_certificate_pem: Some(secret()),
            naive_path: secret(),
            vless_uuid: Some(secret()),
            hysteria_password: Some(secret()),
            hysteria_salamander_key: Some(secret()),
            anytls_password: Some(secret()),
            tuic_uuid: Some(secret()),
            tuic_password: Some(secret()),
            shadow_tls_password: Some(secret()),
            trojan_password: Some(secret()),
            shadowsocks_password: Some(secret()),
            naive_username: Some(secret()),
            naive_password: Some(secret()),
            masque_auth_token: Some(secret()),
            masque_client_certificate_chain_pem: Some(secret()),
            masque_client_private_key_pem: Some(secret()),
            masque_cloudflare_geohash_header: Some(secret()),
            masque_privacy_pass_provider_url: Some(secret()),
            masque_privacy_pass_provider_auth_token: Some(secret()),
            cloudflare_tunnel_token: Some(secret()),
            cloudflare_tunnel_credentials_json: Some(secret()),
            shadow_tls_inner: Some(inner),
            ..ResolvedChainRelayHopConfig::default()
        };
        assert_debug_redacts(&hop);
        assert_debug_redacts(&ChainRelayConfig {
            hops: vec![hop.clone(), hop],
            entry_uuid: Some(secret()),
            exit_uuid: Some(secret()),
            ..ChainRelayConfig::default()
        });

        let mut object = relay_config_json_object();
        object.insert("hysteriaPassword".to_string(), json!(DEBUG_SECRET));
        let runtime: ResolvedRelayRuntimeConfig =
            serde_json::from_value(Value::Object(object)).expect("current runtime config");
        assert_debug_redacts(&runtime);
    }
}
