use std::net::SocketAddr;

/// Upstream SOCKS5 server a desync group egresses through.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpstreamSocksConfig {
    pub addr: SocketAddr,
    /// RFC 1929 credentials the upstream listener requires. `None` offers NO-AUTH only.
    pub auth: Option<UpstreamSocksAuth>,
}

/// RFC 1929 username/password for an app-owned loopback SOCKS5 listener.
#[derive(Clone, PartialEq, Eq)]
pub struct UpstreamSocksAuth {
    pub username: String,
    pub password: String,
}

impl std::fmt::Debug for UpstreamSocksAuth {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("UpstreamSocksAuth(<redacted>)")
    }
}
