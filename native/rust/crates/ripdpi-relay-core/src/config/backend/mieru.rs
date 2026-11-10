#[derive(Clone, Default)]
pub struct MieruRelayConfig {
    pub username: Option<String>,
    pub password: Option<String>,
    pub protocol: String,
    pub multiplexing: String,
    pub mtu: i32,
}

impl_redacted_debug!(MieruRelayConfig { protocol, multiplexing, mtu });
