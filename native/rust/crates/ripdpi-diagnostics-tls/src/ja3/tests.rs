use std::io::{Read, Write};

use super::grease::is_grease;
use super::{RecordingStream, compute_ja3};

#[test]
fn is_grease_filters_grease_values() {
    assert!(is_grease(0x0a0a));
    assert!(is_grease(0x1a1a));
    assert!(is_grease(0x2a2a));
    assert!(is_grease(0x3a3a));
    assert!(is_grease(0x4a4a));
    assert!(is_grease(0x5a5a));
    assert!(is_grease(0x6a6a));
    assert!(is_grease(0x7a7a));
    assert!(is_grease(0x8a8a));
    assert!(is_grease(0x9a9a));
    assert!(is_grease(0xaaaa));
    assert!(is_grease(0xbaba));
    assert!(is_grease(0xcaca));
    assert!(is_grease(0xdada));
    assert!(is_grease(0xeaea));
    assert!(is_grease(0xfafa));
}

#[test]
fn is_grease_rejects_non_grease() {
    assert!(!is_grease(0x0001));
    assert!(!is_grease(0x1301));
    assert!(!is_grease(0x0a0b)); // hi != lo
    assert!(!is_grease(0x0b0b)); // (0x0b & 0x0f) == 0x0b, not 0x0a
    assert!(!is_grease(0x0000));
    assert!(!is_grease(0xffff));
}

/// Build a minimal valid TLS ClientHello for testing.
fn build_test_client_hello(version: u16, cipher_suites: &[u16], extensions: &[(u16, Vec<u8>)]) -> Vec<u8> {
    let mut hello_body = Vec::new();

    // Version
    hello_body.extend_from_slice(&version.to_be_bytes());

    // Random (32 zero bytes)
    hello_body.extend_from_slice(&[0u8; 32]);

    // Session ID (empty)
    hello_body.push(0x00);

    // Cipher suites
    let cs_len = (cipher_suites.len() * 2) as u16;
    hello_body.extend_from_slice(&cs_len.to_be_bytes());
    for suite in cipher_suites {
        hello_body.extend_from_slice(&suite.to_be_bytes());
    }

    // Compression methods (just null)
    hello_body.push(0x01); // length
    hello_body.push(0x00); // null compression

    // Extensions
    if !extensions.is_empty() {
        let mut ext_bytes = Vec::new();
        for (ext_type, ext_data) in extensions {
            ext_bytes.extend_from_slice(&ext_type.to_be_bytes());
            ext_bytes.extend_from_slice(&(ext_data.len() as u16).to_be_bytes());
            ext_bytes.extend_from_slice(ext_data);
        }
        hello_body.extend_from_slice(&(ext_bytes.len() as u16).to_be_bytes());
        hello_body.extend_from_slice(&ext_bytes);
    }

    // Handshake header
    let mut handshake = Vec::new();
    handshake.push(0x01); // ClientHello
    let hs_len = hello_body.len() as u32;
    handshake.push((hs_len >> 16) as u8);
    handshake.push((hs_len >> 8) as u8);
    handshake.push(hs_len as u8);
    handshake.extend_from_slice(&hello_body);

    // TLS record header
    let mut record = Vec::new();
    record.push(0x16); // handshake
    record.extend_from_slice(&[0x03, 0x01]); // TLS 1.0 record version
    record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
    record.extend_from_slice(&handshake);

    record
}

#[test]
fn compute_ja3_minimal_client_hello() {
    // TLS 1.2 (0x0303) with two cipher suites, no extensions
    let data = build_test_client_hello(0x0303, &[0x1301, 0x1302], &[]);
    let ja3 = compute_ja3(&data);
    assert!(ja3.is_some(), "should parse a valid ClientHello");

    let hash = ja3.unwrap();
    assert_eq!(hash.len(), 32, "MD5 hex should be 32 chars");

    // The JA3 string is "771,4865-4866,,,"
    // version=771 (0x0303), suites=4865-4866, no ext/groups/formats
    let expected_ja3_string = "771,4865-4866,,,";
    let expected_hash = format!("{:x}", md5::compute(expected_ja3_string.as_bytes()));
    assert_eq!(hash, expected_hash);
}

#[test]
fn compute_ja3_with_extensions_and_groups() {
    // Build supported_groups extension data: length(2) + groups
    let mut groups_ext = Vec::new();
    let groups: &[u16] = &[0x0017, 0x0018]; // secp256r1, secp384r1
    groups_ext.extend_from_slice(&((groups.len() * 2) as u16).to_be_bytes());
    for g in groups {
        groups_ext.extend_from_slice(&g.to_be_bytes());
    }

    // Build ec_point_formats extension data: length(1) + formats
    let mut formats_ext = Vec::new();
    let formats: &[u8] = &[0x00, 0x01]; // uncompressed, ansiX962_compressed_prime
    formats_ext.push(formats.len() as u8);
    formats_ext.extend_from_slice(formats);

    let extensions = vec![
        (0x0000_u16, vec![]),      // server_name (empty for test)
        (0x000a_u16, groups_ext),  // supported_groups
        (0x000b_u16, formats_ext), // ec_point_formats
        (0x0023_u16, vec![]),      // session_ticket
    ];

    let data = build_test_client_hello(0x0303, &[0x1301, 0xc02c], &extensions);
    let ja3 = compute_ja3(&data).expect("should parse");

    // JA3 string: "771,4865-49196,0-10-11-35,23-24,0-1"
    let expected_ja3_string = "771,4865-49196,0-10-11-35,23-24,0-1";
    let expected_hash = format!("{:x}", md5::compute(expected_ja3_string.as_bytes()));
    assert_eq!(ja3, expected_hash);
}

#[test]
fn compute_ja3_filters_grease_from_all_fields() {
    // Build supported_groups with a GREASE value mixed in
    let mut groups_ext = Vec::new();
    let groups: &[u16] = &[0x2a2a, 0x0017]; // GREASE + secp256r1
    groups_ext.extend_from_slice(&((groups.len() * 2) as u16).to_be_bytes());
    for g in groups {
        groups_ext.extend_from_slice(&g.to_be_bytes());
    }

    let extensions = vec![
        (0x0a0a_u16, vec![]),     // GREASE extension (should be filtered)
        (0x000a_u16, groups_ext), // supported_groups
    ];

    // Include a GREASE cipher suite
    let data = build_test_client_hello(0x0303, &[0x1a1a, 0x1301], &extensions);
    let ja3 = compute_ja3(&data).expect("should parse");

    // GREASE values should all be filtered:
    // version=771, suites=4865 (0x1a1a filtered), ext=10 (0x0a0a filtered),
    // groups=23 (0x2a2a filtered), formats=
    let expected_ja3_string = "771,4865,10,23,";
    let expected_hash = format!("{:x}", md5::compute(expected_ja3_string.as_bytes()));
    assert_eq!(ja3, expected_hash);
}

#[test]
fn compute_ja3_returns_none_for_invalid_data() {
    assert!(compute_ja3(&[]).is_none());
    assert!(compute_ja3(&[0x17, 0x03, 0x01]).is_none()); // wrong content type
    assert!(compute_ja3(&[0x16]).is_none()); // truncated
}

#[test]
fn recording_stream_captures_writes() {
    let mut inner = Vec::new();
    {
        let mut recording = RecordingStream::new(&mut inner);
        recording.write_all(b"hello ").unwrap();
        recording.write_all(b"world").unwrap();
        let (_, recorded) = recording.into_parts();
        assert_eq!(recorded, b"hello world");
    }
    assert_eq!(inner, b"hello world");
}

#[test]
fn recording_stream_delegates_reads() {
    let data: &[u8] = b"test data";
    let mut recording = RecordingStream::new(data);
    let mut buf = [0u8; 9];
    let n = recording.read(&mut buf).unwrap();
    assert_eq!(n, 9);
    assert_eq!(&buf, b"test data");
    // Reads should not be recorded
    assert!(recording.recorded_writes().is_empty());
}
