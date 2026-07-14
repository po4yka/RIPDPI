#[derive(Clone, Default)]
pub struct NaiveProxyRelayConfig {
    pub path: String,
    pub username: Option<String>,
    pub password: Option<String>,
}

impl_redacted_debug!(NaiveProxyRelayConfig {});
