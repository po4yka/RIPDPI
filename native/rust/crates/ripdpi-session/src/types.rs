use std::net::SocketAddr;

pub const S_AUTH_NONE: u8 = 0x00;
pub const S_AUTH_BAD: u8 = 0xff;
pub const S_AUTH_USERPASS: u8 = 0x02;

pub const S_ATP_I4: u8 = 0x01;
pub const S_ATP_ID: u8 = 0x03;
pub const S_ATP_I6: u8 = 0x04;

pub const S_CMD_CONN: u8 = 0x01;
pub const S_CMD_BIND: u8 = 0x02;
pub const S_CMD_AUDP: u8 = 0x03;

pub const S_ER_OK: u8 = 0x00;
pub const S_ER_GEN: u8 = 0x01;
pub const S_ER_DENY: u8 = 0x02;
pub const S_ER_NET: u8 = 0x03;
pub const S_ER_HOST: u8 = 0x04;
pub const S_ER_CONN: u8 = 0x05;
pub const S_ER_TTL: u8 = 0x06;
pub const S_ER_CMD: u8 = 0x07;
pub const S_ER_ATP: u8 = 0x08;

pub const S4_OK: u8 = 0x5a;
pub const S4_ER: u8 = 0x5b;

pub const S_VER5: u8 = 0x05;
pub const S_VER4: u8 = 0x04;

pub const S_SIZE_MIN: usize = 8;
pub const S_SIZE_I4: usize = 10;
pub const S_SIZE_I6: usize = 22;
pub const S_SIZE_ID: usize = 7;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SocketType {
    Stream,
    Datagram,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TargetAddr {
    pub addr: SocketAddr,
    pub host: Option<String>,
}

impl TargetAddr {
    pub fn family(&self) -> &'static str {
        match self.addr {
            SocketAddr::V4(_) => "ipv4",
            SocketAddr::V6(_) => "ipv6",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ClientRequest {
    Socks4Connect(TargetAddr),
    Socks5Connect(TargetAddr),
    Socks5UdpAssociate(TargetAddr),
    HttpConnect(TargetAddr),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProxyReply {
    Socks4(Vec<u8>),
    Socks5(Vec<u8>),
    Http(Vec<u8>),
}

impl ProxyReply {
    pub fn as_bytes(&self) -> &[u8] {
        match self {
            Self::Socks4(bytes) | Self::Socks5(bytes) | Self::Http(bytes) => bytes,
        }
    }
}

pub trait NameResolver {
    fn resolve(&self, host: &str, socket_type: SocketType) -> Option<SocketAddr>;
}

impl<F> NameResolver for F
where
    F: Fn(&str, SocketType) -> Option<SocketAddr>,
{
    fn resolve(&self, host: &str, socket_type: SocketType) -> Option<SocketAddr> {
        self(host, socket_type)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SessionConfig {
    pub resolve: bool,
    pub ipv6: bool,
}

impl Default for SessionConfig {
    fn default() -> Self {
        Self { resolve: true, ipv6: true }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionError {
    pub code: u8,
}

impl SessionError {
    pub(crate) fn socks5(code: u8) -> Self {
        Self { code }
    }

    pub(crate) fn generic() -> Self {
        Self { code: S_ER_GEN }
    }
}

pub(crate) fn read_be_u16(data: &[u8], offset: usize) -> Option<u16> {
    Some(u16::from_be_bytes([*data.get(offset)?, *data.get(offset + 1)?]))
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv6Addr};

    use super::*;

    #[test]
    fn target_addr_family_v4() {
        let target = TargetAddr { addr: SocketAddr::from(([1, 2, 3, 4], 80)), host: None };
        assert_eq!(target.family(), "ipv4");
    }

    #[test]
    fn target_addr_family_v6() {
        let target = TargetAddr { addr: SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443), host: None };
        assert_eq!(target.family(), "ipv6");
    }

    #[test]
    fn session_config_default_enables_resolve_and_ipv6() {
        let config = SessionConfig::default();
        assert!(config.resolve);
        assert!(config.ipv6);
    }

    #[test]
    fn proxy_reply_as_bytes_returns_inner_vec() {
        let socks4 = ProxyReply::Socks4(vec![1, 2, 3]);
        assert_eq!(socks4.as_bytes(), &[1, 2, 3]);

        let socks5 = ProxyReply::Socks5(vec![4, 5]);
        assert_eq!(socks5.as_bytes(), &[4, 5]);

        let http = ProxyReply::Http(vec![6]);
        assert_eq!(http.as_bytes(), &[6]);
    }

    #[test]
    fn read_be_u16_valid() {
        assert_eq!(read_be_u16(&[0x01, 0xbb], 0), Some(443));
    }

    #[test]
    fn read_be_u16_out_of_bounds() {
        assert_eq!(read_be_u16(&[0x01], 0), None);
        assert_eq!(read_be_u16(&[0x01, 0x02], 1), None);
        assert_eq!(read_be_u16(&[], 0), None);
    }

    #[test]
    fn closure_implements_name_resolver() {
        let r = |_host: &str, _st: SocketType| -> Option<SocketAddr> { Some(SocketAddr::from(([1, 1, 1, 1], 53))) };
        let result = r.resolve("anything", SocketType::Stream);
        assert_eq!(result, Some(SocketAddr::from(([1, 1, 1, 1], 53))));
    }

    #[test]
    fn session_error_socks5_carries_code() {
        let err = SessionError::socks5(S_ER_HOST);
        assert_eq!(err.code, S_ER_HOST);
    }

    #[test]
    fn session_error_generic_is_gen_code() {
        let err = SessionError::generic();
        assert_eq!(err.code, S_ER_GEN);
    }

    #[test]
    fn socket_type_equality() {
        assert_eq!(SocketType::Stream, SocketType::Stream);
        assert_ne!(SocketType::Stream, SocketType::Datagram);
    }

    #[test]
    fn client_request_variants_are_distinct() {
        let addr = TargetAddr { addr: SocketAddr::from(([1, 2, 3, 4], 80)), host: None };
        let s4 = ClientRequest::Socks4Connect(addr.clone());
        let s5 = ClientRequest::Socks5Connect(addr.clone());
        let udp = ClientRequest::Socks5UdpAssociate(addr.clone());
        let http = ClientRequest::HttpConnect(addr);
        assert_ne!(s4, s5);
        assert_ne!(s5, udp);
        assert_ne!(udp, http);
    }
}
