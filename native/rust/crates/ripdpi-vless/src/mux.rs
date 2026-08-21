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
        Ok(Self { protocol: VlessMuxProtocol::parse(protocol)?, max_concurrent_streams })
    }
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
