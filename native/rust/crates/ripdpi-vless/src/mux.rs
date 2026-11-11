//! VLESS mux configuration and the sing-mux carrier preamble.
//!
//! A VLESS mux carrier is a normal VLESS TCP request to the reserved
//! `sp.mux.sing-box.arpa:444` destination. After the VLESS response, the
//! client writes the SagerNet sing-mux request and then speaks the selected
//! subprotocol. This module deliberately supports only yamux: `smux` and
//! `h2mux` are distinct protocols and are rejected until their complete wire
//! implementations exist.

use std::io;

/// Destination required by the SagerNet sing-mux carrier protocol.
pub const SING_MUX_DESTINATION: &str = "sp.mux.sing-box.arpa:444";

const SING_MUX_VERSION_0: u8 = 0;
const SING_MUX_PROTOCOL_YAMUX: u8 = 1;

// Keep the pinned yamux receive budget, including initial credit for closing streams.
const YAMUX_RECEIVE_WINDOW: usize = 1024 * 1024 * 1024;
const MAX_SESSION_STREAMS: usize = YAMUX_RECEIVE_WINDOW / yamux::DEFAULT_CREDIT as usize;
const CLOSING_STREAM_FACTOR: usize = 2;

/// The only VLESS mux subprotocol implemented by the native relay.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VlessMuxProtocol {
    /// Hashicorp yamux, encapsulated in the SagerNet sing-mux carrier format.
    Yamux,
}

/// Errors produced while parsing a VLESS mux profile.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum MuxConfigError {
    /// The selected protocol has no complete native wire implementation.
    #[error("unsupported VLESS mux protocol")]
    UnsupportedProtocol,
    /// A working or session stream limit exceeds the bounded receive capacity.
    #[error("mux stream limit {requested} must be between 1 and {max}")]
    InvalidStreamLimit { requested: usize, max: usize },
    /// The profile sets a legacy throughput/padding knob that the native
    /// yamux carrier does not implement; carrying it would silently change
    /// the requested profile.
    #[error("legacy mux knob `{field}` must be zero for the native yamux carrier")]
    LegacyKnobNonZero {
        /// Name of the offending profile knob.
        field: &'static str,
    },
}

impl VlessMuxProtocol {
    /// Parse only the actually supported upstream protocol token.
    pub fn parse(token: &str) -> Result<Self, MuxConfigError> {
        match token.trim().to_ascii_lowercase().as_str() {
            "yamux" => Ok(Self::Yamux),
            // `sing-mux` selects an inner protocol; it is not itself a frame
            // protocol. `smux` and `h2mux` must never be coerced to yamux.
            _ => Err(MuxConfigError::UnsupportedProtocol),
        }
    }
}

/// Configures a reusable VLESS mux carrier.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VlessMuxConfig {
    pub protocol: VlessMuxProtocol,
    /// Working streams, excluding the session reserve for closing streams.
    pub max_concurrent_streams: usize,
}

impl VlessMuxConfig {
    /// Parses a profile mux block. Legacy throughput and padding knobs are
    /// rejected when non-zero because neither belongs to yamux's wire
    /// contract; accepting them would silently change the requested profile.
    pub fn from_strings(
        protocol: &str,
        max_concurrent_streams: u32,
        per_connection_kbps: u32,
        sing_mux_padding_max: u32,
    ) -> Result<Self, MuxConfigError> {
        if per_connection_kbps != 0 {
            return Err(MuxConfigError::LegacyKnobNonZero { field: "per_connection_kbps" });
        }
        if sing_mux_padding_max != 0 {
            return Err(MuxConfigError::LegacyKnobNonZero { field: "sing_mux_padding_max" });
        }
        // Zero means the sing-box default of 256 concurrent substreams.
        let max_concurrent_streams = if max_concurrent_streams == 0 { 256 } else { max_concurrent_streams as usize };
        let config = Self { protocol: VlessMuxProtocol::parse(protocol)?, max_concurrent_streams };
        config.session_stream_limit()?;
        Ok(config)
    }

    pub(crate) fn session_stream_limit(self) -> Result<usize, MuxConfigError> {
        // Closing streams remain counted by yamux after their pool lease is released.
        self.max_concurrent_streams
            .checked_mul(CLOSING_STREAM_FACTOR)
            .filter(|&capacity| capacity > 0 && capacity <= MAX_SESSION_STREAMS)
            .ok_or(MuxConfigError::InvalidStreamLimit {
                requested: self.max_concurrent_streams,
                max: MAX_SESSION_STREAMS / CLOSING_STREAM_FACTOR,
            })
    }
}

pub(crate) fn session_config(capacity: usize) -> io::Result<yamux::Config> {
    if capacity == 0 || capacity > MAX_SESSION_STREAMS {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            MuxConfigError::InvalidStreamLimit { requested: capacity, max: MAX_SESSION_STREAMS },
        ));
    }
    let mut config = yamux::Config::default();
    config.set_max_connection_receive_window(Some(YAMUX_RECEIVE_WINDOW));
    config.set_max_num_streams(capacity);
    Ok(config)
}

/// Encodes the exact SagerNet sing-mux version-0/yamux session request.
pub fn encode_session_request() -> [u8; 2] {
    [SING_MUX_VERSION_0, SING_MUX_PROTOCOL_YAMUX]
}

/// Encodes the sing-mux TCP stream request sent over a newly opened yamux
/// logical stream: big-endian flags followed by sing's Socksaddr encoding.
pub fn encode_tcp_stream_request(destination: &str) -> io::Result<Vec<u8>> {
    let (host, port) = split_destination(destination)?;
    let mut out = Vec::with_capacity(3 + host.len());
    out.extend_from_slice(&0u16.to_be_bytes());
    if let Ok(address) = host.parse::<std::net::Ipv4Addr>() {
        out.push(1);
        out.extend_from_slice(&address.octets());
    } else if let Ok(address) = host.parse::<std::net::Ipv6Addr>() {
        out.push(4);
        out.extend_from_slice(&address.octets());
    } else {
        let host = host.as_bytes();
        let length = u8::try_from(host.len())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "mux host is too long"))?;
        out.push(3);
        out.push(length);
        out.extend_from_slice(host);
    }
    out.extend_from_slice(&port.to_be_bytes());
    Ok(out)
}

/// Validates the one-byte stream response from the mux server.
pub async fn read_stream_response(stream: &mut (impl tokio::io::AsyncRead + Unpin)) -> io::Result<()> {
    use tokio::io::AsyncReadExt;
    let status = stream.read_u8().await?;
    if status == 0 {
        Ok(())
    } else {
        Err(io::Error::new(io::ErrorKind::ConnectionRefused, "VLESS mux stream request rejected"))
    }
}

fn split_destination(destination: &str) -> io::Result<(String, u16)> {
    crate::wire::parse_target(destination).map_err(|_| invalid_destination(destination))
}

fn invalid_destination(_: impl std::fmt::Display) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, "invalid mux destination")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_request_matches_pinned_sing_mux_yamux_vector() {
        assert_eq!(encode_session_request(), [0, 1]);
    }

    #[test]
    fn stream_request_uses_socksaddr_domain_format() {
        assert_eq!(
            encode_tcp_stream_request("example.com:443").unwrap(),
            [0, 0, 3, 11, b'e', b'x', b'a', b'm', b'p', b'l', b'e', b'.', b'c', b'o', b'm', 1, 187]
        );
    }

    #[test]
    fn unsupported_protocols_fail_without_coercion() {
        for token in ["sing-mux", "smux", "h2mux"] {
            assert_eq!(VlessMuxProtocol::parse(token), Err(MuxConfigError::UnsupportedProtocol));
        }
    }

    #[test]
    fn legacy_knobs_are_rejected_with_the_offending_field_named() {
        assert_eq!(
            VlessMuxConfig::from_strings("yamux", 64, 1024, 0),
            Err(MuxConfigError::LegacyKnobNonZero { field: "per_connection_kbps" })
        );
        assert_eq!(
            VlessMuxConfig::from_strings("yamux", 64, 0, 512),
            Err(MuxConfigError::LegacyKnobNonZero { field: "sing_mux_padding_max" })
        );
    }

    #[test]
    fn zero_max_streams_defaults_to_the_sing_box_default() {
        let config = VlessMuxConfig::from_strings("yamux", 0, 0, 0).expect("zero streams must default");
        assert_eq!(config.max_concurrent_streams, 256);
    }

    #[test]
    fn oversized_yamux_working_limits_are_rejected() {
        for limit in [2049, u32::MAX] {
            assert!(VlessMuxConfig::from_strings("yamux", limit, 0, 0).is_err(), "accepted {limit}");
        }
    }

    #[test]
    fn supported_yamux_limits_reserve_closing_capacity() {
        for (working, logical, session) in [(0, 256, 512), (1, 1, 2), (2048, 2048, 4096)] {
            let config = VlessMuxConfig::from_strings("yamux", working, 0, 0).expect("supported working limit");
            assert_eq!(config.max_concurrent_streams, logical);
            assert_eq!(config.session_stream_limit().expect("closing reserve"), session);
            super::session_config(session).expect("real yamux constructor accepts admitted capacity");
        }
    }

    #[test]
    fn direct_yamux_working_limits_cannot_overflow_the_reserve() {
        for requested in [0, 2049, u32::MAX as usize, usize::MAX] {
            let config = VlessMuxConfig { protocol: VlessMuxProtocol::Yamux, max_concurrent_streams: requested };
            assert_eq!(config.session_stream_limit(), Err(MuxConfigError::InvalidStreamLimit { requested, max: 2048 }),);
        }
    }

    /// # Cancel safety:
    /// cancel-safe: invalid configuration fails before connection; the test owns its listener.
    #[tokio::test]
    async fn invalid_direct_yamux_config_is_rejected_before_connect() {
        use base64::Engine as _;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("owned listener");
        let endpoint = listener.local_addr().expect("listener address");
        let mut config = crate::config::VlessRealityConfig::from_strings(
            "127.0.0.1",
            i32::from(endpoint.port()),
            "550e8400-e29b-41d4-a716-446655440000",
            "example.test",
            &base64::engine::general_purpose::STANDARD.encode([0xAB; 32]),
            "abcd1234",
            "chrome_stable",
        )
        .expect("valid base config");
        for limit in [0, 2049, u32::MAX as usize, usize::MAX] {
            config.mux = Some(VlessMuxConfig { protocol: VlessMuxProtocol::Yamux, max_concurrent_streams: limit });
            let result = tokio::time::timeout(
                std::time::Duration::from_secs(1),
                crate::VlessRealityClient::connect_mux_to_guarded(&config, None, endpoint, ()),
            )
            .await
            .expect("configuration rejection must not wait for a peer");
            let Err(error) = result else { panic!("accepted {limit}") };
            assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        }
        assert!(tokio::time::timeout(std::time::Duration::from_millis(20), listener.accept()).await.is_err());
    }

    #[test]
    fn pinned_yamux_fixtures_match_encoder() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../..");
        let fixtures = root.join("contract-fixtures/vless/sing-mux-6fb501d/mux/yamux");
        let cases: [(&str, Vec<u8>); 8] = [
            ("session-v0-yamux.hex", encode_session_request().to_vec()),
            ("tcp-domain-example-443.hex", encode_tcp_stream_request("example.com:443").unwrap()),
            ("tcp-domain-interleave-443.hex", encode_tcp_stream_request("interleave.test:443").unwrap()),
            ("tcp-ipv4-loopback-80.hex", encode_tcp_stream_request("127.0.0.1:80").unwrap()),
            ("tcp-ipv4-public-443.hex", encode_tcp_stream_request("8.8.8.8:443").unwrap()),
            ("tcp-ipv6-loopback-443.hex", encode_tcp_stream_request("[::1]:443").unwrap()),
            ("tcp-domain-minimal-1.hex", encode_tcp_stream_request("a:1").unwrap()),
            ("tcp-domain-alt-port.hex", encode_tcp_stream_request("test.local:8080").unwrap()),
        ];
        assert_eq!(cases.len(), 8, "the mux golden minimum is deliberate");
        for (name, encoded) in cases {
            let golden = std::fs::read_to_string(fixtures.join(name)).unwrap();
            let golden = hex::decode(golden.trim()).unwrap();
            assert_eq!(encoded, golden, "fixture {name}");
        }
    }
}
