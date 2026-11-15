use super::MAX_CLASSIFIER_TLS_BYTES;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TlsAlertPrefixStatus {
    Complete,
    Incomplete,
    Invalid,
}

/// Plaintext alert messages from complete consecutive alert records.
/// `record_bytes` counts complete records, including headers. An incomplete record contributes no messages.
#[derive(Debug)]
pub struct TlsAlertPrefix {
    pub messages: Vec<[u8; 2]>,
    pub record_bytes: usize,
    pub status: TlsAlertPrefixStatus,
}

/// Read at most 64 KiB of alert records. TLS 1.2 messages may cross record boundaries.
/// A negotiated TLS version, rather than the record version, decides if that framing is allowed.
pub fn parse_tls_alert_prefix(buffer: &[u8]) -> Option<TlsAlertPrefix> {
    if buffer.first() != Some(&0x15) {
        return None;
    }
    let mut offset = 0;
    let mut level = None;
    let mut messages = Vec::new();
    let status = loop {
        if offset == buffer.len() {
            break if level.is_some() { TlsAlertPrefixStatus::Incomplete } else { TlsAlertPrefixStatus::Complete };
        }
        if buffer[offset] != 0x15 {
            break if level.is_some() { TlsAlertPrefixStatus::Invalid } else { TlsAlertPrefixStatus::Complete };
        }
        let Some(header) = buffer.get(offset..offset + 5) else {
            break TlsAlertPrefixStatus::Incomplete;
        };
        let length = usize::from(u16::from_be_bytes([header[3], header[4]]));
        if header[1] != 3 || header[2] > 4 || !(1..=16_384).contains(&length) {
            break TlsAlertPrefixStatus::Invalid;
        }
        let end = offset + 5 + length;
        if end > MAX_CLASSIFIER_TLS_BYTES || end > buffer.len() {
            break TlsAlertPrefixStatus::Incomplete;
        }
        for &byte in &buffer[offset + 5..end] {
            if let Some(previous) = level.take() {
                messages.push([previous, byte]);
            } else {
                level = Some(byte);
            }
        }
        offset = end;
    };
    Some(TlsAlertPrefix { messages, record_bytes: offset, status })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn alert_messages_follow_record_fragmentation_without_reading_an_incomplete_record() {
        let prefix = parse_tls_alert_prefix(b"\x15\x03\x03\0\x01\x01\x15\x03\x03\0\x01\x70\x16").expect("alert prefix");
        assert_eq!(prefix.messages, [[1, 112]]);
        assert_eq!(prefix.record_bytes, 12);
        assert_eq!(prefix.status, TlsAlertPrefixStatus::Complete);
        let partial = parse_tls_alert_prefix(b"\x15\x03\x03\0\x08\x02\x28").expect("partial record");
        assert!(partial.messages.is_empty());
        assert_eq!(partial.record_bytes, 0);
        assert_eq!(partial.status, TlsAlertPrefixStatus::Incomplete);
    }

    #[test]
    fn alert_prefix_work_and_storage_are_bounded() {
        let records = b"\x15\x03\x03\0\x02\x01\x70".repeat(10_000);
        let prefix = parse_tls_alert_prefix(&records).expect("alert prefix");
        assert!(prefix.record_bytes <= 65_536);
        assert!(prefix.messages.len() < 10_000);
        assert_eq!(prefix.status, TlsAlertPrefixStatus::Incomplete);
    }
}
