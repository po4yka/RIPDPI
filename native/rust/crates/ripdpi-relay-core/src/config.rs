use serde::{Deserialize, Deserializer, Serialize, Serializer};

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
        match &config.backend {
            RelayBackendConfig::Hysteria2(_) => Self::Hysteria2,
            RelayBackendConfig::TuicV5(_) => Self::TuicV5,
            RelayBackendConfig::VlessReality(vless) => Self::VlessReality { xhttp: vless.vless_transport == "xhttp" },
            RelayBackendConfig::CloudflareTunnel(_) => Self::CloudflareTunnel,
            RelayBackendConfig::ChainRelay(_) => Self::ChainRelay,
            RelayBackendConfig::Masque(_) => Self::Masque,
            RelayBackendConfig::ShadowTlsV3(_) => Self::ShadowTlsV3,
            RelayBackendConfig::NaiveProxy(_) => Self::NaiveProxy,
            RelayBackendConfig::Unsupported(unsupported) => Self::Unsupported(&unsupported.kind),
        }
    }

    pub(crate) fn supports_finalmask(self) -> bool {
        matches!(self, Self::CloudflareTunnel | Self::VlessReality { xhttp: true })
    }

    pub(crate) fn supports_outbound_bind_ip(self) -> bool {
        !matches!(self, Self::Hysteria2 | Self::Masque)
    }
}

#[derive(Debug, Clone)]
pub struct ResolvedRelayRuntimeConfig {
    pub common: CommonRelayConfig,
    pub backend: RelayBackendConfig,
}

impl<'de> Deserialize<'de> for ResolvedRelayRuntimeConfig {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        Ok(FlatResolvedRelayRuntimeConfig::deserialize(deserializer)?.into())
    }
}

impl Serialize for ResolvedRelayRuntimeConfig {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        FlatResolvedRelayRuntimeConfig::from(self).serialize(serializer)
    }
}

impl ResolvedRelayRuntimeConfig {
    pub(crate) fn kind_id(&self) -> &str {
        self.backend.kind_id()
    }

    pub(crate) fn xhttp_path(&self) -> &str {
        match &self.backend {
            RelayBackendConfig::VlessReality(config) => &config.xhttp_path,
            RelayBackendConfig::CloudflareTunnel(config) => &config.xhttp_path,
            _ => "",
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CommonRelayConfig {
    pub enabled: bool,
    pub profile_id: String,
    pub outbound_bind_ip: String,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub local_socks_host: String,
    pub local_socks_port: i32,
    pub udp_enabled: bool,
    pub tcp_fallback_enabled: bool,
    pub quic_bind_low_port: bool,
    pub quic_migrate_after_handshake: bool,
    pub tls_fingerprint_profile: String,
    #[serde(default)]
    pub finalmask: ResolvedRelayFinalmaskConfig,
}

#[derive(Debug, Clone)]
pub enum RelayBackendConfig {
    Hysteria2(Hysteria2RelayConfig),
    TuicV5(TuicRelayConfig),
    VlessReality(VlessRealityRelayConfig),
    CloudflareTunnel(CloudflareTunnelRelayConfig),
    ChainRelay(ChainRelayConfig),
    Masque(MasqueRelayConfig),
    ShadowTlsV3(ShadowTlsRelayConfig),
    NaiveProxy(NaiveProxyRelayConfig),
    Unsupported(UnsupportedRelayConfig),
}

impl RelayBackendConfig {
    pub(crate) fn kind_id(&self) -> &str {
        match self {
            Self::Hysteria2(_) => "hysteria2",
            Self::TuicV5(_) => "tuic_v5",
            Self::VlessReality(_) => "vless_reality",
            Self::CloudflareTunnel(_) => "cloudflare_tunnel",
            Self::ChainRelay(_) => "chain_relay",
            Self::Masque(_) => "masque",
            Self::ShadowTlsV3(_) => "shadowtls_v3",
            Self::NaiveProxy(_) => "naiveproxy",
            Self::Unsupported(config) => &config.kind,
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct Hysteria2RelayConfig {
    pub password: Option<String>,
    pub salamander_key: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct TuicRelayConfig {
    pub uuid: Option<String>,
    pub password: Option<String>,
    pub zero_rtt: bool,
    pub congestion_control: String,
}

#[derive(Debug, Clone, Default)]
pub struct VlessRealityRelayConfig {
    pub reality_public_key: String,
    pub reality_short_id: String,
    pub vless_transport: String,
    pub xhttp_path: String,
    pub xhttp_host: String,
    pub uuid: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct CloudflareTunnelRelayConfig {
    pub uuid: Option<String>,
    pub xhttp_path: String,
    pub xhttp_host: String,
    pub tunnel_mode: String,
    pub publish_local_origin_url: String,
    pub credentials_ref: String,
    pub tunnel_token: Option<String>,
    pub tunnel_credentials_json: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct ChainRelayConfig {
    pub entry_server: String,
    pub entry_port: i32,
    pub entry_server_name: String,
    pub entry_public_key: String,
    pub entry_short_id: String,
    pub entry_profile_id: String,
    pub entry_uuid: Option<String>,
    pub exit_server: String,
    pub exit_port: i32,
    pub exit_server_name: String,
    pub exit_public_key: String,
    pub exit_short_id: String,
    pub exit_profile_id: String,
    pub exit_uuid: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct MasqueRelayConfig {
    pub url: String,
    pub use_http2_fallback: bool,
    pub cloudflare_geohash_enabled: bool,
    pub auth_mode: Option<String>,
    pub auth_token: Option<String>,
    pub client_certificate_chain_pem: Option<String>,
    pub client_private_key_pem: Option<String>,
    pub cloudflare_geohash_header: Option<String>,
    pub privacy_pass_provider_url: Option<String>,
    pub privacy_pass_provider_auth_token: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct ShadowTlsRelayConfig {
    pub password: Option<String>,
    pub inner_profile_id: String,
    pub inner: Option<ResolvedShadowTlsInnerRelayConfig>,
}

#[derive(Debug, Clone, Default)]
pub struct NaiveProxyRelayConfig {
    pub path: String,
    pub username: Option<String>,
    pub password: Option<String>,
}

#[derive(Debug, Clone)]
pub struct UnsupportedRelayConfig {
    pub kind: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct FlatResolvedRelayRuntimeConfig {
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

impl From<FlatResolvedRelayRuntimeConfig> for ResolvedRelayRuntimeConfig {
    fn from(flat: FlatResolvedRelayRuntimeConfig) -> Self {
        let common = CommonRelayConfig {
            enabled: flat.enabled,
            profile_id: flat.profile_id,
            outbound_bind_ip: flat.outbound_bind_ip,
            server: flat.server,
            server_port: flat.server_port,
            server_name: flat.server_name,
            local_socks_host: flat.local_socks_host,
            local_socks_port: flat.local_socks_port,
            udp_enabled: flat.udp_enabled,
            tcp_fallback_enabled: flat.tcp_fallback_enabled,
            quic_bind_low_port: flat.quic_bind_low_port,
            quic_migrate_after_handshake: flat.quic_migrate_after_handshake,
            tls_fingerprint_profile: flat.tls_fingerprint_profile,
            finalmask: flat.finalmask,
        };
        let backend = match flat.kind.as_str() {
            "hysteria2" => RelayBackendConfig::Hysteria2(Hysteria2RelayConfig {
                password: flat.hysteria_password,
                salamander_key: flat.hysteria_salamander_key,
            }),
            "tuic_v5" => RelayBackendConfig::TuicV5(TuicRelayConfig {
                uuid: flat.tuic_uuid,
                password: flat.tuic_password,
                zero_rtt: flat.tuic_zero_rtt,
                congestion_control: flat.tuic_congestion_control,
            }),
            "vless_reality" => RelayBackendConfig::VlessReality(VlessRealityRelayConfig {
                reality_public_key: flat.reality_public_key,
                reality_short_id: flat.reality_short_id,
                vless_transport: flat.vless_transport,
                xhttp_path: flat.xhttp_path,
                xhttp_host: flat.xhttp_host,
                uuid: flat.vless_uuid,
            }),
            "cloudflare_tunnel" => RelayBackendConfig::CloudflareTunnel(CloudflareTunnelRelayConfig {
                uuid: flat.vless_uuid,
                xhttp_path: flat.xhttp_path,
                xhttp_host: flat.xhttp_host,
                tunnel_mode: flat.cloudflare_tunnel_mode,
                publish_local_origin_url: flat.cloudflare_publish_local_origin_url,
                credentials_ref: flat.cloudflare_credentials_ref,
                tunnel_token: flat.cloudflare_tunnel_token,
                tunnel_credentials_json: flat.cloudflare_tunnel_credentials_json,
            }),
            "chain_relay" => RelayBackendConfig::ChainRelay(ChainRelayConfig {
                entry_server: flat.chain_entry_server,
                entry_port: flat.chain_entry_port,
                entry_server_name: flat.chain_entry_server_name,
                entry_public_key: flat.chain_entry_public_key,
                entry_short_id: flat.chain_entry_short_id,
                entry_profile_id: flat.chain_entry_profile_id,
                entry_uuid: flat.chain_entry_uuid,
                exit_server: flat.chain_exit_server,
                exit_port: flat.chain_exit_port,
                exit_server_name: flat.chain_exit_server_name,
                exit_public_key: flat.chain_exit_public_key,
                exit_short_id: flat.chain_exit_short_id,
                exit_profile_id: flat.chain_exit_profile_id,
                exit_uuid: flat.chain_exit_uuid,
            }),
            "masque" => RelayBackendConfig::Masque(MasqueRelayConfig {
                url: flat.masque_url,
                use_http2_fallback: flat.masque_use_http2_fallback,
                cloudflare_geohash_enabled: flat.masque_cloudflare_geohash_enabled,
                auth_mode: flat.masque_auth_mode,
                auth_token: flat.masque_auth_token,
                client_certificate_chain_pem: flat.masque_client_certificate_chain_pem,
                client_private_key_pem: flat.masque_client_private_key_pem,
                cloudflare_geohash_header: flat.masque_cloudflare_geohash_header,
                privacy_pass_provider_url: flat.masque_privacy_pass_provider_url,
                privacy_pass_provider_auth_token: flat.masque_privacy_pass_provider_auth_token,
            }),
            "shadowtls_v3" => RelayBackendConfig::ShadowTlsV3(ShadowTlsRelayConfig {
                password: flat.shadow_tls_password,
                inner_profile_id: flat.shadow_tls_inner_profile_id,
                inner: flat.shadow_tls_inner,
            }),
            "naiveproxy" => RelayBackendConfig::NaiveProxy(NaiveProxyRelayConfig {
                path: flat.naive_path,
                username: flat.naive_username,
                password: flat.naive_password,
            }),
            other => RelayBackendConfig::Unsupported(UnsupportedRelayConfig { kind: other.to_string() }),
        };
        Self { common, backend }
    }
}

impl From<&ResolvedRelayRuntimeConfig> for FlatResolvedRelayRuntimeConfig {
    fn from(config: &ResolvedRelayRuntimeConfig) -> Self {
        let mut flat = Self {
            enabled: config.common.enabled,
            kind: config.kind_id().to_string(),
            profile_id: config.common.profile_id.clone(),
            outbound_bind_ip: config.common.outbound_bind_ip.clone(),
            server: config.common.server.clone(),
            server_port: config.common.server_port,
            server_name: config.common.server_name.clone(),
            reality_public_key: String::new(),
            reality_short_id: String::new(),
            vless_transport: String::new(),
            xhttp_path: String::new(),
            xhttp_host: String::new(),
            cloudflare_tunnel_mode: String::new(),
            cloudflare_publish_local_origin_url: String::new(),
            cloudflare_credentials_ref: String::new(),
            chain_entry_server: String::new(),
            chain_entry_port: 0,
            chain_entry_server_name: String::new(),
            chain_entry_public_key: String::new(),
            chain_entry_short_id: String::new(),
            chain_entry_profile_id: String::new(),
            chain_exit_server: String::new(),
            chain_exit_port: 0,
            chain_exit_server_name: String::new(),
            chain_exit_public_key: String::new(),
            chain_exit_short_id: String::new(),
            chain_exit_profile_id: String::new(),
            masque_url: String::new(),
            masque_use_http2_fallback: false,
            masque_cloudflare_geohash_enabled: false,
            tuic_zero_rtt: false,
            tuic_congestion_control: String::new(),
            shadow_tls_inner_profile_id: String::new(),
            shadow_tls_inner: None,
            naive_path: String::new(),
            local_socks_host: config.common.local_socks_host.clone(),
            local_socks_port: config.common.local_socks_port,
            udp_enabled: config.common.udp_enabled,
            tcp_fallback_enabled: config.common.tcp_fallback_enabled,
            quic_bind_low_port: config.common.quic_bind_low_port,
            quic_migrate_after_handshake: config.common.quic_migrate_after_handshake,
            vless_uuid: None,
            chain_entry_uuid: None,
            chain_exit_uuid: None,
            hysteria_password: None,
            hysteria_salamander_key: None,
            tuic_uuid: None,
            tuic_password: None,
            shadow_tls_password: None,
            naive_username: None,
            naive_password: None,
            tls_fingerprint_profile: config.common.tls_fingerprint_profile.clone(),
            masque_auth_mode: None,
            masque_auth_token: None,
            masque_client_certificate_chain_pem: None,
            masque_client_private_key_pem: None,
            masque_cloudflare_geohash_header: None,
            masque_privacy_pass_provider_url: None,
            masque_privacy_pass_provider_auth_token: None,
            cloudflare_tunnel_token: None,
            cloudflare_tunnel_credentials_json: None,
            finalmask: config.common.finalmask.clone(),
        };
        match &config.backend {
            RelayBackendConfig::Hysteria2(config) => {
                flat.hysteria_password = config.password.clone();
                flat.hysteria_salamander_key = config.salamander_key.clone();
            }
            RelayBackendConfig::TuicV5(config) => {
                flat.tuic_uuid = config.uuid.clone();
                flat.tuic_password = config.password.clone();
                flat.tuic_zero_rtt = config.zero_rtt;
                flat.tuic_congestion_control = config.congestion_control.clone();
            }
            RelayBackendConfig::VlessReality(config) => {
                flat.reality_public_key = config.reality_public_key.clone();
                flat.reality_short_id = config.reality_short_id.clone();
                flat.vless_transport = config.vless_transport.clone();
                flat.xhttp_path = config.xhttp_path.clone();
                flat.xhttp_host = config.xhttp_host.clone();
                flat.vless_uuid = config.uuid.clone();
            }
            RelayBackendConfig::CloudflareTunnel(config) => {
                flat.vless_uuid = config.uuid.clone();
                flat.xhttp_path = config.xhttp_path.clone();
                flat.xhttp_host = config.xhttp_host.clone();
                flat.cloudflare_tunnel_mode = config.tunnel_mode.clone();
                flat.cloudflare_publish_local_origin_url = config.publish_local_origin_url.clone();
                flat.cloudflare_credentials_ref = config.credentials_ref.clone();
                flat.cloudflare_tunnel_token = config.tunnel_token.clone();
                flat.cloudflare_tunnel_credentials_json = config.tunnel_credentials_json.clone();
            }
            RelayBackendConfig::ChainRelay(config) => {
                flat.chain_entry_server = config.entry_server.clone();
                flat.chain_entry_port = config.entry_port;
                flat.chain_entry_server_name = config.entry_server_name.clone();
                flat.chain_entry_public_key = config.entry_public_key.clone();
                flat.chain_entry_short_id = config.entry_short_id.clone();
                flat.chain_entry_profile_id = config.entry_profile_id.clone();
                flat.chain_entry_uuid = config.entry_uuid.clone();
                flat.chain_exit_server = config.exit_server.clone();
                flat.chain_exit_port = config.exit_port;
                flat.chain_exit_server_name = config.exit_server_name.clone();
                flat.chain_exit_public_key = config.exit_public_key.clone();
                flat.chain_exit_short_id = config.exit_short_id.clone();
                flat.chain_exit_profile_id = config.exit_profile_id.clone();
                flat.chain_exit_uuid = config.exit_uuid.clone();
            }
            RelayBackendConfig::Masque(config) => {
                flat.masque_url = config.url.clone();
                flat.masque_use_http2_fallback = config.use_http2_fallback;
                flat.masque_cloudflare_geohash_enabled = config.cloudflare_geohash_enabled;
                flat.masque_auth_mode = config.auth_mode.clone();
                flat.masque_auth_token = config.auth_token.clone();
                flat.masque_client_certificate_chain_pem = config.client_certificate_chain_pem.clone();
                flat.masque_client_private_key_pem = config.client_private_key_pem.clone();
                flat.masque_cloudflare_geohash_header = config.cloudflare_geohash_header.clone();
                flat.masque_privacy_pass_provider_url = config.privacy_pass_provider_url.clone();
                flat.masque_privacy_pass_provider_auth_token = config.privacy_pass_provider_auth_token.clone();
            }
            RelayBackendConfig::ShadowTlsV3(config) => {
                flat.shadow_tls_password = config.password.clone();
                flat.shadow_tls_inner_profile_id = config.inner_profile_id.clone();
                flat.shadow_tls_inner = config.inner.clone();
            }
            RelayBackendConfig::NaiveProxy(config) => {
                flat.naive_path = config.path.clone();
                flat.naive_username = config.username.clone();
                flat.naive_password = config.password.clone();
            }
            RelayBackendConfig::Unsupported(_) => {}
        }
        flat
    }
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
