use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedRelayFinalmaskConfig {
    #[serde(default)]
    pub r#type: String,
    #[serde(default)]
    pub header_hex: String,
    #[serde(default)]
    pub trailer_hex: String,
    #[serde(default)]
    pub rand_range: String,
    #[serde(default)]
    pub sudoku_seed: String,
    #[serde(default)]
    pub fragment_packets: i32,
    #[serde(default)]
    pub fragment_min_bytes: i32,
    #[serde(default)]
    pub fragment_max_bytes: i32,
}

impl Default for ResolvedRelayFinalmaskConfig {
    fn default() -> Self {
        Self {
            r#type: "off".to_string(),
            header_hex: String::new(),
            trailer_hex: String::new(),
            rand_range: String::new(),
            sudoku_seed: String::new(),
            fragment_packets: 0,
            fragment_min_bytes: 0,
            fragment_max_bytes: 0,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RelayKind<'a> {
    Hysteria2,
    TuicV5,
    VlessReality { xhttp: bool },
    CloudflareTunnel,
    ChainRelay,
    Masque,
    ShadowTlsV3,
    NaiveProxy,
    Unsupported(&'a str),
}

impl<'a> RelayKind<'a> {
    pub(crate) fn from_config(config: &'a ResolvedRelayRuntimeConfig) -> Self {
        match config.kind.as_str() {
            "hysteria2" => Self::Hysteria2,
            "tuic_v5" => Self::TuicV5,
            "vless_reality" => Self::VlessReality { xhttp: config.vless_transport == "xhttp" },
            "cloudflare_tunnel" => Self::CloudflareTunnel,
            "chain_relay" => Self::ChainRelay,
            "masque" => Self::Masque,
            "shadowtls_v3" => Self::ShadowTlsV3,
            "naiveproxy" => Self::NaiveProxy,
            other => Self::Unsupported(other),
        }
    }

    pub(crate) fn supports_finalmask(self) -> bool {
        matches!(self, Self::CloudflareTunnel | Self::VlessReality { xhttp: true })
    }

    pub(crate) fn supports_outbound_bind_ip(self) -> bool {
        !matches!(self, Self::Hysteria2 | Self::Masque)
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedRelayRuntimeConfig {
    pub enabled: bool,
    pub kind: String,
    pub profile_id: String,
    pub outbound_bind_ip: String,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub reality_public_key: String,
    pub reality_short_id: String,
    pub vless_transport: String,
    pub xhttp_path: String,
    pub xhttp_host: String,
    /// Tunnel-mode selector: `"consume_existing"` (default) or `"publish_local_origin"`.
    ///
    /// **relay-core does not dispatch on this field.** The mode decision is made in Kotlin
    /// (`UpstreamRelaySupervisor`) before this config reaches Rust. For `publish_local_origin`,
    /// Kotlin launches the `ripdpi-cloudflare-origin` and `cloudflared` subprocesses via
    /// `CloudflarePublishRuntime`; relay-core then receives a plain xHTTP config pointing at
    /// the loopback listener, identical in shape to a `consume_existing` config. Adding a
    /// match here would produce dead branches. See architecture notes for the full rationale.
    #[serde(default)]
    pub cloudflare_tunnel_mode: String,
    /// Local loopback origin URL used only in `publish_local_origin` mode
    /// (e.g. `"http://127.0.0.1:8080"`). Validated and consumed by `CloudflarePublishRuntime`
    /// on the Kotlin side before relay-core is started; relay-core carries this field for
    /// wire-format completeness but does not read it. See architecture notes.
    #[serde(default)]
    pub cloudflare_publish_local_origin_url: String,
    /// Opaque credentials reference for named-tunnel publish mode. Resolved by Kotlin before
    /// relay-core starts; relay-core carries this field for wire-format completeness only.
    /// See architecture notes.
    #[serde(default)]
    pub cloudflare_credentials_ref: String,
    pub chain_entry_server: String,
    pub chain_entry_port: i32,
    pub chain_entry_server_name: String,
    pub chain_entry_public_key: String,
    pub chain_entry_short_id: String,
    pub chain_entry_profile_id: String,
    pub chain_exit_server: String,
    pub chain_exit_port: i32,
    pub chain_exit_server_name: String,
    pub chain_exit_public_key: String,
    pub chain_exit_short_id: String,
    pub chain_exit_profile_id: String,
    pub masque_url: String,
    pub masque_use_http2_fallback: bool,
    pub masque_cloudflare_geohash_enabled: bool,
    pub tuic_zero_rtt: bool,
    pub tuic_congestion_control: String,
    pub shadow_tls_inner_profile_id: String,
    pub shadow_tls_inner: Option<ResolvedShadowTlsInnerRelayConfig>,
    pub naive_path: String,
    pub local_socks_host: String,
    pub local_socks_port: i32,
    pub udp_enabled: bool,
    pub tcp_fallback_enabled: bool,
    pub quic_bind_low_port: bool,
    pub quic_migrate_after_handshake: bool,
    pub vless_uuid: Option<String>,
    pub chain_entry_uuid: Option<String>,
    pub chain_exit_uuid: Option<String>,
    pub hysteria_password: Option<String>,
    pub hysteria_salamander_key: Option<String>,
    pub tuic_uuid: Option<String>,
    pub tuic_password: Option<String>,
    pub shadow_tls_password: Option<String>,
    pub naive_username: Option<String>,
    pub naive_password: Option<String>,
    pub tls_fingerprint_profile: String,
    pub masque_auth_mode: Option<String>,
    pub masque_auth_token: Option<String>,
    pub masque_client_certificate_chain_pem: Option<String>,
    pub masque_client_private_key_pem: Option<String>,
    pub masque_cloudflare_geohash_header: Option<String>,
    pub masque_privacy_pass_provider_url: Option<String>,
    pub masque_privacy_pass_provider_auth_token: Option<String>,
    pub cloudflare_tunnel_token: Option<String>,
    pub cloudflare_tunnel_credentials_json: Option<String>,
    #[serde(default)]
    pub finalmask: ResolvedRelayFinalmaskConfig,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedShadowTlsInnerRelayConfig {
    pub kind: String,
    pub profile_id: String,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub reality_public_key: String,
    pub reality_short_id: String,
    pub vless_transport: String,
    pub vless_uuid: Option<String>,
}
