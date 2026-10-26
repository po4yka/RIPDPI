use std::io;
use std::net::SocketAddr;

use crate::types::{ProxyReply, S_ATP_I4, S_ATP_I6, S_ER_CMD, S_ER_HOST, S_VER5, S4_ER, S4_OK};

pub fn encode_socks4_reply(success: bool) -> ProxyReply {
    ProxyReply::Socks4(vec![0, if success { S4_OK } else { S4_ER }, 0, 0, 0, 0, 0, 0])
}

pub fn encode_socks5_reply(code: u8, addr: SocketAddr) -> ProxyReply {
    let mut out = vec![S_VER5, code, 0];
    match addr {
        SocketAddr::V4(addr) => {
            out.push(S_ATP_I4);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            out.push(S_ATP_I6);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    ProxyReply::Socks5(out)
}

pub fn encode_http_connect_reply(success: bool) -> ProxyReply {
    if success {
        ProxyReply::Http(b"HTTP/1.1 200 OK\r\n\r\n".to_vec())
    } else {
        encode_http_connect_failure(HttpConnectFailure::BadGateway)
    }
}

/// Why an HTTP CONNECT request was not tunnelled (RFC 9110 sections 15.5 and 15.6).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HttpConnectFailure {
    /// A malformed request line, a missing, duplicate or mismatched Host, or an invalid port.
    BadRequest,
    /// A method other than CONNECT.
    MethodNotAllowed,
    /// The target name did not resolve, or the upstream connection failed.
    BadGateway,
    /// The upstream connection timed out.
    GatewayTimeout,
}

impl HttpConnectFailure {
    /// Maps a [`crate::SessionError`] code from `parse_http_connect_request`.
    pub fn for_request_error(code: u8) -> Self {
        match code {
            S_ER_CMD => Self::MethodNotAllowed,
            S_ER_HOST => Self::BadGateway,
            _ => Self::BadRequest,
        }
    }

    /// Maps an upstream connect error.
    pub fn for_connect_error(kind: io::ErrorKind) -> Self {
        if kind == io::ErrorKind::TimedOut { Self::GatewayTimeout } else { Self::BadGateway }
    }
}

pub fn encode_http_connect_failure(failure: HttpConnectFailure) -> ProxyReply {
    let status = match failure {
        HttpConnectFailure::BadRequest => "400 Bad Request",
        HttpConnectFailure::MethodNotAllowed => "405 Method Not Allowed\r\nAllow: CONNECT",
        HttpConnectFailure::BadGateway => "502 Bad Gateway",
        HttpConnectFailure::GatewayTimeout => "504 Gateway Timeout",
    };
    ProxyReply::Http(format!("HTTP/1.1 {status}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").into_bytes())
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv6Addr, SocketAddr};

    use crate::{S_ATP_I4, S_ATP_I6, S_ER_OK, S_VER5, S4_ER, S4_OK};

    use super::*;

    #[test]
    fn encode_socks5_reply_encodes_address_and_port() {
        let reply = encode_socks5_reply(S_ER_OK, SocketAddr::from(([127, 0, 0, 1], 1080)));

        assert_eq!(reply.as_bytes(), &[S_VER5, S_ER_OK, 0, S_ATP_I4, 127, 0, 0, 1, 0x04, 0x38]);
    }

    #[test]
    fn encode_socks4_reply_success_vs_failure() {
        let success = encode_socks4_reply(true);
        assert_eq!(success.as_bytes()[1], S4_OK);
        let failure = encode_socks4_reply(false);
        assert_eq!(failure.as_bytes()[1], S4_ER);
    }

    #[test]
    fn encode_http_connect_reply_success_vs_failure() {
        let success = encode_http_connect_reply(true);
        assert!(success.as_bytes().windows(6).any(|w| w == b"200 OK"));
        let failure = encode_http_connect_reply(false);
        assert!(failure.as_bytes().starts_with(b"HTTP/1.1 502 Bad Gateway\r\n"));
    }

    #[test]
    fn http_connect_failures_carry_their_status_and_close_delimit_nothing() {
        let cases = [
            (HttpConnectFailure::for_request_error(crate::S_ER_GEN), "HTTP/1.1 400 Bad Request\r\n"),
            (HttpConnectFailure::for_request_error(S_ER_CMD), "HTTP/1.1 405 Method Not Allowed\r\nAllow: CONNECT\r\n"),
            (HttpConnectFailure::for_request_error(S_ER_HOST), "HTTP/1.1 502 Bad Gateway\r\n"),
            (HttpConnectFailure::for_connect_error(io::ErrorKind::ConnectionRefused), "HTTP/1.1 502 Bad Gateway\r\n"),
            (HttpConnectFailure::for_connect_error(io::ErrorKind::TimedOut), "HTTP/1.1 504 Gateway Timeout\r\n"),
        ];
        for (failure, head) in cases {
            let reply = String::from_utf8(encode_http_connect_failure(failure).as_bytes().to_vec()).expect("ascii");
            assert!(reply.starts_with(head), "{failure:?}: {reply:?}");
            assert!(reply.ends_with("\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"), "{failure:?}: {reply:?}");
        }
    }

    #[test]
    fn encode_socks5_reply_ipv6_address() {
        let addr = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 8080);
        let reply = encode_socks5_reply(S_ER_OK, addr);
        let bytes = reply.as_bytes();
        assert_eq!(bytes[0], S_VER5);
        assert_eq!(bytes[1], S_ER_OK);
        assert_eq!(bytes[2], 0);
        assert_eq!(bytes[3], S_ATP_I6);
        let mut expected_ip = [0u8; 16];
        expected_ip[15] = 1;
        assert_eq!(&bytes[4..20], &expected_ip);
        assert_eq!(&bytes[20..22], &8080u16.to_be_bytes());
    }

    #[test]
    fn encode_socks4_reply_is_8_bytes() {
        let reply = encode_socks4_reply(true);
        assert_eq!(reply.as_bytes().len(), 8);
        assert_eq!(reply.as_bytes()[0], 0);
    }

    #[test]
    fn encode_http_connect_reply_ends_with_double_crlf() {
        let success = encode_http_connect_reply(true);
        assert!(success.as_bytes().ends_with(b"\r\n\r\n"));
        let failure = encode_http_connect_reply(false);
        assert!(failure.as_bytes().ends_with(b"\r\n\r\n"));
    }
}
