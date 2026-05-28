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

/// Hand-encoded protobuf for the VLESS addons message with
/// `Flow = "xtls-rprx-vision-udp443"`.
///
/// Same wire shape as [`VISION_ADDONS`], with a 23-byte payload string.
/// This variant signals to the server that the client wants UDP-443
/// behavior on top of Vision; the wire encoding is otherwise identical.
///
/// **Selecting which variant to send** is a per-profile decision. This crate
/// exposes all supported byte slices and [`crate::config::VlessRealityConfig`]
/// carries the selected flow. The default remains `VISION_ADDONS` for
/// compatibility; Android profile UX may expose only a subset.
pub const VISION_UDP443_ADDONS: &[u8] = &[
    0x0a, 0x17, // field 1, length 23
    b'x', b't', b'l', b's', b'-', b'r', b'p', b'r', b'x', b'-', b'v', b'i', b's', b'i', b'o', b'n', b'-', b'u', b'd',
    b'p', b'4', b'4', b'3',
];

/// VLESS flow selection. xray-core servers accept clients with either no
/// flow (empty addons), `xtls-rprx-vision`, or `xtls-rprx-vision-udp443`.
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
    /// `xtls-rprx-vision-udp443`.
    VisionUdp443,
}

impl VlessFlow {
    /// On-wire addons payload (without the leading length byte the
    /// VLESS encoder prepends).
    pub const fn as_addons_bytes(self) -> &'static [u8] {
        match self {
            Self::None => &[],
            Self::Vision => VISION_ADDONS,
            Self::VisionUdp443 => VISION_UDP443_ADDONS,
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
    fn vision_udp443_addons_encoding() {
        assert_eq!(VISION_UDP443_ADDONS.len(), 25);
        // Tag: field 1, wire type 2 (length-delimited)
        assert_eq!(VISION_UDP443_ADDONS[0], 0x0a);
        // Length
        assert_eq!(VISION_UDP443_ADDONS[1], 23);
        // Payload
        assert_eq!(&VISION_UDP443_ADDONS[2..], b"xtls-rprx-vision-udp443");
    }

    #[test]
    fn flow_as_addons_bytes_maps_each_variant() {
        assert_eq!(VlessFlow::None.as_addons_bytes(), b"");
        assert_eq!(VlessFlow::Vision.as_addons_bytes(), VISION_ADDONS);
        assert_eq!(VlessFlow::VisionUdp443.as_addons_bytes(), VISION_UDP443_ADDONS);
    }

    #[test]
    fn flow_default_is_vision_for_back_compat() {
        // Existing callers that construct VlessRealityConfig without an
        // explicit flow continue to send VISION_ADDONS; the audit's
        // "unconditional Vision" finding is resolved by making the choice
        // configurable, not by changing the default.
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
