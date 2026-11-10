use std::io;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};

use super::{
    ResponseHeaderStream, encode_request, encode_response, parse_request_header, parse_response_header, read_response,
};

// Expected results were observed with Xray-core 12ee51e4bb1d02ece4ef4b7114efa2bcdc130995
// encoding.Addons and google.golang.org/protobuf/proto.Unmarshal, not this decoder.
const CASES: &[(&str, &str, bool)] = &[
    ("empty", "", true),
    ("vision", "0a1078746c732d727072782d766973696f6e", true),
    ("seed", "1201ff", true),
    ("unknown_length", "1a01ff", true),
    ("unknown_varint", "1801", true),
    ("unknown_fixed32", "1d01020304", true),
    ("unknown_fixed64", "190102030405060708", true),
    ("flow_wrong_varint", "0801", true),
    ("flow_wrong_fixed32", "0d01020304", true),
    ("seed_wrong_varint", "1001", true),
    ("duplicate_flow", "0a01610a0162", true),
    ("duplicate_empty_last", "0a01610a00", true),
    ("unknown_group", "1b20011c", true),
    ("nested_unknown_group", "1b23241c", true),
    ("group_contains_invalid_flow_utf8", "1b0a01ff1c", true),
    ("max_field_number", "f8ffffff0f00", true),
    ("noncanonical_varint_one", "1881808080808080808000", true),
    ("varint64_max", "18ffffffffffffffffff01", true),
    ("noncanonical_tag", "980001", true),
    ("noncanonical_length", "1a810000", true),
    ("field_zero", "00", false),
    ("invalid_wire_type_six", "1e", false),
    ("invalid_wire_type_seven", "1f", false),
    ("truncated_fixed32", "1d0102", false),
    ("truncated_flow", "0a1078", false),
    ("invalid_utf8", "0a01ff", false),
    ("invalid_utf8_before_valid", "0a01ff0a0162", false),
    ("truncated_unknown", "1a0201", false),
    ("varint_overflow", "1880808080808080808002", false),
    ("above_max_field_number", "808080801000", false),
    ("field_number_overflow", "80808080800100", false),
    ("mismatched_group_end", "1b24", false),
    ("top_level_end_group", "1c", false),
    ("unterminated_group", "1b", false),
    ("truncated_fixed64", "190102", false),
];

fn response(addons: &[u8]) -> Vec<u8> {
    let mut bytes = encode_response(addons).expect("bounded test addons");
    bytes.extend_from_slice(b"payload");
    bytes
}

#[test]
fn slice_decoders_match_pinned_xray_addon_vectors() {
    for &(name, encoded, accepted) in CASES {
        let addons = hex::decode(encoded).expect("oracle bytes");
        let request = encode_request(&[7; 16], &addons, "127.0.0.1:80").expect("request");
        assert_eq!(parse_request_header(&request).is_ok(), accepted, "request {name}");
        assert_eq!(parse_response_header(&response(&addons)).is_ok(), accepted, "response {name}");
    }
}

/// # Cancel safety:
/// cancel-safe: each eager read owns a fresh input slice and no cancelled read is retried.
#[tokio::test(flavor = "current_thread")]
async fn eager_response_matches_pinned_xray_addon_vectors() {
    for &(name, encoded, accepted) in CASES {
        let bytes = response(&hex::decode(encoded).expect("oracle bytes"));
        let mut input = bytes.as_slice();
        assert_eq!(read_response(&mut input).await.is_ok(), accepted, "{name}");
        if accepted {
            assert_eq!(input, b"payload", "{name}");
        }
    }
}

/// # Cancel safety:
/// cancel-safe: every lazy stream owns its input and its partial parser state.
#[tokio::test(flavor = "current_thread")]
async fn lazy_response_matches_pinned_xray_addon_vectors() {
    for &(name, encoded, accepted) in CASES {
        let bytes = response(&hex::decode(encoded).expect("oracle bytes"));
        let mut stream = ResponseHeaderStream::new(bytes.as_slice());
        let mut payload = Vec::new();
        let result = stream.read_to_end(&mut payload).await;
        assert_eq!(result.is_ok(), accepted, "{name}");
        if accepted {
            assert_eq!(payload, b"payload", "{name}");
        } else {
            assert!(payload.is_empty(), "malformed {name} must not deliver payload");
            assert_eq!(result.expect_err("malformed addons").kind(), io::ErrorKind::InvalidData);
            let mut next = [0];
            assert_eq!(stream.read(&mut next).await.expect_err("terminal error").kind(), io::ErrorKind::BrokenPipe);
        }
    }
}

/// # Cancel safety:
/// cancel-safe: every cancelled read retains its state in the same lazy stream; both pipe ends are owned here.
#[tokio::test(flavor = "current_thread")]
async fn lazy_addons_resume_at_every_split_without_losing_payload() {
    let addons = hex::decode("0a1078746c732d727072782d766973696f6e1b20011c").expect("valid addons");
    let bytes = response(&addons);
    let header_len = 2 + addons.len();
    for split in 0..=header_len {
        let (client, mut peer) = tokio::io::duplex(512);
        peer.write_all(&bytes[..split]).await.expect("prefix");
        let mut stream = ResponseHeaderStream::new(client);
        let mut payload = [0; 7];
        assert!(tokio::time::timeout(Duration::from_millis(2), stream.read(&mut payload)).await.is_err());
        peer.write_all(&bytes[split..]).await.expect("remaining response");
        stream.read_exact(&mut payload).await.expect("resume read");
        assert_eq!(&payload, b"payload", "split {split}");
    }
}

/// # Cancel safety:
/// cancel-safe: the test owns each stream, and every incomplete response is discarded after its terminal error.
#[tokio::test(flavor = "current_thread")]
async fn lazy_addons_reject_each_truncated_prefix_and_accept_maximum_block() {
    let bytes = encode_response(&[0x1a, 1, 0xff]).expect("valid unknown addon");
    for split in 0..bytes.len() {
        let mut stream = ResponseHeaderStream::new(&bytes[..split]);
        let mut payload = [0];
        assert_eq!(stream.read(&mut payload).await.expect_err("truncated header").kind(), io::ErrorKind::UnexpectedEof);
    }
    let mut addons = vec![0x1a, 0xfc, 1];
    addons.extend(std::iter::repeat_n(0xaa, 252));
    let bytes = response(&addons);
    let mut stream = ResponseHeaderStream::new(bytes.as_slice());
    let mut payload = Vec::new();
    stream.read_to_end(&mut payload).await.expect("255-byte valid addon block");
    assert_eq!(payload, b"payload");
}

#[test]
fn request_decoder_retains_only_the_last_top_level_wire_flow() {
    for (encoded, expected) in [
        ("0a01610a0162", "b"),
        ("0a01610a00", ""),
        ("0a01610801", "a"),
        ("1b0a01611c", ""),
        ("1b0a01ff1c", ""),
        ("0a046e6f6e65", "none"),
        ("0a036f6666", "off"),
        ("0a03206120", " a "),
    ] {
        let addons = hex::decode(encoded).expect("protobuf bytes");
        let request = encode_request(&[7; 16], &addons, "127.0.0.1:80").expect("request");
        assert_eq!(parse_request_header(&request).expect("valid addons").flow, expected);
    }
}

/// # Cancel safety:
/// cancel-safe: all partial bytes stay in the lazy stream when the read is cancelled; every stream is owned here.
#[tokio::test(flavor = "current_thread")]
async fn malformed_addons_still_fail_after_a_cancelled_partial_read() {
    let bytes = response(&[0x0a, 0x10, b'x']);
    for split in 0..5 {
        let (client, mut peer) = tokio::io::duplex(64);
        peer.write_all(&bytes[..split]).await.expect("prefix");
        let mut stream = ResponseHeaderStream::new(client);
        let mut payload = [0; 7];
        assert!(tokio::time::timeout(Duration::from_millis(2), stream.read(&mut payload)).await.is_err());
        peer.write_all(&bytes[split..]).await.expect("remainder");
        assert_eq!(stream.read(&mut payload).await.expect_err("malformed addon").kind(), io::ErrorKind::InvalidData);
        assert_eq!(payload, [0; 7], "no body bytes before validation");
    }
}
