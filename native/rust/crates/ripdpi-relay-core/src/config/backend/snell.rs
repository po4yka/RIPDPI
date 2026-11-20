#[derive(Clone, Default)]
pub struct SnellRelayConfig {
    pub psk: Option<String>,
    pub user_key: Option<String>,
}

impl_redacted_debug!(SnellRelayConfig {});
