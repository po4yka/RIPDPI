use std::io;
use std::time::{SystemTime, UNIX_EPOCH};

pub(crate) const DNS_RECORD_TYPE_A: u16 = 1;
pub(crate) const DNS_RECORD_TYPE_AAAA: u16 = 28;

pub(crate) fn build_dns_query(domain: &str, record_type: u16, query_id: u16) -> io::Result<Vec<u8>> {
    let domain = domain.strip_suffix('.').unwrap_or(domain);
    let mut packet = Vec::with_capacity(512);
    packet.extend(query_id.to_be_bytes());
    packet.extend(0x0100u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    for label in domain.split('.') {
        if label.is_empty() || label.len() > 63 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid dns name"));
        }
        packet.push(label.len() as u8);
        packet.extend(label.as_bytes());
    }
    packet.push(0);
    packet.extend(record_type.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    Ok(packet)
}

pub(crate) fn current_query_id() -> u16 {
    (((SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos() as u64) & 0xffff) as u16).max(1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn absolute_domain_encodes_one_root_terminator() {
        let packet = build_dns_query("a.", DNS_RECORD_TYPE_A, 0x1234).expect("absolute query");
        assert_eq!(packet, [0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, b'a', 0, 0, 1, 0, 1]);
    }

    #[test]
    fn absolute_domain_preserves_valid_label_and_name_boundaries() {
        let name = format!("{}.{}.{}.{}.", "a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61));
        let packet = build_dns_query(&name, DNS_RECORD_TYPE_AAAA, 7).expect("maximum valid DNS name");
        assert_eq!(packet.len(), 12 + 255 + 4);
        assert_eq!(packet[12], 63);
        assert_eq!(packet[12 + 64], 63);
        assert_eq!(packet[12 + 128], 63);
        assert_eq!(packet[12 + 192], 61);
        assert_eq!(&packet[12 + 254..], &[0, 0, 28, 0, 1]);
    }

    #[test]
    fn absolute_domain_keeps_invalid_label_rejection() {
        for name in [
            "".to_string(),
            ".".to_string(),
            "a..".to_string(),
            "a..b.".to_string(),
            format!("{}.test.", "a".repeat(64)),
        ] {
            assert_eq!(
                build_dns_query(&name, DNS_RECORD_TYPE_A, 7).expect_err("invalid label").kind(),
                io::ErrorKind::InvalidInput
            );
        }
    }
}
