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
}
