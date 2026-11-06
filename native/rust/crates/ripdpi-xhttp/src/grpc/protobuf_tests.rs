use super::*;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

fn frame(message: &[u8]) -> Vec<u8> {
    let mut frame = vec![0];
    frame.extend_from_slice(&(message.len() as u32).to_be_bytes());
    frame.extend_from_slice(message);
    frame
}

#[test]
fn protobuf_hunk_accepts_unknown_fields_and_last_data_value() {
    let mut decoder = HunkDecoder::new();
    decoder.extend(&frame(b"\x10\x01\x0a\x01A\x19abcdefgh\x0a\x01B\x25abcd\x2a\x01x\x33\x38\x01\x34"));
    assert_eq!(decoder.next_hunk().expect("valid Protobuf"), Some(Bytes::from_static(b"B")));
    assert!(decoder.is_empty());
}

#[test]
fn protobuf_hunk_accepts_default_empty_message() {
    let mut decoder = HunkDecoder::new();
    decoder.extend(&[0, 0, 0, 0, 0]);
    assert_eq!(decoder.next_hunk().expect("default message"), Some(Bytes::new()));
}

#[test]
fn protobuf_hunk_rejects_nonzero_compression_flags_before_body() {
    for flag in [1, 2, 255] {
        let mut decoder = HunkDecoder::new();
        decoder.extend(&[flag, 0, 0, 0, 3]);
        assert!(decoder.next_hunk().is_err(), "unsupported flag {flag}");
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: cancellation during write_all, shutdown, or read_to_end
/// drops test-owned endpoints and buffers; no partial I/O state survives.
async fn protobuf_transport_reports_invalid_and_truncated_frames() {
    for bytes in [vec![1, 0, 0, 0, 0], vec![0, 0], vec![0, 0, 0, 3, 0x0a]] {
        let (mut transport, mut halves) = GrpcTransport::new(GrpcTransportConfig::new("example.invalid", "test"));
        halves.downstream.write_all(&bytes).await.expect("feed wire");
        halves.downstream.shutdown().await.expect("wire EOF");
        let mut received = Vec::new();
        let result = transport.read_to_end(&mut received).await;
        assert!(result.is_err(), "malformed wire must not appear as clean EOF: {bytes:?}");
    }
}

#[test]
fn protobuf_hunk_rejects_malformed_messages() {
    for message in [
        &b"\x00"[..],
        &b"\x0f"[..],
        &b"\x0a\xff\xff\xff\xff\xff\xff\xff\xff\xff\x02"[..],
        &b"\x10\xff\xff\xff\xff\xff\xff\xff\xff\xff\x02"[..],
        &b"\x0a\x03x"[..],
    ] {
        let mut decoder = HunkDecoder::new();
        decoder.extend(&frame(message));
        assert!(decoder.next_hunk().is_err(), "invalid Hunk {message:?}");
    }
}

#[test]
fn protobuf_hunk_enforces_encoded_size_limit_without_partial_output() {
    // One tag plus a three-byte length varint leaves this much payload room.
    let payload = vec![0x5a; MAX_HUNK_LEN - 4];
    let wire = encode_hunk_to_bytes(&payload).expect("maximum message");
    assert_eq!(&wire[..5], &[0, 0, 0x10, 0, 0]);
    let mut decoder = HunkDecoder::new();
    decoder.extend(&wire);
    assert_eq!(decoder.next_hunk().expect("decode").expect("payload").as_ref(), payload);
    for length in [MAX_HUNK_LEN - 3, MAX_HUNK_LEN + 1] {
        let mut out = BytesMut::from(&b"prefix"[..]);
        assert!(matches!(encode_hunk(&vec![0; length], &mut out), Err(GrpcFramingError::MessageTooLarge(_))));
        assert_eq!(out.as_ref(), b"prefix");
    }
    assert_eq!(encode_hunk_to_bytes(&[]).expect("empty").as_ref(), &[0; 5]);
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: cancellation during write_all, shutdown, or read_to_end
/// drops test-owned endpoints and buffers; no partial I/O state survives.
async fn protobuf_transport_preserves_payload_before_errors_and_skips_empty_hunks() {
    for (suffix, expected_error) in [
        (vec![], None),
        (vec![0, 0], Some(io::ErrorKind::UnexpectedEof)),
        (vec![0, 0, 0, 0, 3, 0x0a], Some(io::ErrorKind::UnexpectedEof)),
        (vec![1, 0, 0, 0, 0], Some(io::ErrorKind::InvalidData)),
        (frame(b"\x00"), Some(io::ErrorKind::InvalidData)),
    ] {
        let (mut transport, mut halves) = GrpcTransport::new(GrpcTransportConfig::new("example.invalid", "test"));
        let mut wire = frame(b"\x0a\x01A");
        wire.extend_from_slice(&frame(&[]));
        wire.extend_from_slice(&frame(b"\x10\x01"));
        wire.extend_from_slice(&frame(b"\x0a\x01B"));
        wire.extend_from_slice(&suffix);
        halves.downstream.write_all(&wire).await.expect("wire");
        halves.downstream.shutdown().await.expect("EOF");
        let mut received = Vec::new();
        let result = transport.read_to_end(&mut received).await;
        assert_eq!(received, b"AB");
        assert_eq!(result.err().map(|e| e.kind()), expected_error);
    }
}
