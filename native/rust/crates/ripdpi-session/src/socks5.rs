use std::net::{IpAddr, Ipv6Addr, SocketAddr};

use crate::types::{
    ClientRequest, NameResolver, S_ATP_I4, S_ATP_I6, S_ATP_ID, S_CMD_AUDP, S_CMD_CONN, S_ER_ATP, S_ER_CMD, S_ER_GEN,
    S_ER_HOST, S_SIZE_I4, S_SIZE_I6, S_SIZE_ID, S_SIZE_MIN, SessionConfig, SessionError, SocketType, TargetAddr,
    read_be_u16,
};

pub fn parse_socks5_request(
    buffer: &[u8],
    socket_type: SocketType,
    config: SessionConfig,
    resolver: &dyn NameResolver,
) -> Result<ClientRequest, SessionError> {
    if buffer.len() < S_SIZE_MIN {
        return Err(SessionError::socks5(S_ER_GEN));
    }
    let atyp = buffer[3];
    let (target, offset) = match atyp {
        S_ATP_I4 => {
            if buffer.len() < S_SIZE_I4 {
                return Err(SessionError::socks5(S_ER_GEN));
            }
            let ip = std::net::Ipv4Addr::new(buffer[4], buffer[5], buffer[6], buffer[7]);
            (TargetAddr { addr: SocketAddr::new(IpAddr::V4(ip), 0), host: None }, S_SIZE_I4)
        }
        S_ATP_ID => {
            let name_len = *buffer.get(4).ok_or_else(|| SessionError::socks5(S_ER_GEN))? as usize;
            let offset = name_len + S_SIZE_ID;
            if buffer.len() < offset {
                return Err(SessionError::socks5(S_ER_GEN));
            }
            if !config.resolve {
                return Err(SessionError::socks5(S_ER_ATP));
            }
            if name_len < 3 {
                return Err(SessionError::socks5(S_ER_HOST));
            }
            let domain = std::str::from_utf8(&buffer[5..5 + name_len]).map_err(|_| SessionError::socks5(S_ER_HOST))?;
            let resolved = resolver.resolve(domain, socket_type).ok_or_else(|| SessionError::socks5(S_ER_HOST))?;
            (TargetAddr { addr: SocketAddr::new(resolved.ip(), 0), host: Some(domain.to_string()) }, offset)
        }
        S_ATP_I6 => {
            if !config.ipv6 {
                return Err(SessionError::socks5(S_ER_ATP));
            }
            if buffer.len() < S_SIZE_I6 {
                return Err(SessionError::socks5(S_ER_GEN));
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&buffer[4..20]);
            (TargetAddr { addr: SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), 0), host: None }, S_SIZE_I6)
        }
        _ => return Err(SessionError::socks5(S_ER_GEN)),
    };
    let port = read_be_u16(buffer, offset - 2).ok_or_else(|| SessionError::socks5(S_ER_GEN))?;
    let target = TargetAddr { addr: SocketAddr::new(target.addr.ip(), port), host: target.host };
    match buffer[1] {
        S_CMD_CONN => Ok(ClientRequest::Socks5Connect(target)),
        S_CMD_AUDP if socket_type == SocketType::Datagram => Ok(ClientRequest::Socks5UdpAssociate(target)),
        S_CMD_AUDP => Ok(ClientRequest::Socks5UdpAssociate(target)),
        _ => Err(SessionError::socks5(S_ER_CMD)),
    }
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv6Addr, SocketAddr};

    use crate::{
        ClientRequest, S_ATP_I4, S_ATP_I6, S_ATP_ID, S_CMD_AUDP, S_CMD_BIND, S_CMD_CONN, S_ER_ATP, S_ER_CMD, S_ER_GEN,
        S_ER_HOST, S_VER5, SessionConfig, SocketType, TargetAddr,
    };

    use super::*;

    fn resolver(host: &str, socket_type: SocketType) -> Option<SocketAddr> {
        match (host, socket_type) {
            ("example.com", SocketType::Stream) => Some(SocketAddr::from(([198, 51, 100, 10], 0))),
            ("example.net", SocketType::Datagram) => Some(SocketAddr::from(([198, 51, 100, 20], 0))),
            _ => None,
        }
    }

    #[test]
    fn parse_socks5_request_resolves_domains_for_datagram_mode() {
        let mut request = vec![S_VER5, S_CMD_AUDP, 0, S_ATP_ID, 11];
        request.extend_from_slice(b"example.net");
        request.extend_from_slice(&8080u16.to_be_bytes());

        let parsed = parse_socks5_request(&request, SocketType::Datagram, SessionConfig::default(), &resolver)
            .expect("parse socks5");

        assert_eq!(
            parsed,
            ClientRequest::Socks5UdpAssociate(TargetAddr {
                addr: SocketAddr::from(([198, 51, 100, 20], 8080)),
                host: Some("example.net".to_string()),
            })
        );
    }

    #[test]
    fn parse_socks5_request_ipv6_address_type() {
        let mut request = vec![S_VER5, S_CMD_CONN, 0, S_ATP_I6];
        let mut ipv6_bytes = [0u8; 16];
        ipv6_bytes[15] = 1;
        request.extend_from_slice(&ipv6_bytes);
        request.extend_from_slice(&443u16.to_be_bytes());

        let parsed = parse_socks5_request(&request, SocketType::Stream, SessionConfig::default(), &resolver)
            .expect("parse socks5 ipv6");

        let expected_ip = IpAddr::V6(Ipv6Addr::new(0, 0, 0, 0, 0, 0, 0, 1));
        assert_eq!(
            parsed,
            ClientRequest::Socks5Connect(TargetAddr { addr: SocketAddr::new(expected_ip, 443), host: None })
        );
    }

    #[test]
    fn parse_socks5_request_ipv6_rejected_when_disabled() {
        let mut request = vec![S_VER5, S_CMD_CONN, 0, S_ATP_I6];
        let ipv6_bytes = [0u8; 16];
        request.extend_from_slice(&ipv6_bytes);
        request.extend_from_slice(&443u16.to_be_bytes());

        let config = SessionConfig { resolve: true, ipv6: false };
        let result = parse_socks5_request(&request, SocketType::Stream, config, &resolver);
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().code, S_ER_ATP);
    }

    #[test]
    fn parse_socks5_request_too_short() {
        let result =
            parse_socks5_request(&[0x05, 0x01, 0, 0x01], SocketType::Stream, SessionConfig::default(), &resolver);
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().code, S_ER_GEN);
    }

    #[test]
    fn parse_socks5_request_ipv4_connect() {
        let mut request = vec![S_VER5, S_CMD_CONN, 0, S_ATP_I4, 192, 168, 1, 1];
        request.extend_from_slice(&443u16.to_be_bytes());

        let parsed = parse_socks5_request(&request, SocketType::Stream, SessionConfig::default(), &resolver)
            .expect("parse socks5 ipv4");

        assert_eq!(
            parsed,
            ClientRequest::Socks5Connect(TargetAddr { addr: SocketAddr::from(([192, 168, 1, 1], 443)), host: None })
        );
    }

    #[test]
    fn parse_socks5_request_domain_resolve_disabled() {
        let mut request = vec![S_VER5, S_CMD_CONN, 0, S_ATP_ID, 11];
        request.extend_from_slice(b"example.com");
        request.extend_from_slice(&443u16.to_be_bytes());

        let config = SessionConfig { resolve: false, ipv6: true };
        let result = parse_socks5_request(&request, SocketType::Stream, config, &resolver);
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().code, S_ER_ATP);
    }

    #[test]
    fn parse_socks5_request_domain_too_short() {
        let mut request = vec![S_VER5, S_CMD_CONN, 0, S_ATP_ID, 2];
        request.extend_from_slice(b"ab");
        request.extend_from_slice(&443u16.to_be_bytes());

        let result = parse_socks5_request(&request, SocketType::Stream, SessionConfig::default(), &resolver);
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().code, S_ER_HOST);
    }

    #[test]
    fn parse_socks5_request_unresolvable_domain() {
        let domain = b"unknown.xyz";
        let mut request = vec![S_VER5, S_CMD_CONN, 0, S_ATP_ID, domain.len() as u8];
        request.extend_from_slice(domain);
        request.extend_from_slice(&443u16.to_be_bytes());

        let result = parse_socks5_request(&request, SocketType::Stream, SessionConfig::default(), &resolver);
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().code, S_ER_HOST);
    }

    #[test]
    fn parse_socks5_request_unknown_address_type() {
        let request = vec![S_VER5, S_CMD_CONN, 0, 0xff, 0, 0, 0, 0, 0, 0];
        let result = parse_socks5_request(&request, SocketType::Stream, SessionConfig::default(), &resolver);
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().code, S_ER_GEN);
    }

    #[test]
    fn parse_socks5_request_unsupported_command() {
        let mut request = vec![S_VER5, S_CMD_BIND, 0, S_ATP_I4, 1, 2, 3, 4];
        request.extend_from_slice(&80u16.to_be_bytes());

        let result = parse_socks5_request(&request, SocketType::Stream, SessionConfig::default(), &resolver);
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().code, S_ER_CMD);
    }

    #[test]
    fn parse_socks5_request_udp_associate_with_stream_socket() {
        let mut request = vec![S_VER5, S_CMD_AUDP, 0, S_ATP_I4, 0, 0, 0, 0];
        request.extend_from_slice(&0u16.to_be_bytes());

        let parsed = parse_socks5_request(&request, SocketType::Stream, SessionConfig::default(), &resolver)
            .expect("udp assoc with stream");
        assert!(matches!(parsed, ClientRequest::Socks5UdpAssociate(_)));
    }
}
