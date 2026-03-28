#![forbid(unsafe_code)]

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use ripdpi_packets::{is_http_redirect, is_tls_client_hello, is_tls_server_hello, tls_session_id_mismatch};

/// Maximum number of ClientHello bytes to store for session-ID mismatch detection.
const CLIENT_HELLO_PREFIX_CAP: usize = 76;

pub const S_AUTH_NONE: u8 = 0x00;
pub const S_AUTH_BAD: u8 = 0xff;

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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TargetAddr {
    pub addr: SocketAddr,
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionPhase {
    Handshake,
    Connected,
    Closed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TriggerEvent {
    Redirect,
    SslErr,
    Connect,
    Torst,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OutboundProgress {
    pub round: u32,
    pub payload_size: usize,
    pub stream_start: usize,
    pub stream_end: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionState {
    pub phase: SessionPhase,
    pub round_count: u32,
    pub recv_count: usize,
    pub sent_this_round: usize,
    pub outbound_bytes_total: usize,
    pub saw_tls_client_hello: bool,
    client_hello_prefix: Vec<u8>,
}

impl Default for SessionState {
    fn default() -> Self {
        Self {
            phase: SessionPhase::Handshake,
            round_count: 0,
            recv_count: 0,
            sent_this_round: 0,
            outbound_bytes_total: 0,
            saw_tls_client_hello: false,
            client_hello_prefix: Vec::new(),
        }
    }
}

impl SessionState {
    pub fn observe_outbound(&mut self, payload: &[u8]) -> OutboundProgress {
        if self.sent_this_round == 0 {
            self.round_count += 1;
        }
        let stream_start = self.outbound_bytes_total;
        self.sent_this_round += payload.len();
        self.outbound_bytes_total = self.outbound_bytes_total.saturating_add(payload.len());
        if is_tls_client_hello(payload) {
            self.saw_tls_client_hello = true;
            let cap = payload.len().min(CLIENT_HELLO_PREFIX_CAP);
            self.client_hello_prefix.clear();
            self.client_hello_prefix.extend_from_slice(&payload[..cap]);
        }
        OutboundProgress {
            round: self.round_count,
            payload_size: payload.len(),
            stream_start,
            stream_end: stream_start.saturating_add(payload.len().saturating_sub(1)),
        }
    }

    pub fn observe_datagram_outbound(&mut self, payload: &[u8]) -> OutboundProgress {
        self.round_count += 1;
        self.sent_this_round = payload.len();
        let stream_start = self.outbound_bytes_total;
        self.outbound_bytes_total = self.outbound_bytes_total.saturating_add(payload.len());
        OutboundProgress {
            round: self.round_count,
            payload_size: payload.len(),
            stream_start,
            stream_end: stream_start.saturating_add(payload.len().saturating_sub(1)),
        }
    }

    pub fn observe_inbound(&mut self, payload: &[u8]) {
        self.recv_count += payload.len();
        self.sent_this_round = 0;
        if self.saw_tls_client_hello
            && (!is_tls_server_hello(payload) || tls_session_id_mismatch(&self.client_hello_prefix, payload))
        {
            self.saw_tls_client_hello = false;
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
    fn socks5(code: u8) -> Self {
        Self { code }
    }

    fn generic() -> Self {
        Self { code: S_ER_GEN }
    }
}

fn read_be_u16(data: &[u8], offset: usize) -> Option<u16> {
    Some(u16::from_be_bytes([*data.get(offset)?, *data.get(offset + 1)?]))
}

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
            .map(|addr| TargetAddr { addr: SocketAddr::new(addr.ip(), port) })
            .ok_or_else(SessionError::generic)?
    } else {
        TargetAddr { addr: SocketAddr::new(IpAddr::V4(ip), port) }
    };
    Ok(ClientRequest::Socks4Connect(target))
}

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
            let ip = Ipv4Addr::new(buffer[4], buffer[5], buffer[6], buffer[7]);
            (TargetAddr { addr: SocketAddr::new(IpAddr::V4(ip), 0) }, S_SIZE_I4)
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
            (TargetAddr { addr: SocketAddr::new(resolved.ip(), 0) }, offset)
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
            (TargetAddr { addr: SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), 0) }, S_SIZE_I6)
        }
        _ => return Err(SessionError::socks5(S_ER_GEN)),
    };
    let port = read_be_u16(buffer, offset - 2).ok_or_else(|| SessionError::socks5(S_ER_GEN))?;
    let target = TargetAddr { addr: SocketAddr::new(target.addr.ip(), port) };
    match buffer[1] {
        S_CMD_CONN => Ok(ClientRequest::Socks5Connect(target)),
        S_CMD_AUDP if socket_type == SocketType::Datagram => Ok(ClientRequest::Socks5UdpAssociate(target)),
        S_CMD_AUDP => Ok(ClientRequest::Socks5UdpAssociate(target)),
        _ => Err(SessionError::socks5(S_ER_CMD)),
    }
}

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
    Ok(ClientRequest::HttpConnect(TargetAddr { addr }))
}

fn split_host_port(value: &str) -> Option<(&str, u16)> {
    let (host, port) = value.rsplit_once(':')?;
    let port = port.parse::<u16>().ok()?;
    Some((host.trim_matches(|ch| ch == '[' || ch == ']'), port))
}

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

pub fn detect_response_trigger(request: &[u8], response: &[u8]) -> Option<TriggerEvent> {
    if is_http_redirect(request, response) {
        return Some(TriggerEvent::Redirect);
    }
    if (is_tls_client_hello(request) && !is_tls_server_hello(response)) || tls_session_id_mismatch(request, response) {
        return Some(TriggerEvent::SslErr);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn resolver(host: &str, socket_type: SocketType) -> Option<SocketAddr> {
        match (host, socket_type) {
            ("example.com", SocketType::Stream) => Some(SocketAddr::from(([198, 51, 100, 10], 0))),
            ("example.net", SocketType::Datagram) => Some(SocketAddr::from(([198, 51, 100, 20], 0))),
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
            ClientRequest::Socks4Connect(TargetAddr { addr: SocketAddr::from(([198, 51, 100, 10], 443)) })
        );
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
            ClientRequest::Socks5UdpAssociate(TargetAddr { addr: SocketAddr::from(([198, 51, 100, 20], 8080)) })
        );
    }

    #[test]
    fn parse_http_connect_request_uses_host_header() {
        let request = b"CONNECT ignored HTTP/1.1\r\nHost: example.com:8443\r\n\r\n";
        let parsed = parse_http_connect_request(request, &resolver).expect("parse connect");

        assert_eq!(
            parsed,
            ClientRequest::HttpConnect(TargetAddr { addr: SocketAddr::from(([198, 51, 100, 10], 8443)) })
        );
    }

    #[test]
    fn encode_socks5_reply_encodes_address_and_port() {
        let reply = encode_socks5_reply(S_ER_OK, SocketAddr::from(([127, 0, 0, 1], 1080)));

        assert_eq!(reply.as_bytes(), &[S_VER5, S_ER_OK, 0, S_ATP_I4, 127, 0, 0, 1, 0x04, 0x38]);
    }

    #[test]
    fn detect_response_trigger_tls_to_non_tls_is_ssl_err() {
        let tls_hello = ripdpi_packets::DEFAULT_FAKE_TLS;
        let non_tls = b"HTTP/1.1 200 OK\r\n\r\n";
        assert_eq!(detect_response_trigger(tls_hello, non_tls), Some(TriggerEvent::SslErr));
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
    fn split_host_port_ipv6_bracket_stripping() {
        assert_eq!(split_host_port("[::1]:443"), Some(("::1", 443)));
    }

    #[test]
    fn split_host_port_missing_port() {
        assert_eq!(split_host_port("example.com"), None);
    }

    #[test]
    fn session_state_tls_flag_cleared_on_non_server_hello() {
        let mut state = SessionState::default();
        state.observe_outbound(ripdpi_packets::DEFAULT_FAKE_TLS);
        assert!(state.saw_tls_client_hello);
        state.observe_inbound(b"not tls at all");
        assert!(!state.saw_tls_client_hello);
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
        assert_eq!(parsed, ClientRequest::Socks5Connect(TargetAddr { addr: SocketAddr::new(expected_ip, 443) }));
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
    fn session_state_tracks_rounds_and_resets_after_inbound() {
        let mut state = SessionState::default();

        let first = state.observe_outbound(b"hello");
        let second = state.observe_outbound(b"world");
        assert_eq!(state.round_count, 1);
        assert_eq!(state.sent_this_round, 10);
        assert_eq!(first.stream_start, 0);
        assert_eq!(first.stream_end, 4);
        assert_eq!(second.stream_start, 5);
        assert_eq!(second.stream_end, 9);

        state.observe_inbound(b"reply");
        assert_eq!(state.recv_count, 5);
        assert_eq!(state.sent_this_round, 0);

        let third = state.observe_outbound(b"next");
        assert_eq!(state.round_count, 2);
        assert_eq!(third.stream_start, 10);
        assert_eq!(third.stream_end, 13);
    }

    #[test]
    fn session_state_tracks_udp_datagram_progress_separately() {
        let mut state = SessionState::default();

        let first = state.observe_datagram_outbound(b"hello");
        let second = state.observe_datagram_outbound(b"world!");

        assert_eq!(first.round, 1);
        assert_eq!(first.stream_start, 0);
        assert_eq!(first.stream_end, 4);
        assert_eq!(second.round, 2);
        assert_eq!(second.stream_start, 5);
        assert_eq!(second.stream_end, 10);
        assert_eq!(state.outbound_bytes_total, 11);
    }

    // --- SessionState default and lifecycle ---

    #[test]
    fn session_state_default_values() {
        let state = SessionState::default();
        assert_eq!(state.phase, SessionPhase::Handshake);
        assert_eq!(state.round_count, 0);
        assert_eq!(state.recv_count, 0);
        assert_eq!(state.sent_this_round, 0);
        assert_eq!(state.outbound_bytes_total, 0);
        assert!(!state.saw_tls_client_hello);
    }

    #[test]
    fn session_state_observe_outbound_empty_payload() {
        let mut state = SessionState::default();
        let progress = state.observe_outbound(b"");
        // Empty payload still increments round (sent_this_round was 0)
        assert_eq!(state.round_count, 1);
        assert_eq!(state.sent_this_round, 0);
        assert_eq!(progress.payload_size, 0);
        assert_eq!(progress.stream_start, 0);
        // stream_end: 0 + 0.saturating_sub(1) = 0
        assert_eq!(progress.stream_end, 0);
    }

    #[test]
    fn session_state_client_hello_prefix_capped() {
        let mut state = SessionState::default();
        // DEFAULT_FAKE_TLS is a valid ClientHello and longer than CLIENT_HELLO_PREFIX_CAP (76)
        let tls = ripdpi_packets::DEFAULT_FAKE_TLS;
        assert!(tls.len() > CLIENT_HELLO_PREFIX_CAP);
        state.observe_outbound(tls);
        assert!(state.saw_tls_client_hello);
        assert_eq!(state.client_hello_prefix.len(), CLIENT_HELLO_PREFIX_CAP);
        assert_eq!(&state.client_hello_prefix[..], &tls[..CLIENT_HELLO_PREFIX_CAP]);
    }

    #[test]
    fn session_state_multiple_inbound_accumulates_recv_count() {
        let mut state = SessionState::default();
        state.observe_inbound(b"first");
        state.observe_inbound(b"second");
        assert_eq!(state.recv_count, 11); // 5 + 6
    }

    #[test]
    fn session_state_non_tls_outbound_does_not_set_tls_flag() {
        let mut state = SessionState::default();
        state.observe_outbound(b"GET / HTTP/1.1\r\n\r\n");
        assert!(!state.saw_tls_client_hello);
    }

    #[test]
    fn session_state_datagram_increments_round_every_call() {
        let mut state = SessionState::default();
        state.observe_datagram_outbound(b"a");
        state.observe_datagram_outbound(b"b");
        state.observe_datagram_outbound(b"c");
        assert_eq!(state.round_count, 3);
        // Each call resets sent_this_round to the payload len
        assert_eq!(state.sent_this_round, 1);
    }

    // --- TargetAddr ---

    #[test]
    fn target_addr_family_v4() {
        let target = TargetAddr { addr: SocketAddr::from(([1, 2, 3, 4], 80)) };
        assert_eq!(target.family(), "ipv4");
    }

    #[test]
    fn target_addr_family_v6() {
        let target = TargetAddr { addr: SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443) };
        assert_eq!(target.family(), "ipv6");
    }

    // --- SessionConfig ---

    #[test]
    fn session_config_default_enables_resolve_and_ipv6() {
        let config = SessionConfig::default();
        assert!(config.resolve);
        assert!(config.ipv6);
    }

    // --- ProxyReply ---

    #[test]
    fn proxy_reply_as_bytes_returns_inner_vec() {
        let socks4 = ProxyReply::Socks4(vec![1, 2, 3]);
        assert_eq!(socks4.as_bytes(), &[1, 2, 3]);

        let socks5 = ProxyReply::Socks5(vec![4, 5]);
        assert_eq!(socks5.as_bytes(), &[4, 5]);

        let http = ProxyReply::Http(vec![6]);
        assert_eq!(http.as_bytes(), &[6]);
    }

    // --- parse_socks4_request error cases ---

    #[test]
    fn parse_socks4_request_too_short() {
        let result = parse_socks4_request(&[0x04, 0x01, 0, 80, 1, 2, 3], SessionConfig::default(), &resolver);
        assert!(result.is_err());
    }

    #[test]
    fn parse_socks4_request_bad_command() {
        // S_CMD_BIND instead of S_CMD_CONN
        let request = vec![S_VER4, S_CMD_BIND, 0x00, 0x50, 1, 2, 3, 4, 0];
        let result = parse_socks4_request(&request, SessionConfig::default(), &resolver);
        assert!(result.is_err());
    }

    #[test]
    fn parse_socks4_request_direct_ipv4() {
        // IP > 0.0.0.255 means direct IP, no domain resolution
        let request = vec![S_VER4, S_CMD_CONN, 0x00, 0x50, 10, 0, 0, 1, 0];
        let parsed = parse_socks4_request(&request, SessionConfig::default(), &resolver).expect("direct ip");
        assert_eq!(
            parsed,
            ClientRequest::Socks4Connect(TargetAddr { addr: SocketAddr::from(([10, 0, 0, 1], 80)) })
        );
    }

    #[test]
    fn parse_socks4_request_domain_resolve_disabled() {
        // SOCKS4a domain request with resolve disabled should fail
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
        // Domain less than 3 chars should fail
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
        // SOCKS4a domain request without final null byte
        let mut request = vec![S_VER4, S_CMD_CONN, 0x01, 0xbb, 0, 0, 0, 1];
        request.extend_from_slice(b"user");
        request.push(0);
        request.extend_from_slice(b"example.com");
        // Missing trailing null -- last byte != 0

        let result = parse_socks4_request(&request, SessionConfig::default(), &resolver);
        assert!(result.is_err());
    }

    // --- parse_socks5_request error cases ---

    #[test]
    fn parse_socks5_request_too_short() {
        let result = parse_socks5_request(&[0x05, 0x01, 0, 0x01], SocketType::Stream, SessionConfig::default(), &resolver);
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
            ClientRequest::Socks5Connect(TargetAddr { addr: SocketAddr::from(([192, 168, 1, 1], 443)) })
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
        let request = vec![S_VER5, S_CMD_CONN, 0, 0xFF, 0, 0, 0, 0, 0, 0];
        let result = parse_socks5_request(&request, SocketType::Stream, SessionConfig::default(), &resolver);
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().code, S_ER_GEN);
    }

    #[test]
    fn parse_socks5_request_unsupported_command() {
        // S_CMD_BIND (0x02) is not handled
        let mut request = vec![S_VER5, S_CMD_BIND, 0, S_ATP_I4, 1, 2, 3, 4];
        request.extend_from_slice(&80u16.to_be_bytes());

        let result = parse_socks5_request(&request, SocketType::Stream, SessionConfig::default(), &resolver);
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().code, S_ER_CMD);
    }

    #[test]
    fn parse_socks5_request_udp_associate_with_stream_socket() {
        // UDP associate is allowed even with Stream socket type
        let mut request = vec![S_VER5, S_CMD_AUDP, 0, S_ATP_I4, 0, 0, 0, 0];
        request.extend_from_slice(&0u16.to_be_bytes());

        let parsed = parse_socks5_request(&request, SocketType::Stream, SessionConfig::default(), &resolver)
            .expect("udp assoc with stream");
        assert!(matches!(parsed, ClientRequest::Socks5UdpAssociate(_)));
    }

    // --- parse_http_connect_request error cases ---

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

    // --- encode_socks5_reply IPv6 ---

    #[test]
    fn encode_socks5_reply_ipv6_address() {
        let addr = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 8080);
        let reply = encode_socks5_reply(S_ER_OK, addr);
        let bytes = reply.as_bytes();
        assert_eq!(bytes[0], S_VER5);
        assert_eq!(bytes[1], S_ER_OK);
        assert_eq!(bytes[2], 0);
        assert_eq!(bytes[3], S_ATP_I6);
        // 16 bytes of IPv6 address
        let mut expected_ip = [0u8; 16];
        expected_ip[15] = 1; // ::1
        assert_eq!(&bytes[4..20], &expected_ip);
        // Port in big-endian
        assert_eq!(&bytes[20..22], &8080u16.to_be_bytes());
    }

    // --- encode_socks4_reply structure ---

    #[test]
    fn encode_socks4_reply_is_8_bytes() {
        let reply = encode_socks4_reply(true);
        assert_eq!(reply.as_bytes().len(), 8);
        assert_eq!(reply.as_bytes()[0], 0);
    }

    // --- encode_http_connect_reply structure ---

    #[test]
    fn encode_http_connect_reply_ends_with_double_crlf() {
        let success = encode_http_connect_reply(true);
        assert!(success.as_bytes().ends_with(b"\r\n\r\n"));
        let failure = encode_http_connect_reply(false);
        assert!(failure.as_bytes().ends_with(b"\r\n\r\n"));
    }

    // --- detect_response_trigger ---

    #[test]
    fn detect_response_trigger_returns_none_for_non_tls_non_redirect() {
        let request = b"GET / HTTP/1.1\r\n\r\n";
        let response = b"HTTP/1.1 200 OK\r\n\r\n";
        assert_eq!(detect_response_trigger(request, response), None);
    }

    // --- split_host_port ---

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

    // --- read_be_u16 ---

    #[test]
    fn read_be_u16_valid() {
        assert_eq!(read_be_u16(&[0x01, 0xBB], 0), Some(443));
    }

    #[test]
    fn read_be_u16_out_of_bounds() {
        assert_eq!(read_be_u16(&[0x01], 0), None);
        assert_eq!(read_be_u16(&[0x01, 0x02], 1), None);
        assert_eq!(read_be_u16(&[], 0), None);
    }

    // --- NameResolver blanket impl ---

    #[test]
    fn closure_implements_name_resolver() {
        let r = |_host: &str, _st: SocketType| -> Option<SocketAddr> { Some(SocketAddr::from(([1, 1, 1, 1], 53))) };
        let result = r.resolve("anything", SocketType::Stream);
        assert_eq!(result, Some(SocketAddr::from(([1, 1, 1, 1], 53))));
    }

    // --- SessionError ---

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

    // --- Enum variant equality ---

    #[test]
    fn session_phase_equality() {
        assert_eq!(SessionPhase::Handshake, SessionPhase::Handshake);
        assert_ne!(SessionPhase::Handshake, SessionPhase::Connected);
        assert_ne!(SessionPhase::Connected, SessionPhase::Closed);
    }

    #[test]
    fn socket_type_equality() {
        assert_eq!(SocketType::Stream, SocketType::Stream);
        assert_ne!(SocketType::Stream, SocketType::Datagram);
    }

    #[test]
    fn trigger_event_equality() {
        assert_eq!(TriggerEvent::Redirect, TriggerEvent::Redirect);
        assert_ne!(TriggerEvent::Redirect, TriggerEvent::SslErr);
        assert_ne!(TriggerEvent::Connect, TriggerEvent::Torst);
    }

    // --- ClientRequest variant coverage ---

    #[test]
    fn client_request_variants_are_distinct() {
        let addr = TargetAddr { addr: SocketAddr::from(([1, 2, 3, 4], 80)) };
        let s4 = ClientRequest::Socks4Connect(addr);
        let s5 = ClientRequest::Socks5Connect(addr);
        let udp = ClientRequest::Socks5UdpAssociate(addr);
        let http = ClientRequest::HttpConnect(addr);
        assert_ne!(s4, s5);
        assert_ne!(s5, udp);
        assert_ne!(udp, http);
    }
}
