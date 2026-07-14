use std::net::SocketAddr;

#[derive(Clone)]
pub struct MasqueRelayConfig {
    pub url: String,
    pub proxy_socket_addr: Option<SocketAddr>,
    pub tcp_protocol: String,
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

impl_redacted_debug!(MasqueRelayConfig {
    proxy_socket_addr,
    tcp_protocol,
    use_http2_fallback,
    cloudflare_geohash_enabled,
    auth_mode,
});

impl Default for MasqueRelayConfig {
    fn default() -> Self {
        Self {
            url: String::new(),
            proxy_socket_addr: None,
            tcp_protocol: "http2".to_string(),
            use_http2_fallback: false,
            cloudflare_geohash_enabled: false,
            auth_mode: None,
            auth_token: None,
            client_certificate_chain_pem: None,
            client_private_key_pem: None,
            cloudflare_geohash_header: None,
            privacy_pass_provider_url: None,
            privacy_pass_provider_auth_token: None,
        }
    }
}
