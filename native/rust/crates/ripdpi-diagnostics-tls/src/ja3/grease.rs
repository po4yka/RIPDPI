pub(super) fn is_grease(value: u16) -> bool {
    let hi = (value >> 8) as u8;
    let lo = value as u8;
    hi == lo && (hi & 0x0f) == 0x0a
}
