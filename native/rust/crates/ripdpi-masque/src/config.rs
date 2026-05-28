pub use ripdpi_tls_profiles::OutboundEchConfig;
use std::io;
use std::net::SocketAddr;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MasqueAuthMode {
    None,
    Bearer,
    Preshared,
    PrivacyPass,
    CloudflareMtls,
}

/// Configuration for a MASQUE proxy connection.
#[derive(Debug, Clone)]
pub struct MasqueConfig {
    /// MASQUE server URL, e.g. `"https://masque.example.com/"`.
    pub url: String,
    /// Pre-resolved proxy socket address from relay bootstrap; leaves URL host available for SNI and HTTP authority.
    pub proxy_socket_addr: Option<SocketAddr>,
    /// Whether to fall back to HTTP/2 CONNECT if HTTP/3 (QUIC) fails.
    pub use_http2_fallback: bool,
    /// Auth mode: `"bearer"`, `"preshared"`, `"privacy_pass"`, or legacy `"token"`.
    pub auth_mode: Option<String>,
    /// Shared secret used by bearer and preshared auth modes.
    pub auth_token: Option<String>,
    /// Client certificate chain used by the generic TLS client certificate mode.
    pub client_certificate_chain_pem: Option<String>,
    /// Client private key used by the generic TLS client certificate mode.
    pub client_private_key_pem: Option<String>,
    /// Legacy config field retained for wire compatibility; generic providers do not emit proprietary geohash headers.
    pub cloudflare_geohash_header: Option<String>,
    /// Deployer-supplied Privacy Pass provider endpoint.
    pub privacy_pass_provider_url: Option<String>,
    /// Optional bearer token used to authenticate to the deployer-supplied Privacy Pass provider.
    pub privacy_pass_provider_auth_token: Option<String>,
    /// TLS fingerprint profile used for HTTP/2 fallback handshakes.
    pub tls_fingerprint_profile: String,
    /// Prefer binding the QUIC transport to a lower, stable-looking UDP source port range.
    pub quic_bind_low_port: bool,
    /// Rebind the owned QUIC transport after the handshake so Quinn performs RFC 9000 path validation.
    pub quic_migrate_after_handshake: bool,
    /// Optional ECH config for owned MASQUE outbounds.
    pub ech_config: Option<OutboundEchConfig>,
}

pub fn resolve_ech_config_via_encrypted_dns(host: &str) -> io::Result<Option<OutboundEchConfig>> {
    ripdpi_diagnostics_dns::dns::resolve_outbound_ech_config_via_encrypted_dns(host)
        .map_err(|error| io::Error::other(format!("MASQUE ECH resolution failed: {error}")))
}

impl MasqueConfig {
    pub fn effective_auth_mode(&self) -> MasqueAuthMode {
        match self.auth_mode.as_deref().map(str::trim).map(str::to_ascii_lowercase).as_deref() {
            Some("bearer" | "token") => MasqueAuthMode::Bearer,
            Some("preshared") => MasqueAuthMode::Preshared,
            Some("privacy_pass") => MasqueAuthMode::PrivacyPass,
            Some("cloudflare_mtls") => MasqueAuthMode::CloudflareMtls,
            Some(_) => MasqueAuthMode::None,
            None if self.auth_token.as_deref().is_some_and(|value| !value.trim().is_empty()) => MasqueAuthMode::Bearer,
            None => MasqueAuthMode::None,
        }
    }
}
