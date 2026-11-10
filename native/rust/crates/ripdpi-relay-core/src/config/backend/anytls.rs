#[derive(Clone, Default)]
pub struct AnyTlsRelayConfig {
    pub password: Option<String>,
    pub root_certificate_pem: Option<String>,
    pub certificate_pin_sha256: Option<String>,
}

impl_redacted_debug!(AnyTlsRelayConfig {});
