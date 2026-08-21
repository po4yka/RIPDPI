pub(crate) use super::*;
/// The minimum and maximum number of hops a composed chain may carry.
///
/// Mirrors the Kotlin `RelayChainHopBounds` (min 2, max 4). The native
/// composition rejects a hop list outside this inclusive range so a malformed
/// runtime config cannot fold into a zero- or one-hop "chain" (which would be a
/// plain relay) nor an unbounded fan-out.
pub const CHAIN_RELAY_MIN_HOPS: usize = 2;
pub const CHAIN_RELAY_MAX_HOPS: usize = 4;

/// Resolved chain-relay backend config.
///
/// The ordered [`hops`](Self::hops) list is the N-hop source of truth: when it
/// is non-empty the composition folds across it in order (`hops[0]` is the
/// entry that opens the single real outbound socket; every later hop tunnels
/// through the prior hop's stream and opens no new OS socket). When `hops` is
/// empty the required resolved `entry`+`exit` fields are folded into a
/// 2-element list by [`ordered_hops`](Self::ordered_hops). Scalar-only legacy
/// chain payloads are not composed because they cannot carry per-hop identity.
#[derive(Clone, Default)]
pub struct ChainRelayConfig {
    /// Ordered N-hop list (min 2, max 4 once validated). Empty means "fold the
    /// required resolved entry/exit fields below into a 2-hop list".
    pub hops: Vec<ResolvedChainRelayHopConfig>,
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

impl std::fmt::Debug for ChainRelayConfig {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ChainRelayConfig")
            .field("hops", &self.hops)
            .field("entry", &self.entry)
            .field("exit", &self.exit)
            .field("credentials", &REDACTED_CREDENTIALS)
            .finish_non_exhaustive()
    }
}

impl ChainRelayConfig {
    /// The effective ordered hop list the composition folds across.
    ///
    /// Returns [`hops`](Self::hops) verbatim when it is non-empty; otherwise
    /// folds the resolved `entry`/`exit` hops into an ordered 2-element list.
    /// A missing resolved hop produces an empty list so bounds validation fails
    /// closed instead of synthesizing a fingerprint identity. The
    /// returned list is *not* yet bounds-checked — callers run
    /// [`validate_hop_count`](Self::validate_hop_count).
    #[must_use]
    pub fn ordered_hops(&self) -> Vec<ResolvedChainRelayHopConfig> {
        if !self.hops.is_empty() {
            return self.hops.clone();
        }
        match (self.entry.as_deref(), self.exit.as_deref()) {
            (Some(entry), Some(exit)) => vec![entry.clone(), exit.clone()],
            _ => Vec::new(),
        }
    }

    /// Number of hops the composition would fold across, for diagnostics and
    /// bounds checks.
    #[must_use]
    pub fn hop_count(&self) -> usize {
        self.ordered_hops().len()
    }

    /// Reject a hop list outside `[CHAIN_RELAY_MIN_HOPS, CHAIN_RELAY_MAX_HOPS]`.
    pub fn validate_hop_count(count: usize) -> std::io::Result<()> {
        if (CHAIN_RELAY_MIN_HOPS..=CHAIN_RELAY_MAX_HOPS).contains(&count) {
            Ok(())
        } else {
            Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                format!("chain relay must compose {CHAIN_RELAY_MIN_HOPS}..={CHAIN_RELAY_MAX_HOPS} hops, got {count}"),
            ))
        }
    }
}

fn default_chain_hop_server_port() -> i32 {
    443
}

fn default_chain_hop_vless_transport() -> String {
    "reality_tcp".to_string()
}

fn default_chain_hop_xhttp_mode() -> String {
    "auto".to_string()
}

fn default_chain_hop_vless_flow() -> String {
    "xtls-rprx-vision".to_string()
}

fn default_chain_hop_cloudflare_tunnel_mode() -> String {
    "consume_existing".to_string()
}

fn default_chain_hop_masque_use_http2_fallback() -> bool {
    true
}

fn default_chain_hop_masque_tcp_protocol() -> String {
    "http2".to_string()
}

fn default_chain_hop_tuic_congestion_control() -> String {
    "bbr".to_string()
}

fn default_chain_hop_tls_fingerprint_profile() -> String {
    "chrome_stable".to_string()
}

#[derive(Clone, Deserialize, Serialize)]
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
    #[serde(default = "default_chain_hop_vless_flow")]
    pub vless_flow: String,
    #[serde(default)]
    pub xhttp_path: String,
    #[serde(default)]
    pub xhttp_host: String,
    #[serde(default = "default_chain_hop_xhttp_mode")]
    pub xhttp_mode: String,
    #[serde(default = "default_chain_hop_cloudflare_tunnel_mode")]
    pub cloudflare_tunnel_mode: String,
    #[serde(default)]
    pub cloudflare_publish_local_origin_url: String,
    #[serde(default)]
    pub cloudflare_credentials_ref: String,
    #[serde(default)]
    pub masque_url: String,
    #[serde(default = "default_chain_hop_masque_tcp_protocol")]
    pub masque_tcp_protocol: String,
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
    pub anytls_root_certificate_pem: Option<String>,
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

impl_redacted_debug!(ResolvedChainRelayHopConfig {
    kind,
    profile_id,
    server,
    server_port,
    server_name,
    vless_transport,
    vless_flow,
    xhttp_mode,
    cloudflare_tunnel_mode,
    masque_tcp_protocol,
    masque_use_http2_fallback,
    masque_cloudflare_geohash_enabled,
    tuic_zero_rtt,
    tuic_congestion_control,
    shadow_tls_inner_profile_id,
    shadow_tls_inner,
    shadowsocks_method,
    tls_fingerprint_profile,
    masque_auth_mode,
    finalmask,
});

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
            vless_flow: default_chain_hop_vless_flow(),
            xhttp_path: String::new(),
            xhttp_host: String::new(),
            xhttp_mode: default_chain_hop_xhttp_mode(),
            cloudflare_tunnel_mode: default_chain_hop_cloudflare_tunnel_mode(),
            cloudflare_publish_local_origin_url: String::new(),
            cloudflare_credentials_ref: String::new(),
            masque_url: String::new(),
            masque_tcp_protocol: default_chain_hop_masque_tcp_protocol(),
            masque_use_http2_fallback: default_chain_hop_masque_use_http2_fallback(),
            masque_cloudflare_geohash_enabled: false,
            tuic_zero_rtt: false,
            tuic_congestion_control: default_chain_hop_tuic_congestion_control(),
            shadow_tls_inner_profile_id: String::new(),
            shadow_tls_inner: None,
            trojan_root_certificate_pem: None,
            anytls_root_certificate_pem: None,
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
