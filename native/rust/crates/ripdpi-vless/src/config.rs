use base64::prelude::*;

use crate::addons::{FlowParseError, VlessFlow};
use crate::mux::{MuxConfigError, VlessMuxConfig};

/// Errors that can occur when parsing VLESS+Reality configuration from strings.
#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("invalid UUID: {0}")]
    InvalidUuid(String),
    #[error("invalid port: {0}")]
    InvalidPort(i32),
    #[error("invalid reality public key (base64): {0}")]
    InvalidPublicKey(String),
    #[error("invalid reality short ID (hex): {0}")]
    InvalidShortId(String),
    #[error("invalid mux config: {0}")]
    InvalidMux(#[from] MuxConfigError),
    #[error("invalid flow: {0}")]
    InvalidFlow(#[from] FlowParseError),
}

/// Configuration for a VLESS+Reality connection.
///
/// `Debug` is implemented manually to redact subscriber credentials
/// (`uuid`, `reality_public_key`). A derived `Debug` would expose them
/// to any `tracing::debug!(?config)` call or panic message. See
/// `redacted_debug_omits_uuid_and_reality_key` test for the contract.
#[derive(Clone)]
pub struct VlessRealityConfig {
    pub server: String,
    pub port: u16,
    /// Parsed 16-byte UUID.
    pub uuid: [u8; 16],
    pub server_name: String,
    pub tls_fingerprint_profile: String,
    /// Decoded 32-byte X25519 public key.
    pub reality_public_key: [u8; 32],
    /// Decoded short ID (0-8 bytes).
    pub reality_short_id: Vec<u8>,
    /// Wire-multiplexing config when the subscription requested `mux:
    /// sing-mux` / `mux: yamux`; `None` for one-connection-per-flow.
    pub mux: Option<VlessMuxConfig>,
    /// Selected VLESS flow. Defaults to [`VlessFlow::Vision`] for
    /// historical back-compatibility; the audit's "Vision is
    /// unconditional" finding is addressed by exposing this as a
    /// per-profile choice rather than hardcoding the addons block at
    /// the call site.
    pub flow: VlessFlow,
}

impl std::fmt::Debug for VlessRealityConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("VlessRealityConfig")
            .field("server", &self.server)
            .field("port", &self.port)
            .field("uuid", &"<redacted>")
            .field("server_name", &self.server_name)
            .field("tls_fingerprint_profile", &self.tls_fingerprint_profile)
            .field("reality_public_key", &"<redacted>")
            .field("reality_short_id_len", &self.reality_short_id.len())
            .field("mux", &self.mux)
            .field("flow", &self.flow)
            .finish()
    }
}

impl VlessRealityConfig {
    /// Build a config from string representations (as passed from the Kotlin layer).
    pub fn from_strings(
        server: &str,
        port: i32,
        uuid_str: &str,
        server_name: &str,
        reality_public_key_b64: &str,
        reality_short_id_hex: &str,
        tls_fingerprint_profile: &str,
    ) -> Result<Self, ConfigError> {
        let port = u16::try_from(port).map_err(|_| ConfigError::InvalidPort(port))?;

        let uuid = parse_uuid(uuid_str).map_err(|_| ConfigError::InvalidUuid(uuid_str.to_owned()))?;

        let pub_key_bytes = BASE64_STANDARD
            .decode(reality_public_key_b64)
            .map_err(|_| ConfigError::InvalidPublicKey(reality_public_key_b64.to_owned()))?;
        if pub_key_bytes.len() != 32 {
            return Err(ConfigError::InvalidPublicKey(format!("expected 32 bytes, got {}", pub_key_bytes.len())));
        }
        let mut reality_public_key = [0u8; 32];
        reality_public_key.copy_from_slice(&pub_key_bytes);

        let reality_short_id = if reality_short_id_hex.is_empty() {
            Vec::new()
        } else {
            hex::decode(reality_short_id_hex)
                .map_err(|_| ConfigError::InvalidShortId(reality_short_id_hex.to_owned()))?
        };
        if reality_short_id.len() > 8 {
            return Err(ConfigError::InvalidShortId(format!("expected 0-8 bytes, got {}", reality_short_id.len())));
        }

        Ok(Self {
            server: server.to_owned(),
            port,
            uuid,
            server_name: server_name.to_owned(),
            tls_fingerprint_profile: tls_fingerprint_profile.to_owned(),
            reality_public_key,
            reality_short_id,
            mux: None,
            flow: VlessFlow::default(),
        })
    }

    /// Replace the flow selection on this config.
    pub fn with_flow(mut self, flow: VlessFlow) -> Self {
        self.flow = flow;
        self
    }

    /// Parse a flow string and attach it. Accepts the xray identifiers
    /// `xtls-rprx-vision`, `xtls-rprx-vision-udp443`, and the empty
    /// string / `"none"` / `"off"` for no flow.
    pub fn with_flow_str(self, flow: &str) -> Result<Self, ConfigError> {
        let parsed = VlessFlow::parse(flow)?;
        Ok(self.with_flow(parsed))
    }

    /// Attach a wire-multiplexing config (builder style). NekoBox / sing-box
    /// subscriptions that carry a `mux` block produce a [`VlessMuxConfig`]
    /// which is threaded onto the profile here.
    pub fn with_mux(mut self, mux: VlessMuxConfig) -> Self {
        self.mux = Some(mux);
        self
    }

    /// Parse a `mux` block from its string fields and attach it to this
    /// config. The field semantics match [`VlessMuxConfig::from_strings`]:
    /// `0` for the numeric fields means "use the default".
    pub fn with_mux_strings(
        self,
        protocol: &str,
        max_concurrent_streams: u32,
        per_connection_kbps: u32,
        sing_mux_padding_max: u32,
    ) -> Result<Self, ConfigError> {
        let mux =
            VlessMuxConfig::from_strings(protocol, max_concurrent_streams, per_connection_kbps, sing_mux_padding_max)?;
        Ok(self.with_mux(mux))
    }
}

/// Parse a UUID string (with or without dashes) into 16 bytes.
fn parse_uuid(s: &str) -> Result<[u8; 16], ()> {
    let hex_only: String = s.chars().filter(|c| *c != '-').collect();
    if hex_only.len() != 32 {
        return Err(());
    }
    let bytes = hex::decode(&hex_only).map_err(|_| ())?;
    let mut uuid = [0u8; 16];
    uuid.copy_from_slice(&bytes);
    Ok(uuid)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_uuid_with_dashes() {
        let uuid = parse_uuid("550e8400-e29b-41d4-a716-446655440000").unwrap();
        assert_eq!(uuid[0], 0x55);
        assert_eq!(uuid[15], 0x00);
    }

    #[test]
    fn parse_uuid_without_dashes() {
        let uuid = parse_uuid("550e8400e29b41d4a716446655440000").unwrap();
        assert_eq!(uuid[0], 0x55);
    }

    #[test]
    fn from_strings_valid() {
        // 32-byte key encoded as base64
        let key = BASE64_STANDARD.encode([0xABu8; 32]);
        let cfg = VlessRealityConfig::from_strings(
            "example.com",
            443,
            "550e8400-e29b-41d4-a716-446655440000",
            "www.example.com",
            &key,
            "abcd1234",
            "chrome_stable",
        )
        .unwrap();
        assert_eq!(cfg.port, 443);
        assert_eq!(cfg.reality_short_id.len(), 4);
        // A plain `from_strings` profile carries no mux block by default.
        assert!(cfg.mux.is_none());
    }

    fn sample_config() -> VlessRealityConfig {
        let key = BASE64_STANDARD.encode([0xABu8; 32]);
        VlessRealityConfig::from_strings(
            "example.com",
            443,
            "550e8400-e29b-41d4-a716-446655440000",
            "www.example.com",
            &key,
            "abcd1234",
            "chrome_stable",
        )
        .expect("valid base config")
    }

    #[test]
    fn with_mux_strings_attaches_a_sing_mux_block() {
        let cfg = sample_config().with_mux_strings("sing-mux", 16, 4096, 256).expect("valid mux block");
        let mux = cfg.mux.expect("mux block present");
        assert_eq!(mux.protocol, crate::mux::VlessMuxProtocol::SingMux);
        assert_eq!(mux.max_concurrent_streams, 16);
        assert_eq!(mux.per_connection_kbps, Some(4096));
        assert_eq!(mux.sing_mux_padding_max, Some(256));
    }

    #[test]
    fn with_mux_strings_attaches_a_yamux_block() {
        let cfg = sample_config().with_mux_strings("yamux", 0, 0, 0).expect("valid mux block");
        let mux = cfg.mux.expect("mux block present");
        assert_eq!(mux.protocol, crate::mux::VlessMuxProtocol::Yamux);
    }

    #[test]
    fn with_mux_strings_rejects_unknown_protocol() {
        let err = sample_config().with_mux_strings("not-a-mux", 0, 0, 0).expect_err("unknown mux must be rejected");
        assert!(matches!(err, ConfigError::InvalidMux(_)));
    }

    #[test]
    fn from_strings_defaults_flow_to_vision_for_back_compat() {
        let cfg = sample_config();
        // Historical callers that don't supply a flow continue to get
        // VISION_ADDONS on the wire — the audit's C3 finding is closed
        // by making the *choice* configurable, not by silently changing
        // every existing client's behavior.
        assert_eq!(cfg.flow, VlessFlow::Vision);
    }

    #[test]
    fn with_flow_replaces_default_selection() {
        let cfg = sample_config().with_flow(VlessFlow::None);
        assert_eq!(cfg.flow, VlessFlow::None);
        let cfg = sample_config().with_flow(VlessFlow::VisionUdp443);
        assert_eq!(cfg.flow, VlessFlow::VisionUdp443);
    }

    #[test]
    fn with_flow_str_parses_xray_identifiers() {
        let cfg = sample_config().with_flow_str("").expect("empty flow accepted");
        assert_eq!(cfg.flow, VlessFlow::None);
        let cfg = sample_config().with_flow_str("xtls-rprx-vision-udp443").expect("udp443 flow accepted");
        assert_eq!(cfg.flow, VlessFlow::VisionUdp443);
    }

    #[test]
    fn with_flow_str_rejects_unknown_flow() {
        let err = sample_config().with_flow_str("xtls-rprx-direct").expect_err("unknown flow rejected");
        assert!(matches!(err, ConfigError::InvalidFlow(_)), "got {err:?}");
    }

    #[test]
    fn with_mux_builder_threads_a_config_through() {
        let mux = VlessMuxConfig::new(crate::mux::VlessMuxProtocol::SingMux);
        let cfg = sample_config().with_mux(mux);
        assert_eq!(cfg.mux, Some(mux));
    }

    #[test]
    fn redacted_debug_omits_uuid_and_reality_key() {
        // Credential-redaction contract: the manual Debug impl on
        // VlessRealityConfig must not echo the UUID or REALITY public key
        // bytes. Removing the manual impl (e.g. derive Debug) would expose
        // both to tracing::debug!(?cfg) and panic messages.
        let cfg = sample_config();

        let dbg = format!("{cfg:?}");

        // UUID bytes in hex (with or without dashes) must not appear.
        let uuid_hex_no_dashes = "550e8400e29b41d4a716446655440000";
        let uuid_hex_dashed = "550e8400-e29b-41d4-a716-446655440000";
        assert!(
            !dbg.contains(uuid_hex_no_dashes) && !dbg.contains(uuid_hex_dashed),
            "Debug output exposes UUID: {dbg}",
        );

        // REALITY public-key bytes (32 × 0xAB == "AB" repeated) must not
        // appear as a hex run.
        let reality_key_hex_run = "AB".repeat(32);
        assert!(!dbg.contains(&reality_key_hex_run), "Debug output exposes REALITY public key: {dbg}",);
        // Also reject the array-of-int form `[171, 171, ...]` that a
        // derived Debug would emit.
        assert!(!dbg.contains("171, 171, 171, 171"), "Debug output exposes REALITY public key as int array: {dbg}",);

        // Public, non-secret fields should still be visible for log
        // utility.
        assert!(dbg.contains("example.com"), "server should be visible in debug");
        assert!(dbg.contains("443"), "port should be visible in debug");
        assert!(dbg.contains("<redacted>"), "redaction marker should be present");
    }

    /// Tracing-event-capture variant of the Debug-redaction contract.
    ///
    /// The Debug impl protects `tracing::debug!(?config, ...)` and
    /// `tracing::error!(?config, "...")` calls — the most common way
    /// secrets leak into logs. This test installs a temporary
    /// subscriber that captures every event field as a string,
    /// triggers a representative tracing call with `?config`, and
    /// asserts the captured event does not echo the UUID or REALITY
    /// public-key bytes.
    ///
    /// Pairs with the connect-path tracing sites in `lib.rs`
    /// (`VLESS+Reality: connecting`, `VLESS handshake completed`)
    /// and the Reality handshake site in `reality.rs`. Closes the
    /// tracing-event-capture criterion on
    /// `add-credential-redaction-tests-for-vless-uuid-tuic-uuid-naive-auth`.
    #[test]
    fn tracing_event_with_config_field_does_not_echo_uuid_or_key() {
        use std::fmt;
        use std::sync::{Arc, Mutex};
        use tracing::{Event, Subscriber};
        use tracing_subscriber::layer::{Context, Layer, SubscriberExt};
        use tracing_subscriber::Registry;

        /// Capture every event's field-set into a shared Vec of strings.
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
                let mut store = self.0.lock().expect("capture mutex");
                store.push(rendered);
            }
        }

        let captured = Arc::new(Mutex::new(Vec::<String>::new()));
        let layer = CaptureLayer(Arc::clone(&captured));
        let subscriber = Registry::default().with(layer);

        let cfg = sample_config();
        tracing::subscriber::with_default(subscriber, || {
            // Fires the exact ?config pattern used at the real
            // tracing sites; if the Debug impl ever regresses,
            // this assertion catches the leak.
            tracing::debug!(config = ?cfg, "VLESS test event");
        });

        let events = captured.lock().expect("capture mutex");
        let joined = events.join("\n");
        let uuid_hex_no_dashes = "550e8400e29b41d4a716446655440000";
        let uuid_hex_dashed = "550e8400-e29b-41d4-a716-446655440000";
        assert!(
            !joined.contains(uuid_hex_no_dashes) && !joined.contains(uuid_hex_dashed),
            "tracing event exposes UUID: {joined}",
        );
        let reality_key_hex_run = "AB".repeat(32);
        assert!(
            !joined.contains(&reality_key_hex_run),
            "tracing event exposes REALITY public key (hex): {joined}",
        );
        assert!(
            !joined.contains("171, 171, 171, 171"),
            "tracing event exposes REALITY public key (int array): {joined}",
        );
        assert!(joined.contains("<redacted>"), "tracing event must carry the redaction marker: {joined}");
    }
}
