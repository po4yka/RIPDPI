#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MasqueAuthMode {
    None,
    Bearer,
    Preshared,
    PrivacyPass,
}

/// Configuration for a MASQUE proxy connection.
#[derive(Debug, Clone)]
pub struct MasqueConfig {
    /// MASQUE server URL, e.g. `"https://masque.example.com/"`.
    pub url: String,
    /// Whether to fall back to HTTP/2 CONNECT if HTTP/3 (QUIC) fails.
    pub use_http2_fallback: bool,
    /// Legacy Cloudflare migration flag. When no explicit auth mode is set this maps to `privacy_pass`.
    pub cloudflare_mode: bool,
    /// Auth mode: `"bearer"`, `"preshared"`, `"privacy_pass"`, or legacy `"token"`.
    pub auth_mode: Option<String>,
    /// Shared secret used by bearer and preshared auth modes.
    pub auth_token: Option<String>,
    /// Legacy Cloudflare client ID.
    pub cf_client_id: Option<String>,
    /// Legacy Cloudflare key ID.
    pub cf_key_id: Option<String>,
    /// Legacy Cloudflare ECDSA P-256 private key in PEM format.
    pub cf_private_key_pem: Option<String>,
    /// Deployer-supplied Privacy Pass provider endpoint.
    pub privacy_pass_provider_url: Option<String>,
    /// Optional bearer token used to authenticate to the deployer-supplied Privacy Pass provider.
    pub privacy_pass_provider_auth_token: Option<String>,
    /// TLS fingerprint profile used for HTTP/2 fallback handshakes.
    pub tls_fingerprint_profile: String,
}

impl MasqueConfig {
    pub fn effective_auth_mode(&self) -> MasqueAuthMode {
        match self.auth_mode.as_deref().map(str::trim).map(str::to_ascii_lowercase).as_deref() {
            Some("bearer") | Some("token") => MasqueAuthMode::Bearer,
            Some("preshared") => MasqueAuthMode::Preshared,
            Some("privacy_pass") => MasqueAuthMode::PrivacyPass,
            Some(_) => MasqueAuthMode::None,
            None if self.cloudflare_mode => MasqueAuthMode::PrivacyPass,
            None if self.auth_token.as_deref().is_some_and(|value| !value.trim().is_empty()) => MasqueAuthMode::Bearer,
            None => MasqueAuthMode::None,
        }
    }
}
