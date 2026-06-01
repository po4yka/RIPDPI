use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use crate::types::{
    ClientRequest, NameResolver, S_CMD_CONN, SessionConfig, SessionError, SocketType, TargetAddr, read_be_u16,
};

pub fn parse_socks4_request(
    buffer: &[u8],
    config: SessionConfig,
    resolver: &dyn NameResolver,
) -> Result<ClientRequest, SessionError> {
    if buffer.len() < 9 {
        return Err(SessionError::generic());
    }
    if buffer[1] != S_CMD_CONN {
        return Err(SessionError::generic());
    }
    let port = read_be_u16(buffer, 2).ok_or_else(SessionError::generic)?;
    let ip = Ipv4Addr::new(buffer[4], buffer[5], buffer[6], buffer[7]);

    let target = if u32::from(ip) <= 255 {
        if !config.resolve || *buffer.last().unwrap_or(&1) != 0 {
            return Err(SessionError::generic());
        }
        let id_end =
            buffer[8..].iter().position(|&byte| byte == 0).map(|pos| pos + 8).ok_or_else(SessionError::generic)?;
        let domain_start = id_end + 1;
        if domain_start >= buffer.len() {
            return Err(SessionError::generic());
        }
        let domain_end = buffer[domain_start..]
            .iter()
            .position(|&byte| byte == 0)
            .map(|pos| pos + domain_start)
            .ok_or_else(SessionError::generic)?;
        let len = domain_end.saturating_sub(domain_start);
        if !(3..=255).contains(&len) {
            return Err(SessionError::generic());
        }
        let domain = std::str::from_utf8(&buffer[domain_start..domain_end]).map_err(|_| SessionError::generic())?;
        resolver
            .resolve(domain, SocketType::Stream)
            .map(|addr| TargetAddr { addr: SocketAddr::new(addr.ip(), port), host: Some(domain.to_string()) })
            .ok_or_else(SessionError::generic)?
    } else {
        TargetAddr { addr: SocketAddr::new(IpAddr::V4(ip), port), host: None }
    };
    Ok(ClientRequest::Socks4Connect(target))
}

#[cfg(test)]
mod tests {
    use std::net::SocketAddr;

    use crate::{ClientRequest, S_CMD_BIND, S_CMD_CONN, S_VER4, SessionConfig, SocketType, TargetAddr};

    use super::*;

    fn resolver(host: &str, socket_type: SocketType) -> Option<SocketAddr> {
        match (host, socket_type) {
            ("example.com", SocketType::Stream) => Some(SocketAddr::from(([198, 51, 100, 10], 0))),
            _ => None,
        }
    }

    #[test]
    fn parse_socks4_request_resolves_domain_targets() {
        let mut request = vec![S_VER4, S_CMD_CONN, 0x01, 0xbb, 0, 0, 0, 1];
        request.extend_from_slice(b"user");
        request.push(0);
        request.extend_from_slice(b"example.com");
        request.push(0);

        let parsed = parse_socks4_request(&request, SessionConfig::default(), &resolver).expect("parse socks4");

        assert_eq!(
            parsed,
            ClientRequest::Socks4Connect(TargetAddr {
                addr: SocketAddr::from(([198, 51, 100, 10], 443)),
                host: Some("example.com".to_string()),
            })
        );
    }

    #[test]
    fn parse_socks4_request_too_short() {
        let result = parse_socks4_request(&[0x04, 0x01, 0, 80, 1, 2, 3], SessionConfig::default(), &resolver);
        assert!(result.is_err());
    }

    #[test]
    fn parse_socks4_request_bad_command() {
        let request = vec![S_VER4, S_CMD_BIND, 0x00, 0x50, 1, 2, 3, 4, 0];
        let result = parse_socks4_request(&request, SessionConfig::default(), &resolver);
        assert!(result.is_err());
    }

    #[test]
    fn parse_socks4_request_direct_ipv4() {
        let request = vec![S_VER4, S_CMD_CONN, 0x00, 0x50, 10, 0, 0, 1, 0];
        let parsed = parse_socks4_request(&request, SessionConfig::default(), &resolver).expect("direct ip");
        assert_eq!(
            parsed,
            ClientRequest::Socks4Connect(TargetAddr { addr: SocketAddr::from(([10, 0, 0, 1], 80)), host: None })
        );
    }

    #[test]
    fn parse_socks4_request_domain_resolve_disabled() {
        let mut request = vec![S_VER4, S_CMD_CONN, 0x01, 0xbb, 0, 0, 0, 1];
        request.extend_from_slice(b"user");
        request.push(0);
        request.extend_from_slice(b"example.com");
        request.push(0);

        let config = SessionConfig { resolve: false, ipv6: true };
        let result = parse_socks4_request(&request, config, &resolver);
        assert!(result.is_err());
    }

    #[test]
    fn parse_socks4_request_unresolvable_domain() {
        let mut request = vec![S_VER4, S_CMD_CONN, 0x01, 0xbb, 0, 0, 0, 1];
        request.extend_from_slice(b"user");
        request.push(0);
        request.extend_from_slice(b"unknown.invalid");
        request.push(0);

        let result = parse_socks4_request(&request, SessionConfig::default(), &resolver);
        assert!(result.is_err());
    }

    #[test]
    fn parse_socks4_request_domain_too_short() {
        let mut request = vec![S_VER4, S_CMD_CONN, 0x01, 0xbb, 0, 0, 0, 1];
        request.extend_from_slice(b"user");
        request.push(0);
        request.extend_from_slice(b"ab");
        request.push(0);

        let result = parse_socks4_request(&request, SessionConfig::default(), &resolver);
        assert!(result.is_err());
    }

    #[test]
    fn parse_socks4_request_no_trailing_null() {
        let mut request = vec![S_VER4, S_CMD_CONN, 0x01, 0xbb, 0, 0, 0, 1];
        request.extend_from_slice(b"user");
        request.push(0);
        request.extend_from_slice(b"example.com");

        let result = parse_socks4_request(&request, SessionConfig::default(), &resolver);
        assert!(result.is_err());
    }
}
