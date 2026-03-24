use crate::types::OracleRng;

pub(crate) fn read_u16(data: &[u8], offset: usize) -> Option<usize> {
    if offset + 1 >= data.len() {
        return None;
    }
    Some(((data[offset] as usize) << 8) | data[offset + 1] as usize)
}

pub(crate) fn read_u24(data: &[u8], offset: usize) -> Option<usize> {
    if offset + 2 >= data.len() {
        return None;
    }
    Some(((data[offset] as usize) << 16) | ((data[offset + 1] as usize) << 8) | data[offset + 2] as usize)
}

pub(crate) fn read_u32(data: &[u8], offset: usize) -> Option<u32> {
    let bytes = data.get(offset..offset + 4)?;
    Some(u32::from_be_bytes(bytes.try_into().ok()?))
}

pub(crate) fn write_u16(data: &mut [u8], offset: usize, value: usize) -> bool {
    if offset + 1 >= data.len() || value > u16::MAX as usize {
        return false;
    }
    data[offset] = ((value >> 8) & 0xff) as u8;
    data[offset + 1] = (value & 0xff) as u8;
    true
}

pub(crate) fn write_u24(data: &mut [u8], offset: usize, value: usize) -> bool {
    if offset + 2 >= data.len() || value > 0x00ff_ffff {
        return false;
    }
    data[offset] = ((value >> 16) & 0xff) as u8;
    data[offset + 1] = ((value >> 8) & 0xff) as u8;
    data[offset + 2] = (value & 0xff) as u8;
    true
}

pub(crate) fn ascii_case_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b.iter()).all(|(left, right)| left.eq_ignore_ascii_case(right))
}

pub(crate) fn strncase_find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || needle.len() > haystack.len() {
        return None;
    }
    haystack.windows(needle.len()).position(|window| ascii_case_eq(window, needle))
}

pub(crate) fn parse_u16_ascii(data: &[u8]) -> Option<u16> {
    std::str::from_utf8(data).ok()?.parse().ok()
}

pub(crate) fn fill_random_alnum(byte: &mut u8, rng: &mut OracleRng) {
    let roll = rng.next_mod(36);
    *byte = if roll < 10 { b'0' + roll as u8 } else { b'a' + (roll as u8 - 10) };
}

pub(crate) fn fill_random_lower(byte: &mut u8, rng: &mut OracleRng) {
    *byte = b'a' + (rng.next_u8() % 26);
}

pub(crate) fn fill_random_tls_host_like_c(host: &mut [u8], rng: &mut OracleRng) {
    const RANDOM_TLDS: [&[u8; 3]; 8] = [b"com", b"net", b"org", b"edu", b"gov", b"mil", b"int", b"biz"];
    if host.is_empty() {
        return;
    }
    fill_random_lower(&mut host[0], rng);
    let len = host.len();
    if len >= 7 {
        for byte in &mut host[1..len - 4] {
            fill_random_alnum(byte, rng);
        }
        host[len - 4] = b'.';
        host[len - 3..].copy_from_slice(RANDOM_TLDS[rng.next_mod(RANDOM_TLDS.len())]);
    } else {
        for byte in &mut host[1..] {
            fill_random_alnum(byte, rng);
        }
    }
}

pub(crate) fn copy_name_seeded(out: &mut [u8], pattern: &[u8], rng: &mut OracleRng) {
    for (dst, src) in out.iter_mut().zip(pattern.iter().copied()) {
        *dst = match src {
            b'*' => {
                let roll = (rng.next_u8() as usize) % (10 + (b'z' - b'a' + 1) as usize);
                if roll < 10 {
                    b'0' + roll as u8
                } else {
                    b'a' + (roll as u8 - 10)
                }
            }
            b'?' => b'a' + (rng.next_u8() % (b'z' - b'a' + 1)),
            b'#' => b'0' + (rng.next_u8() % 10),
            other => other,
        };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn read_u16_boundary_conditions() {
        assert_eq!(read_u16(&[0xAB, 0xCD], 0), Some(0xABCD));
        assert_eq!(read_u16(&[0xAB], 0), None);
        assert_eq!(read_u16(&[], 0), None);
        assert_eq!(read_u16(&[0x01, 0x02, 0x03], 2), None);
    }

    #[test]
    fn write_u16_rejects_overflow() {
        let mut buf = [0u8; 4];
        assert!(write_u16(&mut buf, 0, 0xFFFF));
        assert_eq!(buf[0], 0xFF);
        assert_eq!(buf[1], 0xFF);
        assert!(!write_u16(&mut buf, 0, 65536));
    }

    #[test]
    fn ascii_case_eq_different_lengths() {
        assert!(!ascii_case_eq(b"abc", b"abcd"));
        assert!(!ascii_case_eq(b"abcd", b"abc"));
        assert!(ascii_case_eq(b"AbC", b"abc"));
    }

    #[test]
    fn copy_name_seeded_pattern_chars() {
        let mut rng = OracleRng::seeded(0);
        let mut out = [0u8; 5];

        copy_name_seeded(&mut out[..1], b"*", &mut rng);
        assert!(out[0].is_ascii_alphanumeric());

        copy_name_seeded(&mut out[..1], b"?", &mut rng);
        assert!(out[0].is_ascii_lowercase());

        copy_name_seeded(&mut out[..1], b"#", &mut rng);
        assert!(out[0].is_ascii_digit());

        copy_name_seeded(&mut out[..1], b"X", &mut rng);
        assert_eq!(out[0], b'X');
    }
}
