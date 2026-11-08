use std::net::SocketAddr;

use crate::types::{ClientRequest, NameResolver, S_ER_CMD, S_ER_HOST, SessionError, SocketType, TargetAddr};

pub fn parse_http_connect_request(buffer: &[u8], resolver: &dyn NameResolver) -> Result<ClientRequest, SessionError> {
    HttpConnectHead::parse(buffer)?.resolve(resolver)
}

/// One complete CRLF-delimited CONNECT header. Tunnel bytes are excluded.
#[derive(Clone, Copy)]
pub struct HttpConnectHead<'a> {
    bytes: &'a [u8],
}

impl<'a> HttpConnectHead<'a> {
    pub fn parse(buffer: &'a [u8]) -> Result<Self, SessionError> {
        let end = buffer.windows(4).position(|part| part == b"\r\n\r\n").ok_or_else(SessionError::generic)?;
        let head = Self { bytes: &buffer[..end] };
        if !valid_header_line_endings(head.bytes) {
            return Err(SessionError::generic());
        }
        let request_line = head.lines().next().ok_or_else(SessionError::generic)?;
        if !request_line.is_ascii() || request_line.iter().any(|byte| byte.is_ascii_control() && *byte != b'\t') {
            return Err(SessionError::generic());
        }
        let mut proxy_authorization_seen = false;
        for line in head.lines().skip(1) {
            let colon = line.iter().position(|byte| *byte == b':').ok_or_else(SessionError::generic)?;
            let name = &line[..colon];
            if name.is_empty() || !name.iter().copied().all(is_field_name_byte) {
                return Err(SessionError::generic());
            }
            let value = &line[colon + 1..];
            if value.iter().any(|byte| *byte != b'\t' && (*byte < b' ' || *byte == 0x7f))
                || (name.eq_ignore_ascii_case(b"Host") && !value.is_ascii())
            {
                return Err(SessionError::generic());
            }
            if name.eq_ignore_ascii_case(b"Proxy-Authorization") {
                if proxy_authorization_seen {
                    return Err(SessionError::generic());
                }
                proxy_authorization_seen = true;
            }
            let value = value.trim_ascii();
            if name.eq_ignore_ascii_case(b"Transfer-Encoding")
                || (name.eq_ignore_ascii_case(b"Content-Length")
                    && (value.is_empty() || value.iter().any(|byte| *byte != b'0')))
            {
                return Err(SessionError::generic());
            }
        }
        Ok(head)
    }

    fn lines(self) -> impl Iterator<Item = &'a [u8]> {
        self.bytes.split(|byte| *byte == b'\n').map(|line| line.strip_suffix(b"\r").unwrap_or(line))
    }

    fn fields(self) -> impl Iterator<Item = (&'a [u8], &'a [u8])> {
        self.lines().skip(1).filter_map(|line| {
            let colon = line.iter().position(|byte| *byte == b':')?;
            Some((&line[..colon], &line[colon + 1..]))
        })
    }

    pub fn proxy_authorization(self) -> Option<&'a str> {
        self.fields()
            .find(|(name, _)| name.eq_ignore_ascii_case(b"Proxy-Authorization"))
            .and_then(|(_, value)| std::str::from_utf8(value).ok())
    }

    pub fn resolve(self, resolver: &dyn NameResolver) -> Result<ClientRequest, SessionError> {
        let request_line = std::str::from_utf8(self.lines().next().ok_or_else(SessionError::generic)?)
            .map_err(|_| SessionError::generic())?;
        let mut parts = request_line.split_ascii_whitespace();
        if parts.next() != Some("CONNECT") {
            return Err(SessionError::socks5(S_ER_CMD));
        }
        let authority = parts.next().ok_or_else(SessionError::generic)?;
        let version = parts.next();
        if !matches!(version, Some("HTTP/1.0" | "HTTP/1.1")) || parts.next().is_some() {
            return Err(SessionError::generic());
        }
        let (name, port) = split_host_port(authority).ok_or_else(SessionError::generic)?;
        let mut hosts = self.fields().filter(|(name, _)| name.eq_ignore_ascii_case(b"Host"));
        let host = hosts.next();
        if hosts.next().is_some() {
            return Err(SessionError::generic());
        }
        if let Some((_, host)) = host {
            let host = std::str::from_utf8(host).map_err(|_| SessionError::generic())?.trim();
            let unbracketed_host = host.strip_prefix('[').and_then(|value| value.strip_suffix(']')).unwrap_or(host);
            let (host_name, host_port) =
                split_host_port(host).map_or((unbracketed_host, None), |(name, port)| (name, Some(port)));
            if !host_name.eq_ignore_ascii_case(name) || host_port.is_some_and(|host_port| host_port != port) {
                return Err(SessionError::generic());
            }
        } else if version == Some("HTTP/1.1") {
            return Err(SessionError::generic());
        }
        let addr = resolver
            .resolve(name, SocketType::Stream)
            .map(|resolved| SocketAddr::new(resolved.ip(), port))
            .ok_or(SessionError::socks5(S_ER_HOST))?;
        Ok(ClientRequest::HttpConnect(TargetAddr { addr, host: Some(name.to_string()) }))
    }
}

fn valid_header_line_endings(header: &[u8]) -> bool {
    header.iter().enumerate().all(|(index, byte)| match byte {
        b'\r' => header.get(index + 1) == Some(&b'\n'),
        b'\n' => index > 0 && header[index - 1] == b'\r',
        _ => true,
    })
}

// RFC 9110 section 5.1: each field name is an HTTP token.
fn is_field_name_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || b"!#$%&'*+-.^_|~`".contains(&byte)
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

    #[test]
    fn parse_http_connect_request_http10_host_is_optional() {
        let request = b"CONNECT example.com:8443 HTTP/1.0\r\n\r\n";
        assert_eq!(
            parse_http_connect_request(request, &resolver).expect("parse HTTP/1.0 authority"),
            ClientRequest::HttpConnect(TargetAddr {
                addr: SocketAddr::from(([198, 51, 100, 10], 8443)),
                host: Some("example.com".to_string()),
            })
        );
        for fields in ["Host:\r\n", "Host: other.example\r\n", "Host: example.com\r\nHost: example.com\r\n"] {
            let request = format!("CONNECT example.com:8443 HTTP/1.0\r\n{fields}\r\n");
            assert_eq!(
                parse_http_connect_request(request.as_bytes(), &resolver).err().map(|error| error.code),
                Some(crate::S_ER_GEN),
                "{fields:?}"
            );
        }
    }

    #[test]
    fn parse_http_connect_request_rejects_invalid_field_lines() {
        for field in [
            &b"Ignored : value\r\n"[..],
            b"Host : other.invalid\r\n",
            b"X-Test: a\x00b\r\n",
            b"X-Test: a\rb\r\n",
            b"X-Test: a\x1fb\r\n",
            b"X-Test: a\x7fb\r\n",
            b"no colon here\r\n",
            b": no name\r\n",
            b"X-Test: one\r\n two\r\n",
            b"X-Test: value\n",
            b"Proxy-Authorization: Basic one\r\nProxy-Authorization: Basic two\r\n",
        ] {
            let mut request = b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n".to_vec();
            request.extend_from_slice(field);
            request.extend_from_slice(b"\r\n");
            assert_eq!(
                HttpConnectHead::parse(&request).err().map(|error| error.code),
                Some(crate::S_ER_GEN),
                "head {field:?}"
            );
            assert_eq!(
                parse_http_connect_request(&request, &resolver).err().map(|error| error.code),
                Some(crate::S_ER_GEN),
                "{field:?}"
            );
        }
        let request = b"CONNECT example.com:443 HTTP/1.1\r\nHost: \xc2\xa0example.com\r\n\r\n";
        assert_eq!(parse_http_connect_request(request, &resolver).err().map(|error| error.code), Some(crate::S_ER_GEN));
        let request = b"CONNECT example.com\x00:443 HTTP/1.1\r\nHost: example.com\x00\r\n\r\n";
        assert_eq!(parse_http_connect_request(request, &resolver).err().map(|error| error.code), Some(crate::S_ER_GEN));
    }

    #[test]
    fn parse_http_connect_request_accepts_field_tokens_and_ows() {
        let request =
            b"CONNECT example.com:443 HTTP/1.1\r\nHost:\texample.com \t\r\nX!#$%&'*+-.^_`|~: \t\x80\xff\t \r\n\r\n";
        assert!(parse_http_connect_request(request, &resolver).is_ok());
    }

    #[test]
    fn parse_http_connect_request_rejects_content_framing() {
        for fields in [
            "Content-Length: xyz\r\n",
            "Content-Length: 1\r\n",
            "Content-Length: 1\r\nContent-Length: 2\r\n",
            "Content-Length: 0\r\nContent-Length: 2\r\n",
            "Transfer-Encoding: gzip\r\n",
            "Transfer-Encoding: chunked\r\n",
            "Transfer-Encoding: chunked\r\nContent-Length: 0\r\n",
            "Transfer-Encoding:\r\n",
        ] {
            for version in ["HTTP/1.0", "HTTP/1.1"] {
                let request = format!("CONNECT example.com:443 {version}\r\nHost: example.com\r\n{fields}\r\n");
                assert_eq!(
                    HttpConnectHead::parse(request.as_bytes()).err().map(|error| error.code),
                    Some(crate::S_ER_GEN),
                    "head {version}: {fields:?}"
                );
                assert_eq!(
                    parse_http_connect_request(request.as_bytes(), &resolver).err().map(|error| error.code),
                    Some(crate::S_ER_GEN),
                    "{version}: {fields:?}"
                );
            }
        }
    }

    #[test]
    fn parse_http_connect_request_accepts_explicit_zero_content_length() {
        let request = b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\nContent-Length: 0\r\n\r\n";
        assert!(matches!(
            parse_http_connect_request(request, &resolver),
            Ok(ClientRequest::HttpConnect(TargetAddr { addr, .. })) if addr == SocketAddr::from(([198, 51, 100, 10], 443))
        ));
    }

    #[test]
    fn parse_http_connect_request_accepts_opaque_field_octets() {
        let request = b"CONNECT example.com:8443 HTTP/1.1\r\nX-Opaque: \x80\xff\r\nHost: example.com\r\n\r\n";
        let parsed = parse_http_connect_request(request, &resolver).expect("parse opaque field value");
        assert_eq!(
            parsed,
            ClientRequest::HttpConnect(TargetAddr {
                addr: SocketAddr::from(([198, 51, 100, 10], 8443)),
                host: Some("example.com".to_string()),
            })
        );
    }

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
    fn rejects_invalid_names_even_on_unrelated_fields() {
        for field in ["X-Test : value", "X-Test\t: value", ": empty name", "X/Test: value", "no-colon"] {
            let request = format!("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n{field}\r\n\r\n");
            let result = parse_http_connect_request(request.as_bytes(), &resolver);
            assert_eq!(result.err().map(|error| error.code), Some(crate::S_ER_GEN), "{field}");
        }
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
        assert_eq!(result.err().map(|err| err.code), Some(S_ER_CMD));
    }

    #[test]
    fn parse_http_connect_request_no_host_header() {
        let request = b"CONNECT example.com:443 HTTP/1.1\r\n\r\n";
        let result = parse_http_connect_request(request, &resolver);
        assert_eq!(result.err().map(|err| err.code), Some(crate::S_ER_GEN));
    }

    #[test]
    fn parse_http_connect_request_unresolvable_host() {
        let request = b"CONNECT unknown.invalid:443 HTTP/1.1\r\nHost: unknown.invalid:443\r\n\r\n";
        let result = parse_http_connect_request(request, &resolver);
        assert_eq!(result.err().map(|err| err.code), Some(S_ER_HOST));
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
