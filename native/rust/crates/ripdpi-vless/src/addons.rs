//! VLESS Addons protobuf-encoded constants.
//!
//! Upstream reference: xray-core (XTLS/Xray-core) flow strings. The
//! addons message carries `Flow = "<flow-name>"` as field 1. The byte
//! constants here are hand-encoded so the crate stays free of any
//! protobuf runtime dependency.
//!
//! See `SPEC_VERSION.md` for the pinned upstream tag.

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
/// **Selecting which variant to send** is a per-profile decision that
/// the engine must make; this crate exposes both byte slices and the
/// caller picks. Until that selection is wired through the profile
/// editor, default flow remains `VISION_ADDONS` and the UDP-443 path
/// is opt-in via configuration. See
/// `docs/tasks/issues/add-vless-flow-xtls-rprx-vision-udp443-support.md`.
pub const VISION_UDP443_ADDONS: &[u8] = &[
    0x0a, 0x17, // field 1, length 23
    b'x', b't', b'l', b's', b'-', b'r', b'p', b'r', b'x', b'-', b'v', b'i', b's', b'i', b'o', b'n', b'-', b'u', b'd',
    b'p', b'4', b'4', b'3',
];

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
}
