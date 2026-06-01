use std::ops::Range;

use super::{ClientHelloOffsetsError, read_u16};

pub(crate) fn collect_tls_record_payload_spans(buffer: &[u8]) -> Result<Vec<Range<usize>>, ClientHelloOffsetsError> {
    if buffer.len() < 5 {
        return Err(ClientHelloOffsetsError::TruncatedRecord);
    }

    let mut spans = Vec::new();
    let mut cursor = 0usize;
    while cursor < buffer.len() {
        let header = buffer.get(cursor..cursor + 5).ok_or(ClientHelloOffsetsError::TruncatedRecord)?;
        if !is_valid_record_header(header.try_into().expect("checked header len")) {
            return Err(ClientHelloOffsetsError::InvalidRecordHeader);
        }
        let record_len = read_u16(buffer, cursor + 3).ok_or(ClientHelloOffsetsError::TruncatedRecord)?;
        let payload_start = cursor + 5;
        let payload_end = payload_start.checked_add(record_len).ok_or(ClientHelloOffsetsError::TruncatedRecord)?;
        if payload_end > buffer.len() {
            return Err(ClientHelloOffsetsError::TruncatedRecord);
        }
        spans.push(payload_start..payload_end);
        cursor = payload_end;
    }
    Ok(spans)
}

pub(crate) fn is_valid_record_header(header: [u8; 5]) -> bool {
    header[0] == 0x16 && header[1] == 0x03 && header[2] <= 0x04
}
