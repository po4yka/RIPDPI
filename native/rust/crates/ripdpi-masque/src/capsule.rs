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

/// Incremental CONNECT-UDP capsule reader. Unknown capsule types and context IDs
/// are skipped without buffering their values. A known datagram is bounded by
/// the Context ID width plus the maximum UDP payload size.
#[derive(Debug, Default)]
pub struct CapsuleReader {
    buffer: Vec<u8>,
    state: CapsuleState,
}

#[derive(Debug, Default, Clone, Copy)]
enum CapsuleState {
    #[default]
    Header,
    Context {
        length: u64,
    },
    Payload {
        remaining: usize,
    },
    Skip {
        remaining: u64,
    },
    Failed(CapsuleErrorKind),
}

impl CapsuleReader {
    /// Returns complete Context-0 DATAGRAM values, or a terminal stream error.
    pub fn push(&mut self, mut input: &[u8]) -> Result<Vec<Vec<u8>>, CapsuleError> {
        let mut payloads = Vec::new();
        loop {
            match self.state {
                CapsuleState::Header => {
                    let Some((&byte, rest)) = input.split_first() else { break };
                    self.buffer.push(byte);
                    input = rest;
                    if let Some((capsule_type, length, _)) = capsule_header(&self.buffer) {
                        self.buffer.clear();
                        self.state = if capsule_type == DATAGRAM_CAPSULE_TYPE {
                            if length == 0 {
                                return Err(
                                    self.fail(CapsuleError::truncated("DATAGRAM capsule is missing its context ID"))
                                );
                            }
                            CapsuleState::Context { length }
                        } else {
                            CapsuleState::Skip { remaining: length }
                        };
                    }
                }
                CapsuleState::Context { length } => {
                    let Some((&byte, rest)) = input.split_first() else { break };
                    self.buffer.push(byte);
                    input = rest;
                    let width = 1_usize << usize::from(self.buffer[0] >> 6);
                    if width as u64 > length {
                        return Err(self.fail(CapsuleError::truncated("truncated DATAGRAM capsule context ID")));
                    }
                    if self.buffer.len() == width {
                        let (context, _) = decode_quic_varint(&self.buffer)?;
                        let remaining = length - width as u64;
                        if context != 0 {
                            self.buffer.clear();
                            self.state = CapsuleState::Skip { remaining };
                        } else if remaining > MAX_CONNECT_UDP_PAYLOAD_LEN as u64 {
                            return Err(
                                self.fail(CapsuleError::value_too_large("CONNECT-UDP payload exceeds RFC 9298 limit"))
                            );
                        } else {
                            self.state = CapsuleState::Payload { remaining: remaining as usize };
                        }
                    }
                }
                CapsuleState::Payload { remaining: 0 } => {
                    payloads.push(std::mem::take(&mut self.buffer));
                    self.state = CapsuleState::Header;
                }
                CapsuleState::Payload { remaining } => {
                    if input.is_empty() {
                        break;
                    }
                    let consumed = input.len().min(remaining);
                    self.buffer.extend_from_slice(&input[..consumed]);
                    input = &input[consumed..];
                    self.state = CapsuleState::Payload { remaining: remaining - consumed };
                }
                CapsuleState::Skip { remaining: 0 } => self.state = CapsuleState::Header,
                CapsuleState::Skip { remaining } => {
                    if input.is_empty() {
                        break;
                    }
                    let consumed = usize::try_from(remaining).unwrap_or(usize::MAX).min(input.len());
                    input = &input[consumed..];
                    self.state = CapsuleState::Skip { remaining: remaining - consumed as u64 };
                }
                CapsuleState::Failed(kind) => {
                    return Err(CapsuleError { kind, message: "capsule stream already failed" });
                }
            }
        }
        Ok(payloads)
    }

    /// Validates a clean end of the capsule stream, including skipped values.
    pub fn finish(self) -> Result<(), CapsuleError> {
        match self.state {
            CapsuleState::Header if self.buffer.is_empty() => Ok(()),
            CapsuleState::Failed(kind) => Err(CapsuleError { kind, message: "capsule stream already failed" }),
            _ => Err(CapsuleError::truncated("truncated capsule at the end of the stream")),
        }
    }

    fn fail(&mut self, error: CapsuleError) -> CapsuleError {
        self.buffer.clear();
        self.state = CapsuleState::Failed(error.kind());
        error
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
    if context_id != 0 {
        return Ok(None);
    }
    let payload = &input[context_len..];
    if payload.len() > MAX_CONNECT_UDP_PAYLOAD_LEN {
        return Err(CapsuleError::value_too_large("CONNECT-UDP payload exceeds RFC 9298 limit"));
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
        let mut reader = CapsuleReader::default();
        assert_eq!(reader.push(&[0, 2, 0, 1, 0, 3]).expect("first capsule"), vec![vec![0, 1]]);
        assert_eq!(reader.push(&[0, 2, 3]).expect("second capsule"), vec![vec![0, 2, 3]]);
        reader.finish().expect("complete stream");
    }

    #[test]
    fn capsule_reader_skips_huge_unknown_capsules_without_buffering_them() {
        let mut reader = CapsuleReader::default();
        // Unknown capsule type 33, eight-byte varint length 2^40.
        let mut stream = vec![0x21, 0xc0, 0, 1, 0, 0, 0, 0, 0];
        stream.extend([0xaa; 4096]);
        assert!(reader.push(&stream).expect("unknown type is skipped").is_empty());
        assert!(reader.buffer.is_empty());
        assert!(reader.finish().is_err(), "clean EOF still requires the complete unknown value");
    }

    #[test]
    fn capsule_reader_skips_unknown_contexts_even_above_the_udp_limit() {
        let mut reader = CapsuleReader::default();
        // DATAGRAM length 65537, unknown Context ID 1, then 65536 opaque bytes.
        let mut stream = vec![0, 0x80, 1, 0, 1, 1];
        stream.extend(std::iter::repeat_n(0xbb, 65536));
        stream.extend_from_slice(&[0, 2, 0, 7]);
        assert!(reader.push(&stream[..1000]).expect("skip context").is_empty());
        assert!(reader.buffer.is_empty());
        assert_eq!(reader.push(&stream[1000..]).expect("next datagram"), vec![vec![0, 7]]);
        reader.finish().expect("complete stream");
    }

    #[test]
    fn capsule_reader_enforces_context_zero_udp_size_before_buffering_the_value() {
        for length_bytes in [[0x80, 0, 0xff, 0xf9], [0x80, 1, 0, 1]] {
            let mut reader = CapsuleReader::default();
            // Capsule values: Context ID 0 plus 65528 or 65536 bytes.
            let input = [vec![0], length_bytes.to_vec(), vec![0]].concat();
            assert_eq!(reader.push(&input).expect_err("oversized UDP").kind(), CapsuleErrorKind::ValueTooLarge);
            assert!(reader.buffer.is_empty());
            assert!(reader.push(&[0, 2, 0, 7]).is_err(), "a failed stream cannot resume");
        }
        let mut reader = CapsuleReader::default();
        let mut input = vec![0, 0x80, 0, 0xff, 0xf8, 0]; // value length 65528 = context plus maximum UDP
        input.extend(std::iter::repeat_n(0x61, 65527));
        let values = reader.push(&input).expect("maximum UDP is valid");
        assert_eq!(values, vec![[vec![0], vec![0x61; 65527]].concat()]);
        reader.finish().expect("complete stream");
    }

    #[test]
    fn capsule_reader_rejects_malformed_context_before_following_datagram() {
        for malformed in [&[0, 0][..], &[0, 1, 0x40][..]] {
            let mut input = malformed.to_vec();
            input.extend_from_slice(&[0, 2, 0, 7]);
            let mut reader = CapsuleReader::default();
            assert_eq!(reader.push(&input).expect_err("malformed context").kind(), CapsuleErrorKind::Truncated);
        }
    }

    #[test]
    fn capsule_reader_detects_incomplete_fin_at_each_parser_stage() {
        for incomplete in [&[0x40][..], &[0][..], &[0, 1][..], &[0, 3, 0, 1][..], &[0x21, 2, 1][..]] {
            let mut reader = CapsuleReader::default();
            assert!(reader.push(incomplete).expect("partial capsule waits for bytes").is_empty());
            assert_eq!(reader.finish().expect_err("truncated FIN").kind(), CapsuleErrorKind::Truncated);
        }
        CapsuleReader::default().finish().expect("empty stream is complete");
    }

    #[test]
    fn capsule_reader_handles_every_byte_boundary_and_nonminimal_zero_context() {
        // Two-byte capsule type and length, eight-byte Context ID zero, empty UDP.
        let input = [0x40, 0, 0x40, 8, 0xc0, 0, 0, 0, 0, 0, 0, 0];
        let mut reader = CapsuleReader::default();
        let mut values = Vec::new();
        for byte in input {
            values.extend(reader.push(&[byte]).expect("next byte"));
        }
        assert_eq!(values, vec![vec![0xc0, 0, 0, 0, 0, 0, 0, 0]]);
        reader.finish().expect("complete stream");
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
    fn oversized_unknown_context_is_not_a_context_zero_udp_error() {
        let mut value = vec![1];
        value.extend(std::iter::repeat_n(0x61, 65528));
        assert!(decode_connect_udp_payload(&value).expect("unknown context is skipped").is_none());
    }

    #[test]
    fn connect_udp_payload_rejects_payloads_above_rfc_limit() {
        let payload = vec![0u8; MAX_CONNECT_UDP_PAYLOAD_LEN + 1];
        let error = encode_connect_udp_payload(0, &payload).expect_err("oversized UDP payload must fail");

        assert_eq!(error.kind(), CapsuleErrorKind::ValueTooLarge);
    }
}
