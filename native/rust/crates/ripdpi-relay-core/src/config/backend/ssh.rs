#[derive(Clone, Default)]
pub struct SshRelayConfig {
    pub host: String,
    pub port: i32,
    pub username: Option<String>,
    pub auth_type: String,
    pub password: Option<String>,
    pub private_key: Option<String>,
    pub private_key_passphrase: Option<String>,
    pub host_key_fingerprint: Option<String>,
    pub strict_host_key: bool,
}

impl_redacted_debug!(SshRelayConfig { host, port, auth_type, strict_host_key });
