use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn proxy_protocol_mode(&self) -> RuntimeProxyProtocolMode {
        match self.handshake_settings.protocol_mode {
            ProxyProtocolMode::Transparent => RuntimeProxyProtocolMode::Transparent,
            ProxyProtocolMode::HttpConnect => RuntimeProxyProtocolMode::HttpConnect,
            ProxyProtocolMode::Mixed { shadowsocks_enabled } => RuntimeProxyProtocolMode::Mixed { shadowsocks_enabled },
            ProxyProtocolMode::BytePrefixed { shadowsocks_enabled } => {
                RuntimeProxyProtocolMode::BytePrefixed { shadowsocks_enabled }
            }
        }
    }
    pub(in crate::runtime) fn proxy_auth_token(&self) -> Option<&str> {
        self.handshake_settings.auth_token.as_deref()
    }
    pub(in crate::runtime) fn udp_associate_enabled(&self) -> bool {
        self.handshake_settings.udp_associate_enabled
    }
    pub(in crate::runtime) fn handshake_protect_path(&self) -> Option<String> {
        self.handshake_settings.protect_path.clone()
    }
    pub(in crate::runtime) fn delayed_connect_enabled(&self) -> bool {
        self.delayed_connect_settings.enabled
    }
    pub(in crate::runtime) fn delayed_connect_buffer_size(&self) -> usize {
        self.delayed_connect_settings.buffer_size
    }
    pub(in crate::runtime) fn encode_upstream_socks_connect(target: SocketAddr) -> Vec<u8> {
        encode_upstream_socks_connect(target)
    }
    pub(in crate::runtime) fn read_upstream_socks_reply(reader: &mut impl Read) -> io::Result<Vec<u8>> {
        read_upstream_socks_reply(reader)
    }
    pub(in crate::runtime) fn upstream_socks_auth_request() -> [u8; 3] {
        [S_VER5, 1, S_AUTH_NONE]
    }
    pub(in crate::runtime) fn upstream_socks_auth_accepted(reply: [u8; 2]) -> bool {
        reply == [S_VER5, S_AUTH_NONE]
    }
    pub(in crate::runtime) fn upstream_socks_connect_succeeded(reply: &[u8]) -> bool {
        reply.first().copied() == Some(S_VER5) && reply.get(1).copied().unwrap_or(S_ER_GEN) == 0
    }
    /// Maps a connect failure [`io::ErrorKind`] to the RFC 1928 §6 SOCKS5 reply
    /// (`REP`) code, defaulting to general failure for unclassified kinds.
    pub(in crate::runtime) fn socks5_reply_code_for_kind(kind: io::ErrorKind) -> u8 {
        match kind {
            io::ErrorKind::ConnectionRefused => S_ER_CONN,
            io::ErrorKind::HostUnreachable => S_ER_HOST,
            io::ErrorKind::NetworkUnreachable => S_ER_NET,
            io::ErrorKind::TimedOut => S_ER_TTL,
            _ => S_ER_GEN,
        }
    }
    pub(in crate::runtime) fn socks5_auth_selection(auth_token: Option<&str>, methods: &[u8]) -> ([u8; 2], bool) {
        let method = if auth_token.is_some() {
            if methods.contains(&S_AUTH_USERPASS) { S_AUTH_USERPASS } else { S_AUTH_BAD }
        } else if methods.contains(&S_AUTH_NONE) {
            S_AUTH_NONE
        } else {
            S_AUTH_BAD
        };
        ([S_VER5, method], method != S_AUTH_BAD)
    }
    pub(in crate::runtime) fn is_socks5_version(version: u8) -> bool {
        version == S_VER5
    }
    pub(in crate::runtime) fn socks5_command_unsupported_code() -> u8 {
        S_ER_CMD
    }
    pub(in crate::runtime) fn socks5_general_failure_code() -> u8 {
        S_ER_GEN
    }
    pub(in crate::runtime) fn socks5_fixed_address_tail_len(address_type: u8) -> Option<usize> {
        match address_type {
            S_ATP_I4 => Some(6),
            S_ATP_I6 => Some(18),
            _ => None,
        }
    }
    pub(in crate::runtime) fn is_socks5_domain_address_type(address_type: u8) -> bool {
        address_type == 0x03
    }
    pub(in crate::runtime) fn is_socks5_resolved_domain_address_type(address_type: u8) -> bool {
        // RIPDPI-private local TUN -> proxy address type; see ripdpi-session.
        address_type == 0x05
    }
    pub(in crate::runtime) fn resolved_domain_targets_allowed(&self) -> bool {
        self.listener_settings.bind_addr.ip().is_loopback()
            && self.listener_settings.bind_addr.port() == 0
            && self.handshake_settings.auth_token.is_some()
    }
    pub(in crate::runtime) fn encode_socks4_reply(granted: bool) -> ProxyReply {
        encode_socks4_reply(granted)
    }
    pub(in crate::runtime) fn encode_socks5_reply(code: u8, addr: SocketAddr) -> ProxyReply {
        encode_socks5_reply(code, addr)
    }
    pub(in crate::runtime) fn encode_http_connect_reply(success: bool) -> ProxyReply {
        encode_http_connect_reply(success)
    }
    pub(in crate::runtime) fn resolve_proxy_name(&self, host: &str, _socket_type: SocketType) -> Option<SocketAddr> {
        use std::net::IpAddr;

        if let Ok(ip) = host.parse::<IpAddr>() {
            return Some(SocketAddr::new(ip, 0));
        }

        let session_config = self.handshake_settings.session_config;
        if let Some(loopback) = resolve_localhost(host, session_config.ipv6) {
            return Some(loopback);
        }
        if !session_config.resolve {
            return None;
        }

        let protect_path = self.handshake_protect_path();
        self.resolve_encrypted_dns_host(host, protect_path.as_deref(), session_config.ipv6).ok()
    }
    pub(in crate::runtime) fn resolve_handshake_name(&self, host: &str) -> Option<SocketAddr> {
        self.resolve_proxy_name(host, SocketType::Stream)
    }
    pub(in crate::runtime) fn parse_socks4_client_request(
        &self,
        request: &[u8],
        resolve_name: impl Fn(&str) -> Option<SocketAddr>,
    ) -> Result<RuntimeClientRequest, RuntimeSessionError> {
        let resolver = |host: &str, socket_type: SocketType| {
            let _ = socket_type;
            resolve_name(host)
        };
        parse_socks4_request(request, self.handshake_settings.session_config, &resolver)
            .map(runtime_client_request)
            .map_err(runtime_session_error)
    }
    pub(in crate::runtime) fn parse_socks5_client_request(
        &self,
        request: &[u8],
        resolve_name: impl Fn(&str) -> Option<SocketAddr>,
    ) -> Result<RuntimeClientRequest, RuntimeSessionError> {
        let resolver = |host: &str, socket_type: SocketType| {
            let _ = socket_type;
            resolve_name(host)
        };
        parse_socks5_request(request, SocketType::Stream, self.handshake_settings.session_config, &resolver)
            .map(runtime_client_request)
            .map_err(runtime_session_error)
    }
    pub(in crate::runtime) fn parse_http_connect_client_request(
        request: &[u8],
        resolve_name: impl Fn(&str) -> Option<SocketAddr>,
    ) -> Result<RuntimeClientRequest, RuntimeSessionError> {
        let resolver = |host: &str, socket_type: SocketType| {
            let _ = socket_type;
            resolve_name(host)
        };
        parse_http_connect_request(request, &resolver).map(runtime_client_request).map_err(runtime_session_error)
    }
    pub(in crate::runtime) fn validate_http_proxy_auth(request: &[u8], token: &str) -> bool {
        validate_http_proxy_auth(request, token)
    }
    pub(in crate::runtime) fn parse_shadowsocks_target(
        &self,
        request: &[u8],
        mut resolve_name: impl FnMut(&str) -> Option<SocketAddr>,
    ) -> Option<(TargetAddr, usize)> {
        parse_shadowsocks_target(request, self.handshake_settings.shadowsocks_target_policy, |host, socket_type| {
            let _ = socket_type;
            resolve_name(host)
        })
    }
}

fn resolve_localhost(host: &str, ipv6_enabled: bool) -> Option<SocketAddr> {
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

    if !host.eq_ignore_ascii_case("localhost") && !host.eq_ignore_ascii_case("localhost.") {
        return None;
    }

    let ip = if ipv6_enabled { IpAddr::V6(Ipv6Addr::LOCALHOST) } else { IpAddr::V4(Ipv4Addr::LOCALHOST) };
    Some(SocketAddr::new(ip, 0))
}
