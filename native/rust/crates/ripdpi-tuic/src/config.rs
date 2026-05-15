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
    /// QUIC application-level keepalive interval in milliseconds.
    ///
    /// `0` disables keepalive. Mobile NATs aggressively reclaim UDP
    /// bindings (often <30s), so a non-zero default is recommended on
    /// any production profile. Carried through to
    /// `quinn::TransportConfig::keep_alive_interval`.
    ///
    /// `#[serde(default)]` keeps profiles written before this field
    /// existed deserializable; the implicit default is `0` (disabled).
    /// Set explicitly to ~15000 ms to survive a 60s NAT silence
    /// window without re-handshaking.
    #[serde(default)]
    pub keepalive_interval_ms: u32,
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
            .field("keepalive_interval_ms", &self.keepalive_interval_ms)
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
            keepalive_interval_ms: 15_000,
        }
    }

    #[test]
    fn legacy_config_without_keepalive_field_deserializes_with_zero() {
        // Profiles persisted before the keepalive_interval_ms field
        // existed must still load. `#[serde(default)]` must keep them
        // deserializable; the implicit default is `0` (disabled).
        let json = r#"{
            "server": "example.com",
            "server_port": 443,
            "server_name": "www.example.com",
            "uuid": "550e8400-e29b-41d4-a716-446655440000",
            "password": "x",
            "zero_rtt": false,
            "congestion_control": "bbr",
            "udp_enabled": true,
            "quic_bind_low_port": false,
            "quic_migrate_after_handshake": true
        }"#;
        let cfg: Config = serde_json::from_str(json).expect("legacy config should still parse");
        assert_eq!(cfg.keepalive_interval_ms, 0);
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
