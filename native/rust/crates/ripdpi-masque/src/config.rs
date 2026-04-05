/// Configuration for a MASQUE proxy connection.
#[derive(Debug, Clone)]
pub struct MasqueConfig {
    /// MASQUE server URL, e.g. `"https://masque.example.com/"`.
    pub url: String,
    /// Whether to fall back to HTTP/2 CONNECT if HTTP/3 (QUIC) fails.
    pub use_http2_fallback: bool,
    /// Enable Cloudflare-specific auth headers.
    pub cloudflare_mode: bool,
    /// Auth mode: `"token"` or `"cloudflare"`.
    pub auth_mode: Option<String>,
    /// Bearer token for simple auth.
    pub auth_token: Option<String>,
    /// Cloudflare client ID.
    pub cf_client_id: Option<String>,
    /// Cloudflare key ID.
    pub cf_key_id: Option<String>,
    /// Cloudflare ECDSA P-256 private key in PEM format.
    pub cf_private_key_pem: Option<String>,
}
