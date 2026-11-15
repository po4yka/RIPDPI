use super::*;

#[test]
fn a_field_section_below_the_advertised_limit_is_accepted() {
    // QPACK :status=200 plus x:<16000 bytes> has decoded size 16075, below 16384.
    // Literal value length 16000 uses a 7-bit prefix and base-128 continuation (RFC 9204).
    static RESPONSE: [u8; 16011] = {
        let mut bytes = [b'x'; 16011];
        let prefix = [1, 0x7e, 0x88, 0, 0, 0xd9, 0x21, b'x', 0x7f, 0x81, 0x7c];
        let mut i = 0;
        while i < prefix.len() {
            bytes[i] = prefix[i];
            i += 1;
        }
        bytes
    };
    let server = Server::start(Reply::Raw(&RESPONSE), b"h3");
    let facts = run(&server, &server.config());
    server.finish();
    assert_eq!(facts.status, "COMPLETE", "{facts:?}");
    assert!(facts.http3_validated && facts.body_complete, "{facts:?}");
}

#[test]
fn oversized_known_frames_fail_before_the_payload_arrives() {
    // HEADERS length 65537: RFC 9114 Section 7.1 and RFC 9000 Section 16.
    let server = Server::start(Reply::RawOpen(&[1, 0x80, 1, 0, 1]), b"h3");
    let facts = run(&server, &server.config());
    server.finish();
    assert_eq!(facts.status, "FAILED", "{facts:?}");
    assert!(!facts.http3_validated && !facts.body_complete, "{facts:?}");
}

#[test]
fn a_large_unknown_frame_is_skipped_before_the_response() {
    // Reserved frame 0x21, length 131072, then QPACK static :status=200.
    static RESPONSE: [u8; 131082] = {
        let mut bytes = [b'x'; 131082];
        let prefix = [0x21, 0x80, 2, 0, 0];
        let suffix = [1, 3, 0, 0, 0xd9];
        let mut i = 0;
        while i < 5 {
            bytes[i] = prefix[i];
            bytes[131077 + i] = suffix[i];
            i += 1;
        }
        bytes
    };
    let server = Server::start(Reply::Raw(&RESPONSE), b"h3");
    let facts = run(&server, &server.config());
    server.finish();
    assert_eq!(facts.status, "COMPLETE", "{facts:?}");
    assert!(facts.http3_validated && facts.body_complete, "{facts:?}");
    assert_eq!(facts.response_bytes, 0);
}

#[test]
fn data_frames_larger_than_the_control_limit_still_stream() {
    let server = Server::start(Reply::Body(200, 131072), b"h3");
    let mut config = server.config();
    config.max_response_bytes = 262144;
    let facts = run(&server, &config);
    server.finish();
    assert_eq!(facts.status, "COMPLETE", "{facts:?}");
    assert!(facts.body_complete, "{facts:?}");
    assert_eq!(facts.response_bytes, 131072);
}

#[test]
fn fin_cannot_hide_an_incomplete_unknown_frame() {
    let server = Server::start(Reply::Raw(&[1, 3, 0, 0, 0xd9, 0x21, 5, b'x']), b"h3");
    let facts = run(&server, &server.config());
    server.finish();
    assert_eq!(facts.status, "FAILED", "{facts:?}");
    assert!(!facts.body_complete, "{facts:?}");
}

#[test]
fn fin_before_any_data_payload_is_a_typed_failure() {
    let server = Server::start(Reply::Raw(&[1, 3, 0, 0, 0xd9, 0, 5]), b"h3");
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| run(&server, &server.config())));
    server.finish();
    let facts = result.expect("a truncated DATA frame must return evidence without a panic");
    assert_eq!(facts.status, "FAILED", "{facts:?}");
    assert!(!facts.body_complete, "{facts:?}");
}
