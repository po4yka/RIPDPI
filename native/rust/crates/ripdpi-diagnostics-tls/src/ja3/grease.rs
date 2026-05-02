/// Returns true if the value is a GREASE (Generate Random Extensions And
/// Sustain Extensibility) value. GREASE values have the pattern 0x?a?a where
/// the high and low bytes are equal and `(byte & 0x0f) == 0x0a`.
pub(super) fn is_grease(value: u16) -> bool {
    let hi = (value >> 8) as u8;
    let lo = value as u8;
    hi == lo && (hi & 0x0f) == 0x0a
}
