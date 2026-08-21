#[derive(Clone, Default)]
pub struct MieruRelayConfig {
    pub server: String,
    pub port: i32,
    pub username: Option<String>,
    pub password: Option<String>,
    pub protocol: String,
    pub multiplexing: String,
    pub mtu: i32,
}

impl_redacted_debug!(MieruRelayConfig { server, port, protocol, multiplexing, mtu });
