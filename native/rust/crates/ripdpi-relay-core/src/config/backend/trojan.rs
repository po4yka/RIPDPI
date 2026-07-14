#[derive(Clone, Default)]
pub struct TrojanRelayConfig {
    pub password: Option<String>,
    pub root_certificate_pem: Option<String>,
}

impl_redacted_debug!(TrojanRelayConfig {});
