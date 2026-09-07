pub use ripdpi_tls_profiles::OutboundEchConfig;
use std::fmt;
use std::io;
use std::net::{IpAddr, SocketAddr};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MasqueAuthMode {
    None,
    Bearer,
    Preshared,
    PrivacyPass,
    CloudflareMtls,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MasqueTcpProtocol {
    Http2,
    Http3,
}

impl MasqueTcpProtocol {
    pub fn from_wire(value: &str) -> io::Result<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "http2" | "h2" => Ok(Self::Http2),
            "http3" | "h3" => Ok(Self::Http3),
            _ => Err(io::Error::new(io::ErrorKind::InvalidInput, "unsupported MASQUE TCP protocol")),
        }
    }
}

/// Configuration for a MASQUE proxy connection.
#[derive(Clone)]
pub struct MasqueConfig {
    /// Runtime-owned policy for keeping carrier sockets outside the app TUN.
    pub socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    /// MASQUE server URL, e.g. `"https://masque.example.com/"`.
    pub url: String,
    /// Pre-resolved proxy socket address from relay bootstrap; leaves URL host available for SNI and HTTP authority.
    pub proxy_socket_addr: Option<SocketAddr>,
    /// Explicit TCP carrier protocol, independent of UDP fallback policy.
    pub tcp_protocol: MasqueTcpProtocol,
    /// Whether an H3 CONNECT-UDP failure may fall back to H2 capsules.
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
    /// TLS fingerprint profile used for HTTP/2 handshakes.
    pub tls_fingerprint_profile: String,
    /// Optional PEM trust anchor for the H2 or H3 proxy TLS certificate. When set,
    /// every PEM certificate is added to the connector's trust store (verification
    /// stays ON — this PINS the proxy cert, it does not disable verification),
    /// which lets a self-signed or private-CA MASQUE proxy be trusted without
    /// relaxing verification. Mirrors `root_certificate_pem` on the Trojan and
    /// AnyTLS clients. `None` uses the system trust store.
    pub root_certificate_pem: Option<String>,
    /// Prefer binding the QUIC transport to a lower, stable-looking UDP source port range.
    pub quic_bind_low_port: bool,
    /// Rebind the owned QUIC transport after the handshake so Quinn performs RFC 9000 path validation.
    pub quic_migrate_after_handshake: bool,
    /// Optional ECH config for owned MASQUE outbounds.
    pub ech_config: Option<OutboundEchConfig>,
}

impl fmt::Debug for MasqueConfig {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("MasqueConfig")
            .field("socket_protection", &self.socket_protection)
            .field("url", &"<redacted>")
            .field("proxy_socket_addr", &self.proxy_socket_addr)
            .field("tcp_protocol", &self.tcp_protocol)
            .field("use_http2_fallback", &self.use_http2_fallback)
            .field("auth_mode", &self.auth_mode)
            .field("auth_token", &self.auth_token.as_ref().map(|_| "<redacted>"))
            .field("client_certificate_chain_pem", &self.client_certificate_chain_pem.as_ref().map(|_| "<redacted>"))
            .field("client_private_key_pem", &self.client_private_key_pem.as_ref().map(|_| "<redacted>"))
            .field("cloudflare_geohash_header", &self.cloudflare_geohash_header.as_ref().map(|_| "<redacted>"))
            .field("privacy_pass_provider_url", &self.privacy_pass_provider_url.as_ref().map(|_| "<redacted>"))
            .field(
                "privacy_pass_provider_auth_token",
                &self.privacy_pass_provider_auth_token.as_ref().map(|_| "<redacted>"),
            )
            .field("tls_fingerprint_profile", &self.tls_fingerprint_profile)
            .field("root_certificate_pem", &self.root_certificate_pem.as_ref().map(|_| "<redacted>"))
            .field("quic_bind_low_port", &self.quic_bind_low_port)
            .field("quic_migrate_after_handshake", &self.quic_migrate_after_handshake)
            .field("ech_config_present", &self.ech_config.is_some())
            .finish()
    }
}

pub fn resolve_ech_config_via_encrypted_dns(
    host: &str,
    outbound_bind_ip: Option<IpAddr>,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<Option<OutboundEchConfig>> {
    ripdpi_ech_dns::resolve_outbound_ech_config_via_encrypted_dns(
        host,
        crate::ech::connect_hooks(outbound_bind_ip, socket_protection),
    )
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tcp_protocol_wire_values_are_explicit_and_unknown_values_fail_closed() {
        assert_eq!(MasqueTcpProtocol::from_wire("http2").expect("HTTP/2"), MasqueTcpProtocol::Http2);
        assert_eq!(MasqueTcpProtocol::from_wire("http3").expect("HTTP/3"), MasqueTcpProtocol::Http3);
        let error = MasqueTcpProtocol::from_wire("auto").expect_err("unknown protocol must fail");
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }
}
