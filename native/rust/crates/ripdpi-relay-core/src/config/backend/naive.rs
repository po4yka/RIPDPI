#[derive(Debug, Clone, Default)]
pub struct NaiveProxyRelayConfig {
    pub path: String,
    pub username: Option<String>,
    pub password: Option<String>,
}
