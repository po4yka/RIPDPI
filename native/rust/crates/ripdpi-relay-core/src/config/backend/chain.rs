#[derive(Debug, Clone, Default)]
pub struct ChainRelayConfig {
    pub entry: Option<Box<ResolvedChainRelayHopConfig>>,
    pub entry_server: String,
    pub entry_port: i32,
    pub entry_server_name: String,
    pub entry_public_key: String,
    pub entry_short_id: String,
    pub entry_profile_id: String,
    pub entry_uuid: Option<String>,
    pub exit: Option<Box<ResolvedChainRelayHopConfig>>,
    pub exit_server: String,
    pub exit_port: i32,
    pub exit_server_name: String,
    pub exit_public_key: String,
    pub exit_short_id: String,
    pub exit_profile_id: String,
    pub exit_uuid: Option<String>,
}

fn default_chain_hop_server_port() -> i32 {
    443
}

fn default_chain_hop_vless_transport() -> String {
    "reality_tcp".to_string()
}

fn default_chain_hop_cloudflare_tunnel_mode() -> String {
    "consume_existing".to_string()
}

fn default_chain_hop_masque_use_http2_fallback() -> bool {
    true
}

fn default_chain_hop_tuic_congestion_control() -> String {
    "bbr".to_string()
}

fn default_chain_hop_tls_fingerprint_profile() -> String {
    "chrome_stable".to_string()
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedChainRelayHopConfig {
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub profile_id: String,
    #[serde(default)]
    pub server: String,
    #[serde(default = "default_chain_hop_server_port")]
    pub server_port: i32,
    #[serde(default)]
    pub server_name: String,
    #[serde(default)]
    pub reality_public_key: String,
    #[serde(default)]
    pub reality_short_id: String,
    #[serde(default = "default_chain_hop_vless_transport")]
    pub vless_transport: String,
    #[serde(default)]
    pub xhttp_path: String,
    #[serde(default)]
    pub xhttp_host: String,
    #[serde(default = "default_chain_hop_cloudflare_tunnel_mode")]
    pub cloudflare_tunnel_mode: String,
    #[serde(default)]
    pub cloudflare_publish_local_origin_url: String,
    #[serde(default)]
    pub cloudflare_credentials_ref: String,
    #[serde(default)]
    pub masque_url: String,
    #[serde(default = "default_chain_hop_masque_use_http2_fallback")]
    pub masque_use_http2_fallback: bool,
    #[serde(default)]
    pub masque_cloudflare_geohash_enabled: bool,
    #[serde(default)]
    pub tuic_zero_rtt: bool,
    #[serde(default = "default_chain_hop_tuic_congestion_control")]
    pub tuic_congestion_control: String,
    #[serde(default)]
    pub shadow_tls_inner_profile_id: String,
    #[serde(default)]
    pub shadow_tls_inner: Option<ResolvedShadowTlsInnerRelayConfig>,
    #[serde(default)]
    pub trojan_root_certificate_pem: Option<String>,
    #[serde(default)]
    pub naive_path: String,
    #[serde(default)]
    pub vless_uuid: Option<String>,
    #[serde(default)]
    pub hysteria_password: Option<String>,
    #[serde(default)]
    pub hysteria_salamander_key: Option<String>,
    #[serde(default)]
    pub anytls_password: Option<String>,
    #[serde(default)]
    pub tuic_uuid: Option<String>,
    #[serde(default)]
    pub tuic_password: Option<String>,
    #[serde(default)]
    pub shadow_tls_password: Option<String>,
    #[serde(default)]
    pub trojan_password: Option<String>,
    #[serde(default)]
    pub shadowsocks_method: Option<String>,
    #[serde(default)]
    pub shadowsocks_password: Option<String>,
    #[serde(default)]
    pub naive_username: Option<String>,
    #[serde(default)]
    pub naive_password: Option<String>,
    #[serde(default = "default_chain_hop_tls_fingerprint_profile")]
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

impl Default for ResolvedChainRelayHopConfig {
    fn default() -> Self {
        Self {
            kind: String::new(),
            profile_id: String::new(),
            server: String::new(),
            server_port: default_chain_hop_server_port(),
            server_name: String::new(),
            reality_public_key: String::new(),
            reality_short_id: String::new(),
            vless_transport: default_chain_hop_vless_transport(),
            xhttp_path: String::new(),
            xhttp_host: String::new(),
            cloudflare_tunnel_mode: default_chain_hop_cloudflare_tunnel_mode(),
            cloudflare_publish_local_origin_url: String::new(),
            cloudflare_credentials_ref: String::new(),
            masque_url: String::new(),
            masque_use_http2_fallback: default_chain_hop_masque_use_http2_fallback(),
            masque_cloudflare_geohash_enabled: false,
            tuic_zero_rtt: false,
            tuic_congestion_control: default_chain_hop_tuic_congestion_control(),
            shadow_tls_inner_profile_id: String::new(),
            shadow_tls_inner: None,
            trojan_root_certificate_pem: None,
            naive_path: String::new(),
            vless_uuid: None,
            hysteria_password: None,
            hysteria_salamander_key: None,
            anytls_password: None,
            tuic_uuid: None,
            tuic_password: None,
            shadow_tls_password: None,
            trojan_password: None,
            shadowsocks_method: None,
            shadowsocks_password: None,
            naive_username: None,
            naive_password: None,
            tls_fingerprint_profile: default_chain_hop_tls_fingerprint_profile(),
            masque_auth_mode: None,
            masque_auth_token: None,
            masque_client_certificate_chain_pem: None,
            masque_client_private_key_pem: None,
            masque_cloudflare_geohash_header: None,
            masque_privacy_pass_provider_url: None,
            masque_privacy_pass_provider_auth_token: None,
            cloudflare_tunnel_token: None,
            cloudflare_tunnel_credentials_json: None,
            finalmask: ResolvedRelayFinalmaskConfig::default(),
        }
    }
}
