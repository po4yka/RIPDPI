#[derive(Clone, Default)]
pub struct Hysteria2RelayConfig {
    pub password: Option<String>,
    pub salamander_key: Option<String>,
    /// Skip TLS certificate verification (`insecure=1` on the share link).
    pub insecure: bool,
}

impl_redacted_debug!(Hysteria2RelayConfig { insecure });
