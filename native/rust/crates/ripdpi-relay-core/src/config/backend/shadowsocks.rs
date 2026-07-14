#[derive(Clone, Default)]
pub struct ShadowsocksRelayConfig {
    pub method: String,
    pub password: Option<String>,
}

impl_redacted_debug!(ShadowsocksRelayConfig { method });
