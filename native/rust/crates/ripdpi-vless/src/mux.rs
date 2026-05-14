//! VLESS-side wire-multiplexing (`mux`) configuration.
//!
//! NekoBox / sing-box subscriptions frequently request `mux: sing-mux` (or
//! `mux: yamux`) on a VLESS outbound to amortize connection-establishment
//! overhead across many logical flows. The wire codecs and the
//! protocol-agnostic session layer live in `ripdpi-relay-mux`'s `wire_mux`
//! module; this module is the thin VLESS-profile-shaped config that selects a
//! protocol and carries the limits, then hands them to that session layer.
//!
//! Keeping the config here (rather than re-exporting `ripdpi-relay-mux`
//! types directly on `VlessRealityConfig`) means the VLESS crate owns its own
//! profile surface: the Kotlin layer passes strings, [`VlessMuxConfig`]
//! parses and validates them, and [`VlessMuxConfig::to_limits`] converts to
//! the `ripdpi-relay-mux` [`MuxLimits`] the transport actually consumes.

use ripdpi_relay_mux::wire_mux::{MuxLimits, MuxProtocol, PaddingMode};

/// Which wire multiplexer a VLESS outbound should run over its connection.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VlessMuxProtocol {
    /// sing-box's sing-mux.
    SingMux,
    /// hashicorp yamux.
    Yamux,
}

impl VlessMuxProtocol {
    /// Parse the `mux` protocol token from a subscription / profile string.
    ///
    /// Accepts the spellings real subscriptions use: `sing-mux`, `singmux`,
    /// `smux` (the sing-box alias for its own mux -- note this is *not* the
    /// Trojan-Go `smux`, which is a separate protocol and out of scope here),
    /// `h2mux` (a yamux alias seen in some configs), and `yamux`.
    pub fn parse(token: &str) -> Result<Self, MuxConfigError> {
        match token.trim().to_ascii_lowercase().as_str() {
            "sing-mux" | "singmux" | "smux" => Ok(Self::SingMux),
            "yamux" | "h2mux" => Ok(Self::Yamux),
            other => Err(MuxConfigError::UnknownProtocol(other.to_string())),
        }
    }

    /// Map to the protocol enum the `ripdpi-relay-mux` session layer uses.
    pub fn to_wire_protocol(self) -> MuxProtocol {
        match self {
            Self::SingMux => MuxProtocol::SingMux,
            Self::Yamux => MuxProtocol::Yamux,
        }
    }
}

/// Errors produced while parsing a VLESS `mux` config block.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum MuxConfigError {
    /// The `mux` protocol token was not a recognized multiplexer name.
    #[error("unknown mux protocol `{0}` (expected sing-mux or yamux)")]
    UnknownProtocol(String),
    /// `max_concurrent_streams` was given as `0`, which would open no streams.
    #[error("mux max_concurrent_streams must be at least 1")]
    ZeroMaxStreams,
}

/// A VLESS outbound's `mux` configuration block.
///
/// Present (`Some`) on a `VlessRealityConfig` when the subscription requested
/// multiplexing; absent (`None`) for the default one-connection-per-flow
/// behavior.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VlessMuxConfig {
    /// Which wire multiplexer to run.
    pub protocol: VlessMuxProtocol,
    /// Maximum substreams open concurrently over one mux session.
    pub max_concurrent_streams: usize,
    /// Soft per-connection throughput target hint, in KB/s. `None` is
    /// unthrottled.
    pub per_connection_kbps: Option<u32>,
    /// sing-mux v2 padding: when `Some(max)`, each `New`/`Data` frame gets
    /// `1..=max` random pad bytes. Ignored for yamux.
    pub sing_mux_padding_max: Option<u16>,
}

impl VlessMuxConfig {
    /// Build a mux config for `protocol` with the `ripdpi-relay-mux` defaults
    /// (256 concurrent streams, unthrottled, no padding).
    pub fn new(protocol: VlessMuxProtocol) -> Self {
        let defaults = MuxLimits::default();
        Self {
            protocol,
            max_concurrent_streams: defaults.max_concurrent_streams,
            per_connection_kbps: None,
            sing_mux_padding_max: None,
        }
    }

    /// Parse a `mux` config from the string fields the Kotlin layer passes.
    ///
    /// `protocol` is required; `max_concurrent_streams` is `0` to mean "use
    /// the default"; `per_connection_kbps` is `0` to mean "unthrottled";
    /// `sing_mux_padding_max` is `0` to mean "no padding".
    pub fn from_strings(
        protocol: &str,
        max_concurrent_streams: u32,
        per_connection_kbps: u32,
        sing_mux_padding_max: u32,
    ) -> Result<Self, MuxConfigError> {
        let protocol = VlessMuxProtocol::parse(protocol)?;
        let mut config = Self::new(protocol);
        if max_concurrent_streams != 0 {
            config.max_concurrent_streams = max_concurrent_streams as usize;
        }
        if per_connection_kbps != 0 {
            config.per_connection_kbps = Some(per_connection_kbps);
        }
        if sing_mux_padding_max != 0 {
            config.sing_mux_padding_max = Some(sing_mux_padding_max.min(u32::from(u16::MAX)) as u16);
        }
        Ok(config)
    }

    /// Builder: cap concurrent substreams.
    pub fn with_max_concurrent_streams(mut self, max: usize) -> Result<Self, MuxConfigError> {
        if max == 0 {
            return Err(MuxConfigError::ZeroMaxStreams);
        }
        self.max_concurrent_streams = max;
        Ok(self)
    }

    /// Builder: set the per-connection KB/s target hint.
    pub fn with_per_connection_kbps(mut self, kbps: u32) -> Self {
        self.per_connection_kbps = Some(kbps);
        self
    }

    /// Builder: enable sing-mux v2 padding with `1..=max` pad bytes per frame.
    pub fn with_sing_mux_padding(mut self, max: u16) -> Self {
        self.sing_mux_padding_max = Some(max);
        self
    }

    /// Convert this profile-shaped config into the `ripdpi-relay-mux`
    /// [`MuxLimits`] the wire-mux session layer consumes.
    ///
    /// Padding is only carried through for sing-mux; a yamux config ignores
    /// `sing_mux_padding_max` because yamux has no padding extension.
    pub fn to_limits(&self) -> MuxLimits {
        let padding_mode = match (self.protocol, self.sing_mux_padding_max) {
            (VlessMuxProtocol::SingMux, Some(max)) if max > 0 => PaddingMode::Enabled { max },
            _ => PaddingMode::Disabled,
        };
        let mut limits = MuxLimits::default()
            .with_max_concurrent_streams(self.max_concurrent_streams)
            .with_padding_mode(padding_mode);
        if let Some(kbps) = self.per_connection_kbps {
            limits = limits.with_per_connection_kbps(kbps);
        }
        limits
    }

    /// The `ripdpi-relay-mux` wire protocol this config selects.
    pub fn wire_protocol(&self) -> MuxProtocol {
        self.protocol.to_wire_protocol()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_accepts_sing_mux_spellings() {
        for token in ["sing-mux", "SING-MUX", " singmux ", "smux"] {
            assert_eq!(VlessMuxProtocol::parse(token), Ok(VlessMuxProtocol::SingMux), "token: {token:?}");
        }
    }

    #[test]
    fn parse_accepts_yamux_spellings() {
        for token in ["yamux", "YAMUX", "h2mux"] {
            assert_eq!(VlessMuxProtocol::parse(token), Ok(VlessMuxProtocol::Yamux), "token: {token:?}");
        }
    }

    #[test]
    fn parse_rejects_unknown_protocol() {
        let err = VlessMuxProtocol::parse("mptcp").expect_err("unknown protocol must be rejected");
        assert_eq!(err, MuxConfigError::UnknownProtocol("mptcp".to_string()));
    }

    #[test]
    fn new_uses_relay_mux_defaults() {
        let config = VlessMuxConfig::new(VlessMuxProtocol::SingMux);
        let defaults = MuxLimits::default();
        assert_eq!(config.max_concurrent_streams, defaults.max_concurrent_streams);
        assert_eq!(config.per_connection_kbps, None);
        assert_eq!(config.sing_mux_padding_max, None);
    }

    #[test]
    fn from_strings_parses_a_full_sing_mux_block() {
        let config = VlessMuxConfig::from_strings("sing-mux", 16, 4096, 256).expect("valid mux block");
        assert_eq!(config.protocol, VlessMuxProtocol::SingMux);
        assert_eq!(config.max_concurrent_streams, 16);
        assert_eq!(config.per_connection_kbps, Some(4096));
        assert_eq!(config.sing_mux_padding_max, Some(256));
    }

    #[test]
    fn from_strings_treats_zero_fields_as_defaults() {
        let config = VlessMuxConfig::from_strings("yamux", 0, 0, 0).expect("valid mux block");
        assert_eq!(config.protocol, VlessMuxProtocol::Yamux);
        assert_eq!(config.max_concurrent_streams, MuxLimits::default().max_concurrent_streams);
        assert_eq!(config.per_connection_kbps, None);
        assert_eq!(config.sing_mux_padding_max, None);
    }

    #[test]
    fn from_strings_rejects_unknown_protocol() {
        assert!(VlessMuxConfig::from_strings("nope", 8, 0, 0).is_err());
    }

    #[test]
    fn with_max_concurrent_streams_rejects_zero() {
        let err = VlessMuxConfig::new(VlessMuxProtocol::Yamux)
            .with_max_concurrent_streams(0)
            .expect_err("zero streams must be rejected");
        assert_eq!(err, MuxConfigError::ZeroMaxStreams);
    }

    #[test]
    fn to_limits_carries_sing_mux_padding() {
        let config = VlessMuxConfig::new(VlessMuxProtocol::SingMux).with_sing_mux_padding(128);
        let limits = config.to_limits();
        assert_eq!(limits.padding_mode, PaddingMode::Enabled { max: 128 });
    }

    #[test]
    fn to_limits_ignores_padding_for_yamux() {
        // A yamux config carrying a padding value still produces Disabled --
        // yamux has no padding extension.
        let mut config = VlessMuxConfig::new(VlessMuxProtocol::Yamux);
        config.sing_mux_padding_max = Some(128);
        assert_eq!(config.to_limits().padding_mode, PaddingMode::Disabled);
    }

    #[test]
    fn to_limits_carries_concurrency_and_throughput() {
        let config = VlessMuxConfig::new(VlessMuxProtocol::SingMux)
            .with_max_concurrent_streams(32)
            .expect("non-zero")
            .with_per_connection_kbps(8192);
        let limits = config.to_limits();
        assert_eq!(limits.max_concurrent_streams, 32);
        assert_eq!(limits.per_connection_kbps, Some(8192));
    }

    #[test]
    fn wire_protocol_maps_to_relay_mux_enum() {
        assert_eq!(VlessMuxConfig::new(VlessMuxProtocol::SingMux).wire_protocol(), MuxProtocol::SingMux);
        assert_eq!(VlessMuxConfig::new(VlessMuxProtocol::Yamux).wire_protocol(), MuxProtocol::Yamux);
    }
}
