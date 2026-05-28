use std::net::SocketAddr;

#[derive(Debug, Clone, Default)]
pub struct MasqueRelayConfig {
    pub url: String,
    pub proxy_socket_addr: Option<SocketAddr>,
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
