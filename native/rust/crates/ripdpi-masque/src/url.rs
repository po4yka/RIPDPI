use std::io;
use std::net::SocketAddr;

use url::{Url, form_urlencoded::byte_serialize};

use crate::config::MasqueConfig;

pub(crate) struct ProxyOrigin {
    pub(crate) host: String,
    pub(crate) authority: String,
    pub(crate) udp_base_path: String,
}

pub(crate) struct TargetAuthority {
    pub(crate) host: String,
    pub(crate) port: u16,
}

impl TargetAuthority {
    pub(crate) fn authority(&self) -> String {
        if self.host.contains(':') {
            format!("[{}]:{}", self.host, self.port)
        } else {
            format!("{}:{}", self.host, self.port)
        }
    }
}

pub(crate) fn parse_proxy_origin(config: &MasqueConfig) -> io::Result<ProxyOrigin> {
    let parsed = Url::parse(&config.url)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid MASQUE URL: {error}")))?;
    if parsed.scheme() != "https" {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL must use https"));
    }
    let authority_host =
        parsed.host_str().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL is missing a host"))?;
    let host = ripdpi_tls_profiles::normalize_server_name(authority_host)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let udp_base_path = derive_udp_base_path(parsed.path());
    let port = parsed.port().unwrap_or(443);
    let authority = if port == 443 { authority_host.to_owned() } else { format!("{authority_host}:{port}") };
    Ok(ProxyOrigin { host, authority, udp_base_path })
}

pub(crate) async fn resolve_proxy_socket_addr(
    config: &MasqueConfig,
    proxy_origin: &ProxyOrigin,
) -> io::Result<SocketAddr> {
    if let Some(addr) = config.proxy_socket_addr {
        return Ok(addr);
    }
    let port = url::Url::parse(&config.url).ok().and_then(|url| url.port()).unwrap_or(443);
    config
        .socket_protection
        .resolve_host(&proxy_origin.host, port)
        .await?
        .into_iter()
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "failed to resolve MASQUE proxy host"))
}

fn normalized_url_path(path: &str) -> String {
    let trimmed = path.trim();
    if trimmed.is_empty() { "/".to_string() } else { trimmed.to_string() }
}

fn derive_udp_base_path(path: &str) -> String {
    let normalized = normalized_url_path(path);
    let trimmed = normalized.trim_end_matches('/');
    if trimmed.is_empty() || trimmed == "/" {
        return "/.well-known/masque".to_string();
    }
    if let Some(base) = trimmed.strip_suffix("/ip") {
        return if base.is_empty() { "/.well-known/masque".to_string() } else { base.to_string() };
    }
    trimmed.to_string()
}

pub(crate) fn build_connect_udp_path(proxy_origin: &ProxyOrigin, target: &TargetAuthority) -> String {
    let host = byte_serialize(target.host.as_bytes()).collect::<String>();
    format!("{}/udp/{host}/{}/", proxy_origin.udp_base_path.trim_end_matches('/'), target.port,)
}

pub(crate) fn parse_target(target: &str) -> io::Result<TargetAuthority> {
    if let Ok(address) = target.parse::<SocketAddr>() {
        return Ok(TargetAuthority { host: address.ip().to_string(), port: address.port() });
    }

    if let Some(rest) = target.strip_prefix('[') {
        let (host, port) = rest.rsplit_once("]:").ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target authority: {target}"))
        })?;
        let port = port
            .parse::<u16>()
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target port: {target}")))?;
        return Ok(TargetAuthority { host: host.to_string(), port });
    }

    let (host, port) = target
        .rsplit_once(':')
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target authority: {target}")))?;
    if host.contains(':') {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("unbracketed IPv6 target must use bracketed form: {target}"),
        ));
    }
    Ok(TargetAuthority {
        host: host.to_string(),
        port: port
            .parse::<u16>()
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target port: {target}")))?,
    })
}

#[cfg(test)]
mod bare_ipv6_rejection_tests {
    use super::*;

    /// Regression test (audit H4 siblings): a bare IPv6 literal must be
    /// rejected instead of being silently split into a corrupted host
    /// (`"2001:db8:"`) with a bogus port.
    #[test]
    fn parse_target_rejects_bare_ipv6_target() {
        let error = match parse_target("2001:db8::1") {
            Ok(target) => panic!("bare IPv6 target must be rejected, got host `{}` port {}", target.host, target.port),
            Err(error) => error,
        };
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn parse_target_accepts_bracketed_ipv6_and_domain() {
        let bracketed = parse_target("[2001:db8::1]:443").expect("bracketed IPv6 target parses");
        assert_eq!(bracketed.host, "2001:db8::1");
        assert_eq!(bracketed.port, 443);
        let domain = parse_target("example.com:443").expect("domain target parses");
        assert_eq!(domain.host, "example.com");
        assert_eq!(domain.port, 443);
    }
}

#[cfg(test)]
mod tls_identity_tests {
    use super::*;

    #[test]
    fn proxy_origin_separates_ipv6_tls_name_from_http_authority() {
        let mut config = MasqueConfig {
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            url: String::new(),
            proxy_socket_addr: None,
            tcp_protocol: crate::config::MasqueTcpProtocol::Http2,
            use_http2_fallback: false,
            auth_mode: None,
            auth_token: None,
            client_certificate_chain_pem: None,
            client_private_key_pem: None,
            cloudflare_geohash_header: None,
            privacy_pass_provider_url: None,
            privacy_pass_provider_auth_token: None,
            tls_fingerprint_profile: "native_default".into(),
            root_certificate_pem: None,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            ech_config: None,
        };
        for (url, expected_name, expected) in [
            ("https://[::1]/", "::1", "[::1]"),
            ("https://[::1]:8443/", "::1", "[::1]:8443"),
            ("https://example.test.:8443/", "example.test", "example.test.:8443"),
        ] {
            config.url = url.into();
            let origin = parse_proxy_origin(&config).expect("IPv6 origin");
            assert_eq!(origin.host, expected_name);
            assert_eq!(origin.authority, expected);
        }
    }
}
