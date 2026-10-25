//! VLESS Addons protobuf-encoded constants.
//!
//! Upstream reference: xray-core (XTLS/Xray-core) flow strings. The
//! addons message carries `Flow = "<flow-name>"` as field 1. The byte
//! constants here are hand-encoded so the crate stays free of any
//! protobuf runtime dependency.
//!
//! See `SPEC_VERSION.md` for the pinned upstream tag.

use std::fmt;

/// Hand-encoded protobuf for the VLESS addons message with
/// `Flow = "xtls-rprx-vision"`.
///
/// Protobuf encoding: field 1 (tag = 0x0a), length-delimited, 16 bytes.
/// Field 1 wire format: `0x0a` (field number 1, wire type 2 = length-delimited).
/// Length: `0x10` (16 bytes).
/// Payload: `b"xtls-rprx-vision"`.
pub const VISION_ADDONS: &[u8] = &[
    0x0a, 0x10, // field 1, length 16
    b'x', b't', b'l', b's', b'-', b'r', b'p', b'r', b'x', b'-', b'v', b'i', b's', b'i', b'o', b'n',
];

/// VLESS flow selection. xray-core servers accept clients with either no
/// flow (empty addons) or `xtls-rprx-vision`. The `-udp443` spelling is a
/// client-side policy only: Xray trims it to `xtls-rprx-vision` before it
/// writes the request header, and servers reject the longer string.
/// Hardcoding Vision in every connection breaks compatibility with
/// servers that advertise `flow: ""` and is a packet-level fingerprint
/// for DPI heuristics looking for unconditional Vision-addons traffic.
///
/// `Default` resolves to `Vision` to preserve historical client behavior
/// for callers that have not opted in to per-profile flow selection.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum VlessFlow {
    /// No flow. Sends an empty addons block. Matches xray's `flow: ""`.
    None,
    /// `xtls-rprx-vision`. Default for back-compatibility.
    #[default]
    Vision,
    /// `xtls-rprx-vision-udp443`. Same on-wire addons as [`Self::Vision`];
    /// the suffix only lets the client carry UDP/443 through XUDP.
    VisionUdp443,
}

impl VlessFlow {
    /// On-wire addons payload (without the leading length byte the
    /// VLESS encoder prepends).
    pub const fn as_addons_bytes(self) -> &'static [u8] {
        match self {
            Self::None => &[],
            Self::Vision | Self::VisionUdp443 => VISION_ADDONS,
        }
    }

    /// Parse a Kotlin-supplied flow string. Accepts the empty string and
    /// `"none"` for [`Self::None`]; otherwise expects the exact xray
    /// flow identifier.
    pub fn parse(value: &str) -> Result<Self, FlowParseError> {
        match value.trim() {
            "" | "none" | "off" => Ok(Self::None),
            "xtls-rprx-vision" => Ok(Self::Vision),
            "xtls-rprx-vision-udp443" => Ok(Self::VisionUdp443),
            other => Err(FlowParseError(other.to_owned())),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("unsupported VLESS flow {0:?}; expected '', 'none', 'xtls-rprx-vision', or 'xtls-rprx-vision-udp443'")]
pub struct FlowParseError(pub String);

impl fmt::Display for VlessFlow {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let label = match self {
            Self::None => "",
            Self::Vision => "xtls-rprx-vision",
            Self::VisionUdp443 => "xtls-rprx-vision-udp443",
        };
        f.write_str(label)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vision_addons_encoding() {
        assert_eq!(VISION_ADDONS.len(), 18);
        // Tag: field 1, wire type 2 (length-delimited)
        assert_eq!(VISION_ADDONS[0], 0x0a);
        // Length
        assert_eq!(VISION_ADDONS[1], 16);
        // Payload
        assert_eq!(&VISION_ADDONS[2..], b"xtls-rprx-vision");
    }

    #[test]
    fn flow_as_addons_bytes_maps_each_variant() {
        assert_eq!(VlessFlow::None.as_addons_bytes(), b"");
        assert_eq!(VlessFlow::Vision.as_addons_bytes(), VISION_ADDONS);
        // Xray trims the -udp443 suffix before it writes the request header;
        // servers reject the 23-byte spelling as an unknown flow.
        assert_eq!(VlessFlow::VisionUdp443.as_addons_bytes(), b"\x0a\x10xtls-rprx-vision");
    }

    #[test]
    fn flow_default_is_vision_for_back_compat() {
        // Existing callers that construct VlessRealityConfig without an
        // explicit flow continue to send VISION_ADDONS; the unconditional
        // Vision default is preserved while the choice is now configurable.
        assert_eq!(VlessFlow::default(), VlessFlow::Vision);
    }

    #[test]
    fn flow_parse_recognizes_empty_and_named_variants() {
        assert_eq!(VlessFlow::parse("").unwrap(), VlessFlow::None);
        assert_eq!(VlessFlow::parse("  ").unwrap(), VlessFlow::None);
        assert_eq!(VlessFlow::parse("none").unwrap(), VlessFlow::None);
        assert_eq!(VlessFlow::parse("off").unwrap(), VlessFlow::None);
        assert_eq!(VlessFlow::parse("xtls-rprx-vision").unwrap(), VlessFlow::Vision);
        assert_eq!(VlessFlow::parse("  xtls-rprx-vision  ").unwrap(), VlessFlow::Vision);
        assert_eq!(VlessFlow::parse("xtls-rprx-vision-udp443").unwrap(), VlessFlow::VisionUdp443);
    }

    #[test]
    fn flow_parse_rejects_unknown_strings() {
        let err = VlessFlow::parse("xtls-rprx-direct").expect_err("unknown flow must be rejected");
        assert_eq!(err.0, "xtls-rprx-direct");
        // Diagnostic must name the offending value so a misconfigured
        // subscription surfaces a useful error in the Kotlin log.
        assert!(err.to_string().contains("xtls-rprx-direct"));
    }

    #[test]
    fn flow_display_matches_xray_identifier() {
        assert_eq!(VlessFlow::None.to_string(), "");
        assert_eq!(VlessFlow::Vision.to_string(), "xtls-rprx-vision");
        assert_eq!(VlessFlow::VisionUdp443.to_string(), "xtls-rprx-vision-udp443");
    }
}
