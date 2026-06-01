use std::net::SocketAddr;

use crate::types::{ProxyReply, S_ATP_I4, S_ATP_I6, S_VER5, S4_ER, S4_OK};

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
    let body = if success { b"HTTP/1.1 200 OK\r\n\r\n".to_vec() } else { b"HTTP/1.1 503 Fail\r\n\r\n".to_vec() };
    ProxyReply::Http(body)
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
        assert!(failure.as_bytes().windows(8).any(|w| w == b"503 Fail"));
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
