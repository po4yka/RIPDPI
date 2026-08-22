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
    #[serde(skip, default)]
    pub socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    /// Local address the QUIC carrier socket is bound to (interface pinning).
    /// `None` binds to an unspecified address. Not part of the serialized
    /// profile: it is runtime routing state supplied by the relay builder,
    /// mirroring `socket_protection`. The resolved server address must match
    /// this IP's family or the connect fails closed.
    #[serde(skip, default)]
    pub outbound_bind_ip: Option<std::net::IpAddr>,
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
    /// Optional PEM trust anchor for the server's TLS certificate. When set, the
    /// certificate(s) are added to the QUIC client's root store (verification
    /// stays ON — this PINS the server cert, it does not disable verification),
    /// letting a self-signed / private-CA TUIC server be trusted. Mirrors
    /// `root_certificate_pem` on the Trojan / AnyTLS / MASQUE clients. `None`
    /// uses the system (webpki) roots. `#[serde(default)]` keeps older profiles
    /// deserializable.
    #[serde(default)]
    pub root_certificate_pem: Option<String>,
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
            .field("socket_protection", &self.socket_protection)
            .field("outbound_bind_ip", &self.outbound_bind_ip)
            .field("keepalive_interval_ms", &self.keepalive_interval_ms)
            .field("root_certificate_pem", &self.root_certificate_pem.as_ref().map(|_| "<pinned>"))
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
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
            keepalive_interval_ms: 15_000,
            root_certificate_pem: None,
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

    /// Install a temporary subscriber that captures every event's
    /// field-set into a single joined string, run `emit`, and return
    /// the captured render. Mirrors the CaptureLayer pattern in
    /// `ripdpi-vless/src/config.rs`
    /// (`tracing_event_with_config_field_does_not_echo_uuid_or_key`).
    fn capture_events(emit: impl FnOnce()) -> String {
        use std::fmt;
        use std::sync::{Arc, Mutex};

        use tracing::{Event, Subscriber};
        use tracing_subscriber::Registry;
        use tracing_subscriber::layer::{Context, Layer, SubscriberExt};

        struct CaptureLayer(Arc<Mutex<Vec<String>>>);

        impl<S: Subscriber> Layer<S> for CaptureLayer {
            fn on_event(&self, event: &Event<'_>, _ctx: Context<'_, S>) {
                struct Visitor<'a>(&'a mut String);
                impl<'a> tracing::field::Visit for Visitor<'a> {
                    fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn fmt::Debug) {
                        use fmt::Write;
                        let _ = write!(self.0, " {}={:?}", field.name(), value);
                    }
                }
                let mut rendered = String::new();
                event.record(&mut Visitor(&mut rendered));
                self.0.lock().expect("capture mutex").push(rendered);
            }
        }

        let captured = Arc::new(Mutex::new(Vec::<String>::new()));
        let subscriber = Registry::default().with(CaptureLayer(Arc::clone(&captured)));
        tracing::subscriber::with_default(subscriber, emit);
        captured.lock().expect("capture mutex").join("\n")
    }

    fn assert_no_credentials(joined: &str) {
        // UUID (dashed and the dashless hex form a derived Debug might
        // emit) and password must never appear in a captured event.
        assert!(
            !joined.contains("550e8400-e29b-41d4-a716-446655440000"),
            "tracing event exposes UUID (dashed): {joined}",
        );
        assert!(
            !joined.contains("550e8400e29b41d4a716446655440000"),
            "tracing event exposes UUID (no dashes): {joined}",
        );
        assert!(!joined.contains("hunter2-secret-password"), "tracing event exposes password: {joined}",);
        assert!(joined.contains("<redacted>"), "tracing event must carry the redaction marker: {joined}");
    }

    /// Happy-path tracing-event-capture variant of the redaction
    /// contract: a representative `tracing::debug!(config = ?cfg, ...)`
    /// at a connect site must not echo the UUID or password. Closes the
    /// tracing-event-capture criterion for ripdpi-tuic on
    /// `add-credential-redaction-tests-for-vless-uuid-tuic-uuid-naive-auth`.
    #[test]
    fn tracing_event_with_config_field_does_not_echo_uuid_or_password() {
        let cfg = sample_config();
        let joined = capture_events(|| {
            tracing::debug!(config = ?cfg, "TUIC connecting");
        });
        assert_no_credentials(&joined);
    }

    /// Error-path variant: the way a misconfig / connect failure would
    /// log the config (`tracing::error!(config = ?cfg, "...")`) must
    /// also be free of credentials. Removing the manual Debug impl
    /// (e.g. deriving it) fails this assertion.
    #[test]
    fn tracing_error_event_with_config_field_does_not_echo_uuid_or_password() {
        let cfg = sample_config();
        let joined = capture_events(|| {
            tracing::error!(config = ?cfg, error = "connection refused", "TUIC connect failed");
        });
        assert_no_credentials(&joined);
    }
}
