use serde::{Deserialize, Serialize};

/// `Debug` is implemented manually to redact subscriber credentials
/// (`uuid`, `password`). A derived `Debug` would expose them to any
/// `tracing::debug!(?config)` call or panic message.
#[derive(Clone, Deserialize, Serialize)]
pub struct Config {
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub uuid: String,
    pub password: String,
    pub zero_rtt: bool,
    pub congestion_control: String,
    pub udp_enabled: bool,
    pub quic_bind_low_port: bool,
    pub quic_migrate_after_handshake: bool,
}

impl std::fmt::Debug for Config {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Config")
            .field("server", &self.server)
            .field("server_port", &self.server_port)
            .field("server_name", &self.server_name)
            .field("uuid", &"<redacted>")
            .field("password", &"<redacted>")
            .field("zero_rtt", &self.zero_rtt)
            .field("congestion_control", &self.congestion_control)
            .field("udp_enabled", &self.udp_enabled)
            .field("quic_bind_low_port", &self.quic_bind_low_port)
            .field("quic_migrate_after_handshake", &self.quic_migrate_after_handshake)
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_config() -> Config {
        Config {
            server: "example.com".to_owned(),
            server_port: 443,
            server_name: "www.example.com".to_owned(),
            uuid: "550e8400-e29b-41d4-a716-446655440000".to_owned(),
            password: "hunter2-secret-password".to_owned(),
            zero_rtt: false,
            congestion_control: "bbr".to_owned(),
            udp_enabled: true,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: true,
        }
    }

    #[test]
    fn redacted_debug_omits_uuid_and_password() {
        let cfg = sample_config();
        let dbg = format!("{cfg:?}");
        assert!(!dbg.contains("550e8400-e29b-41d4-a716-446655440000"), "Debug output exposes UUID: {dbg}",);
        assert!(!dbg.contains("hunter2-secret-password"), "Debug output exposes password: {dbg}",);
        assert!(dbg.contains("<redacted>"), "redaction marker should be present");
        assert!(dbg.contains("example.com"), "server should remain visible");
    }
}
