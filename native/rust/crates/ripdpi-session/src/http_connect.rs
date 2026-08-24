use std::net::SocketAddr;

use crate::types::{ClientRequest, NameResolver, SessionError, SocketType, TargetAddr};

pub fn parse_http_connect_request(buffer: &[u8], resolver: &dyn NameResolver) -> Result<ClientRequest, SessionError> {
    let text = std::str::from_utf8(buffer).map_err(|_| SessionError::generic())?;
    let mut lines = text.lines();
    let request_line = lines.next().ok_or_else(SessionError::generic)?;
    if !request_line.starts_with("CONNECT ") {
        return Err(SessionError::generic());
    }
    let host_header =
        text.lines().find(|line| line.to_ascii_lowercase().starts_with("host:")).ok_or_else(SessionError::generic)?;
    let host = host_header[5..].trim();
    let (name, port) = split_host_port(host).ok_or_else(SessionError::generic)?;
    let addr = resolver
        .resolve(name, SocketType::Stream)
        .map(|resolved| SocketAddr::new(resolved.ip(), port))
        .ok_or_else(SessionError::generic)?;
    Ok(ClientRequest::HttpConnect(TargetAddr { addr, host: Some(name.to_string()) }))
}

fn split_host_port(value: &str) -> Option<(&str, u16)> {
    let (host, port) = value.rsplit_once(':')?;
    if !host.starts_with('[') && host.contains(':') {
        return None;
    }
    let port = port.parse::<u16>().ok()?;
    Some((host.trim_matches(|ch| ch == '[' || ch == ']'), port))
}

#[cfg(test)]
mod tests {
    use std::net::SocketAddr;

    use crate::{ClientRequest, SocketType, TargetAddr};

    use super::*;

    fn resolver(host: &str, socket_type: SocketType) -> Option<SocketAddr> {
        match (host, socket_type) {
            ("example.com", SocketType::Stream) => Some(SocketAddr::from(([198, 51, 100, 10], 0))),
            _ => None,
        }
    }

    #[test]
    fn parse_http_connect_request_uses_host_header() {
        let request = b"CONNECT ignored HTTP/1.1\r\nHost: example.com:8443\r\n\r\n";
        let parsed = parse_http_connect_request(request, &resolver).expect("parse connect");

        assert_eq!(
            parsed,
            ClientRequest::HttpConnect(TargetAddr {
                addr: SocketAddr::from(([198, 51, 100, 10], 8443)),
                host: Some("example.com".to_string()),
            })
        );
    }

    #[test]
    fn split_host_port_ipv6_bracket_stripping() {
        assert_eq!(split_host_port("[::1]:443"), Some(("::1", 443)));
    }

    #[test]
    fn parse_http_connect_request_not_connect_method() {
        let request = b"GET / HTTP/1.1\r\nHost: example.com:443\r\n\r\n";
        let result = parse_http_connect_request(request, &resolver);
        assert!(result.is_err());
    }

    #[test]
    fn parse_http_connect_request_no_host_header() {
        let request = b"CONNECT example.com:443 HTTP/1.1\r\n\r\n";
        let result = parse_http_connect_request(request, &resolver);
        assert!(result.is_err());
    }

    #[test]
    fn parse_http_connect_request_unresolvable_host() {
        let request = b"CONNECT unknown.invalid:443 HTTP/1.1\r\nHost: unknown.invalid:443\r\n\r\n";
        let result = parse_http_connect_request(request, &resolver);
        assert!(result.is_err());
    }

    #[test]
    fn parse_http_connect_request_invalid_utf8() {
        let request: &[u8] = &[0x43, 0x4f, 0x4e, 0x4e, 0x45, 0x43, 0x54, 0xff, 0xfe];
        let result = parse_http_connect_request(request, &resolver);
        assert!(result.is_err());
    }

    #[test]
    fn split_host_port_missing_port() {
        assert_eq!(split_host_port("example.com"), None);
    }

    #[test]
    fn split_host_port_standard_host() {
        assert_eq!(split_host_port("example.com:8080"), Some(("example.com", 8080)));
    }

    #[test]
    fn split_host_port_invalid_port() {
        assert_eq!(split_host_port("example.com:notaport"), None);
    }

    #[test]
    fn split_host_port_port_overflow() {
        assert_eq!(split_host_port("example.com:99999"), None);
    }

    #[test]
    fn split_host_port_empty_string() {
        assert_eq!(split_host_port(""), None);
    }
}

#[cfg(test)]
mod bare_ipv6_rejection_tests {
    use super::split_host_port;

    /// Regression test (audit H4 siblings): a bare IPv6 authority must be
    /// rejected instead of being silently split into a corrupted host
    /// (`"2001:db8:"`) with a bogus port.
    #[test]
    fn split_host_port_rejects_bare_ipv6() {
        assert_eq!(split_host_port("2001:db8::1"), None);
        assert_eq!(split_host_port("2001:db8::1:443"), None);
    }

    #[test]
    fn split_host_port_accepts_bracketed_ipv6() {
        assert_eq!(split_host_port("[2001:db8::1]:443"), Some(("2001:db8::1", 443)));
    }
}
