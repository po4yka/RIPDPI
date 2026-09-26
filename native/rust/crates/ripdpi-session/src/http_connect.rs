use std::net::SocketAddr;

use crate::types::{ClientRequest, NameResolver, SessionError, SocketType, TargetAddr};

pub fn parse_http_connect_request(buffer: &[u8], resolver: &dyn NameResolver) -> Result<ClientRequest, SessionError> {
    let header_end = buffer
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .map(|position| position + 4)
        .ok_or_else(SessionError::generic)?;
    let text = std::str::from_utf8(&buffer[..header_end]).map_err(|_| SessionError::generic())?;
    let mut lines = text.lines();
    let request_line = lines.next().ok_or_else(SessionError::generic)?;
    let mut parts = request_line.split_ascii_whitespace();
    if parts.next() != Some("CONNECT") {
        return Err(SessionError::generic());
    }
    let authority = parts.next().ok_or_else(SessionError::generic)?;
    if !matches!(parts.next(), Some("HTTP/1.0" | "HTTP/1.1")) || parts.next().is_some() {
        return Err(SessionError::generic());
    }
    let (name, port) = split_host_port(authority).ok_or_else(SessionError::generic)?;
    let mut hosts = lines.take_while(|line| !line.is_empty()).filter_map(|line| {
        line.split_once(':').filter(|(field, _)| field.eq_ignore_ascii_case("host")).map(|(_, value)| value.trim())
    });
    let host = hosts.next().ok_or_else(SessionError::generic)?;
    if hosts.next().is_some() {
        return Err(SessionError::generic());
    }
    let unbracketed_host = host.strip_prefix('[').and_then(|value| value.strip_suffix(']')).unwrap_or(host);
    let (host_name, host_port) =
        split_host_port(host).map_or((unbracketed_host, None), |(name, port)| (name, Some(port)));
    if !host_name.eq_ignore_ascii_case(name) || host_port.is_some_and(|host_port| host_port != port) {
        return Err(SessionError::generic());
    }
    let addr = resolver
        .resolve(name, SocketType::Stream)
        .map(|resolved| SocketAddr::new(resolved.ip(), port))
        .ok_or_else(SessionError::generic)?;
    Ok(ClientRequest::HttpConnect(TargetAddr { addr, host: Some(name.to_string()) }))
}

fn split_host_port(value: &str) -> Option<(&str, u16)> {
    let (host, port) = value.rsplit_once(':')?;
    let port = port.parse::<u16>().ok().filter(|port| *port != 0)?;
    let host = if let Some(host) = host.strip_prefix('[') {
        let host = host.strip_suffix(']')?;
        host.parse::<std::net::Ipv6Addr>().ok()?;
        host
    } else {
        if host.is_empty() || host.contains(['[', ']', ':']) {
            return None;
        }
        host
    };
    Some((host, port))
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
    fn parse_http_connect_request_uses_connect_target() {
        let request = b"CONNECT example.com:8443 HTTP/1.1\r\nHost: example.com:8443\r\n\r\n";
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
    fn parse_http_connect_request_rejects_mismatched_host() {
        let request = b"CONNECT other.example:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n";
        assert!(parse_http_connect_request(request, &resolver).is_err());
    }

    #[test]
    fn parse_http_connect_request_accepts_host_without_port() {
        let request = b"CONNECT example.com:8443 HTTP/1.1\r\nHost: example.com\r\n\r\n";
        assert!(parse_http_connect_request(request, &resolver).is_ok());
    }

    #[test]
    fn parse_http_connect_request_accepts_binary_tunnel_data() {
        let request = b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n\r\n\x16\x03\xff";
        assert!(parse_http_connect_request(request, &resolver).is_ok());
    }

    #[test]
    fn parse_http_connect_request_ignores_tunneled_host_text() {
        let request = b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n\r\nHost: tunneled.example\r\n";
        assert!(parse_http_connect_request(request, &resolver).is_ok());
    }

    #[test]
    fn parse_http_connect_request_rejects_duplicate_host() {
        let request = b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\nHost: example.com\r\n\r\n";
        assert!(parse_http_connect_request(request, &resolver).is_err());
    }

    #[test]
    fn split_host_port_rejects_zero_port() {
        assert_eq!(split_host_port("example.com:0"), None);
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
