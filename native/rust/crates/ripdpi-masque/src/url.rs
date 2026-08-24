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
    let host = parsed
        .host_str()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL is missing a host"))?
        .to_string();
    let udp_base_path = derive_udp_base_path(parsed.path());
    let port = parsed.port().unwrap_or(443);
    let authority = if port == 443 { host.clone() } else { format!("{host}:{port}") };
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
