use std::error::Error;
use std::fmt;

const DATAGRAM_CAPSULE_TYPE: u64 = 0x00;
pub const H3_DATAGRAM_ERROR: u64 = 0x33;
pub const MAX_CONNECT_UDP_PAYLOAD_LEN: usize = 65_527;
pub const MAX_QUARTER_STREAM_ID: u64 = (1_u64 << 60) - 1;
const MAX_QUIC_VARINT: u64 = (1_u64 << 62) - 1;
const TWO_BYTE_VARINT_LIMIT: u64 = (1_u64 << 14) - 1;
const FOUR_BYTE_VARINT_LIMIT: u64 = (1_u64 << 30) - 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CapsuleErrorKind {
    Truncated,
    ValueTooLarge,
}

#[derive(Debug)]
pub struct CapsuleError {
    kind: CapsuleErrorKind,
    message: &'static str,
}

impl CapsuleError {
    pub fn kind(&self) -> CapsuleErrorKind {
        self.kind
    }

    pub fn error_code(&self) -> u64 {
        H3_DATAGRAM_ERROR
    }

    fn truncated(message: &'static str) -> Self {
        Self { kind: CapsuleErrorKind::Truncated, message }
    }

    fn value_too_large(message: &'static str) -> Self {
        Self { kind: CapsuleErrorKind::ValueTooLarge, message }
    }
}

impl fmt::Display for CapsuleError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.message)
    }
}

impl Error for CapsuleError {}

pub fn encode_quic_varint(value: u64) -> Result<Vec<u8>, CapsuleError> {
    if value > MAX_QUIC_VARINT {
        return Err(CapsuleError::value_too_large("QUIC varint value exceeds 62-bit maximum"));
    }

    let mut encoded = if value <= 63 {
        vec![value as u8]
    } else if value <= TWO_BYTE_VARINT_LIMIT {
        let mut bytes = (value as u16).to_be_bytes();
        bytes[0] |= 0x40;
        bytes.to_vec()
    } else if value <= FOUR_BYTE_VARINT_LIMIT {
        let mut bytes = (value as u32).to_be_bytes();
        bytes[0] |= 0x80;
        bytes.to_vec()
    } else {
        let mut bytes = value.to_be_bytes();
        bytes[0] |= 0xc0;
        bytes.to_vec()
    };
    encoded.shrink_to_fit();
    Ok(encoded)
}

pub fn decode_quic_varint(input: &[u8]) -> Result<(u64, usize), CapsuleError> {
    let first = *input.first().ok_or_else(|| CapsuleError::truncated("missing QUIC varint"))?;
    let width = 1_usize << usize::from(first >> 6);
    if input.len() < width {
        return Err(CapsuleError::truncated("truncated QUIC varint"));
    }

    let value = match width {
        1 => u64::from(first & 0x3f),
        2 => u64::from(u16::from_be_bytes([first & 0x3f, input[1]])),
        4 => u64::from(u32::from_be_bytes([first & 0x3f, input[1], input[2], input[3]])),
        8 => u64::from_be_bytes([first & 0x3f, input[1], input[2], input[3], input[4], input[5], input[6], input[7]]),
        _ => unreachable!("QUIC varint prefix maps to 1, 2, 4, or 8 bytes"),
    };
    Ok((value, width))
}

pub fn encode_datagram_capsule(payload: &[u8]) -> Result<Vec<u8>, CapsuleError> {
    let payload_len = u64::try_from(payload.len())
        .map_err(|_| CapsuleError::value_too_large("DATAGRAM capsule payload length exceeds u64"))?;
    let mut encoded = encode_quic_varint(DATAGRAM_CAPSULE_TYPE)?;
    encoded.extend_from_slice(&encode_quic_varint(payload_len)?);
    encoded.extend_from_slice(payload);
    Ok(encoded)
}

pub fn decode_datagram_capsules(input: &[u8]) -> Result<Vec<Vec<u8>>, CapsuleError> {
    let mut cursor = 0;
    let mut payloads = Vec::new();
    while cursor < input.len() {
        let (capsule_type, type_len) = decode_quic_varint(&input[cursor..])?;
        cursor += type_len;
        let (payload_len, len_len) = decode_quic_varint(&input[cursor..])?;
        cursor += len_len;
        let payload_len = usize::try_from(payload_len)
            .map_err(|_| CapsuleError::value_too_large("DATAGRAM capsule length exceeds usize"))?;
        let payload_end = cursor
            .checked_add(payload_len)
            .ok_or_else(|| CapsuleError::value_too_large("DATAGRAM capsule length overflows cursor"))?;
        if payload_end > input.len() {
            return Err(CapsuleError::truncated("truncated DATAGRAM capsule payload"));
        }
        if capsule_type == DATAGRAM_CAPSULE_TYPE {
            payloads.push(input[cursor..payload_end].to_vec());
        }
        cursor = payload_end;
    }
    Ok(payloads)
}

/// Largest DATAGRAM capsule value kept: a context ID varint plus the largest
/// CONNECT-UDP payload. Longer ones are discarded without buffering (RFC 9298 5).
const MAX_DATAGRAM_CAPSULE_LEN: usize = 8 + MAX_CONNECT_UDP_PAYLOAD_LEN;

/// Incremental reader of a capsule stream (RFC 9297 3.2). It returns each
/// complete DATAGRAM capsule as soon as its bytes arrive, keeps only the partial
/// tail, and skips other capsule types and oversized DATAGRAM capsules as they
/// stream past, so its buffer never exceeds one maximal DATAGRAM capsule.
#[derive(Debug, Default)]
pub struct CapsuleReader {
    buffer: Vec<u8>,
    /// Bytes of a skipped capsule still to come.
    skip: u64,
}

impl CapsuleReader {
    /// Feeds the next bytes of the stream; returns the DATAGRAM payloads completed by them.
    pub fn push(&mut self, input: &[u8]) -> Vec<Vec<u8>> {
        let skipped = usize::try_from(self.skip).unwrap_or(usize::MAX).min(input.len());
        self.skip -= skipped as u64;
        self.buffer.extend_from_slice(&input[skipped..]);

        let mut payloads = Vec::new();
        let mut cursor = 0;
        while let Some((capsule_type, length, header_len)) = capsule_header(&self.buffer[cursor..]) {
            let value_start = cursor + header_len;
            let available = (self.buffer.len() - value_start) as u64;
            let keep = capsule_type == DATAGRAM_CAPSULE_TYPE && length <= MAX_DATAGRAM_CAPSULE_LEN as u64;
            if !keep && length > available {
                self.skip = length - available;
                cursor = self.buffer.len();
                break;
            }
            if length > available {
                break;
            }
            // Infallible: length <= available, which is a buffer length.
            let value_end = value_start + length as usize;
            if keep {
                payloads.push(self.buffer[value_start..value_end].to_vec());
            }
            cursor = value_end;
        }
        self.buffer.drain(..cursor);
        payloads
    }
}

/// The type, length and header size of the capsule at the start of `input`, or
/// `None` while the header is incomplete.
fn capsule_header(input: &[u8]) -> Option<(u64, u64, usize)> {
    let (capsule_type, type_len) = decode_quic_varint(input).ok()?;
    let (length, length_len) = decode_quic_varint(&input[type_len..]).ok()?;
    Some((capsule_type, length, type_len + length_len))
}

pub fn encode_http_datagram(quarter_stream_id: u64, payload: &[u8]) -> Result<Vec<u8>, CapsuleError> {
    if quarter_stream_id > MAX_QUARTER_STREAM_ID {
        return Err(CapsuleError::value_too_large("HTTP Datagram quarter stream ID exceeds 60-bit maximum"));
    }
    let mut encoded = encode_quic_varint(quarter_stream_id)?;
    encoded.extend_from_slice(payload);
    Ok(encoded)
}

pub fn decode_http_datagram(input: &[u8]) -> Result<(u64, Vec<u8>), CapsuleError> {
    let (quarter_stream_id, prefix_len) = decode_quic_varint(input)?;
    if quarter_stream_id > MAX_QUARTER_STREAM_ID {
        return Err(CapsuleError::value_too_large("HTTP Datagram quarter stream ID exceeds 60-bit maximum"));
    }
    Ok((quarter_stream_id, input[prefix_len..].to_vec()))
}

pub fn encode_connect_udp_payload(context_id: u64, payload: &[u8]) -> Result<Vec<u8>, CapsuleError> {
    if payload.len() > MAX_CONNECT_UDP_PAYLOAD_LEN {
        return Err(CapsuleError::value_too_large("CONNECT-UDP payload exceeds RFC 9298 limit"));
    }
    let mut encoded = encode_quic_varint(context_id)?;
    encoded.extend_from_slice(payload);
    Ok(encoded)
}

pub fn decode_connect_udp_payload(input: &[u8]) -> Result<Option<(u64, Vec<u8>)>, CapsuleError> {
    let (context_id, context_len) = decode_quic_varint(input)?;
    let payload = &input[context_len..];
    if payload.len() > MAX_CONNECT_UDP_PAYLOAD_LEN {
        return Err(CapsuleError::value_too_large("CONNECT-UDP payload exceeds RFC 9298 limit"));
    }
    if context_id != 0 {
        return Ok(None);
    }
    Ok(Some((context_id, payload.to_vec())))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quic_varint_golden_vectors_round_trip() {
        let cases: &[(u64, &[u8])] = &[
            (37, &[0x25]),
            (15293, &[0x7b, 0xbd]),
            (494878333, &[0x9d, 0x7f, 0x3e, 0x7d]),
            (151288809941952652, &[0xc2, 0x19, 0x7c, 0x5e, 0xff, 0x14, 0xe8, 0x8c]),
        ];

        for (value, encoded) in cases {
            assert_eq!(encode_quic_varint(*value).expect("encode"), *encoded);
            assert_eq!(decode_quic_varint(encoded).expect("decode"), (*value, encoded.len()));
        }
    }

    #[test]
    fn datagram_capsule_encodes_type_length_value() {
        let encoded = encode_datagram_capsule(&[0x00, 0x01, 0x02]).expect("encode");

        assert_eq!(encoded, [0x00, 0x03, 0x00, 0x01, 0x02]);
    }

    #[test]
    fn datagram_capsule_length_uses_quic_varint_boundaries() {
        let payload = vec![0xaa; 64];
        let encoded = encode_datagram_capsule(&payload).expect("encode");

        assert_eq!(&encoded[..3], &[0x00, 0x40, 0x40]);
        assert_eq!(&encoded[3..], payload.as_slice());
    }

    #[test]
    fn decode_datagram_capsules_skips_unknown_types() {
        let mut encoded = Vec::new();
        encoded.extend_from_slice(&[0x21, 0x02, 0xaa, 0xbb]);
        encoded.extend_from_slice(&encode_datagram_capsule(&[0xcc]).expect("encode datagram"));

        let payloads = decode_datagram_capsules(&encoded).expect("decode");

        assert_eq!(payloads, vec![vec![0xcc]]);
    }

    #[test]
    fn truncated_capsule_payload_is_rejected() {
        let error = decode_datagram_capsules(&[0x00, 0x03, 0xaa]).expect_err("truncated capsule must fail");

        assert_eq!(error.kind(), CapsuleErrorKind::Truncated);
    }

    #[test]
    fn zero_length_datagram_capsule_round_trips() {
        let encoded = encode_datagram_capsule(&[]).expect("encode");

        assert_eq!(encoded, [0x00, 0x00]);
        assert_eq!(decode_datagram_capsules(&encoded).expect("decode"), vec![Vec::<u8>::new()]);
    }

    #[test]
    fn capsule_reader_delivers_complete_capsules_before_a_partial_tail() {
        let first = encode_datagram_capsule(&[0x00, 0x01]).expect("encode");
        let second = encode_datagram_capsule(&[0x00, 0x02, 0x03]).expect("encode");
        let mut reader = CapsuleReader::default();

        assert_eq!(reader.push(&[first.clone(), second[..2].to_vec()].concat()), vec![vec![0x00, 0x01]]);
        assert_eq!(reader.push(&second[2..]), vec![vec![0x00, 0x02, 0x03]]);
        assert!(reader.buffer.is_empty());
    }

    #[test]
    fn capsule_reader_skips_huge_capsules_without_buffering_them() {
        let mut reader = CapsuleReader::default();
        // An unknown type and an oversized DATAGRAM, both announcing more than they send.
        let mut stream = vec![0x21];
        stream.extend(encode_quic_varint(1 << 40).expect("length"));
        stream.extend([0xaa; 4096]);

        assert!(reader.push(&stream).is_empty());
        assert!(reader.buffer.is_empty());
        assert_eq!(reader.skip, (1 << 40) - 4096);

        let mut reader = CapsuleReader::default();
        let oversized = (MAX_DATAGRAM_CAPSULE_LEN + 1) as u64;
        let mut stream = vec![0x00];
        stream.extend(encode_quic_varint(oversized).expect("length"));
        stream.extend(vec![0xbb; MAX_DATAGRAM_CAPSULE_LEN + 1]);
        stream.extend(encode_datagram_capsule(&[0x00, 0x07]).expect("encode"));

        assert_eq!(reader.push(&stream[..1000]), Vec::<Vec<u8>>::new());
        assert!(reader.buffer.is_empty());
        assert_eq!(reader.push(&stream[1000..]), vec![vec![0x00, 0x07]]);
    }

    #[test]
    fn http_datagram_encodes_quarter_stream_id_prefix() {
        let encoded = encode_http_datagram(17, &[0xaa, 0xbb]).expect("encode");

        assert_eq!(encoded, [0x11, 0xaa, 0xbb]);
        assert_eq!(decode_http_datagram(&encoded).expect("decode"), (17, vec![0xaa, 0xbb]));
    }

    #[test]
    fn http_datagram_rejects_oversized_quarter_stream_id() {
        let error = encode_http_datagram(MAX_QUARTER_STREAM_ID + 1, &[]).expect_err("oversized id must fail");

        assert_eq!(error.error_code(), H3_DATAGRAM_ERROR);
    }

    #[test]
    fn truncated_http_datagram_quarter_stream_id_maps_to_h3_datagram_error() {
        let error = decode_http_datagram(&[0x40]).expect_err("truncated quarter stream id must fail");

        assert_eq!(error.error_code(), H3_DATAGRAM_ERROR);
    }

    #[test]
    fn connect_udp_payload_encodes_context_id_zero_as_varint() {
        let encoded = encode_connect_udp_payload(0, &[0x01, 0x02]).expect("encode");

        assert_eq!(encoded, [0x00, 0x01, 0x02]);
        assert_eq!(decode_connect_udp_payload(&encoded).expect("decode"), Some((0, vec![0x01, 0x02])));
    }

    #[test]
    fn connect_udp_payload_supports_multibyte_context_ids() {
        let encoded = encode_connect_udp_payload(64, &[0xaa]).expect("encode");

        assert_eq!(encoded, [0x40, 0x40, 0xaa]);
        assert_eq!(decode_quic_varint(&encoded).expect("decode context"), (64, 2));
        assert_eq!(decode_connect_udp_payload(&encoded).expect("decode payload"), None);
    }

    #[test]
    fn connect_udp_payload_unknown_context_is_dropped() {
        let decoded = decode_connect_udp_payload(&[0x01, 0xaa]).expect("decode");

        assert!(decoded.is_none());
    }

    #[test]
    fn connect_udp_payload_rejects_payloads_above_rfc_limit() {
        let payload = vec![0u8; MAX_CONNECT_UDP_PAYLOAD_LEN + 1];
        let error = encode_connect_udp_payload(0, &payload).expect_err("oversized UDP payload must fail");

        assert_eq!(error.kind(), CapsuleErrorKind::ValueTooLarge);
    }
}
