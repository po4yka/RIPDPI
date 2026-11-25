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
    assert!(!is_grease(0x0a0b));
    assert!(!is_grease(0x0b0b));
    assert!(!is_grease(0x0000));
    assert!(!is_grease(0xffff));
}

fn build_test_client_hello(version: u16, cipher_suites: &[u16], extensions: &[(u16, Vec<u8>)]) -> Vec<u8> {
    let mut hello_body = Vec::new();

    hello_body.extend_from_slice(&version.to_be_bytes());

    hello_body.extend_from_slice(&[0u8; 32]);

    hello_body.push(0x00);

    let cs_len = (cipher_suites.len() * 2) as u16;
    hello_body.extend_from_slice(&cs_len.to_be_bytes());
    for suite in cipher_suites {
        hello_body.extend_from_slice(&suite.to_be_bytes());
    }

    hello_body.push(0x01);
    hello_body.push(0x00);

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

    let mut handshake = Vec::new();
    handshake.push(0x01);
    let hs_len = hello_body.len() as u32;
    handshake.push((hs_len >> 16) as u8);
    handshake.push((hs_len >> 8) as u8);
    handshake.push(hs_len as u8);
    handshake.extend_from_slice(&hello_body);

    handshake_record(&handshake)
}

fn handshake_record(payload: &[u8]) -> Vec<u8> {
    let mut record = vec![0x16, 0x03, 0x01];
    record.extend_from_slice(&(payload.len() as u16).to_be_bytes());
    record.extend_from_slice(payload);
    record
}

#[test]
fn compute_ja3_minimal_client_hello() {
    let data = build_test_client_hello(0x0303, &[0x1301, 0x1302], &[]);
    let ja3 = compute_ja3(&data);
    assert!(ja3.is_some(), "should parse a valid ClientHello");

    let hash = ja3.unwrap();
    assert_eq!(hash.len(), 32, "MD5 hex should be 32 chars");

    let expected_ja3_string = "771,4865-4866,,,";
    let expected_hash = format!("{:x}", md5::compute(expected_ja3_string.as_bytes()));
    assert_eq!(hash, expected_hash);
}

#[test]
fn compute_ja3_with_extensions_and_groups() {
    let mut groups_ext = Vec::new();
    let groups: &[u16] = &[0x0017, 0x0018];
    groups_ext.extend_from_slice(&((groups.len() * 2) as u16).to_be_bytes());
    for g in groups {
        groups_ext.extend_from_slice(&g.to_be_bytes());
    }

    let mut formats_ext = Vec::new();
    let formats: &[u8] = &[0x00, 0x01];
    formats_ext.push(formats.len() as u8);
    formats_ext.extend_from_slice(formats);

    let extensions =
        vec![(0x0000_u16, vec![]), (0x000a_u16, groups_ext), (0x000b_u16, formats_ext), (0x0023_u16, vec![])];

    let data = build_test_client_hello(0x0303, &[0x1301, 0xc02c], &extensions);
    let ja3 = compute_ja3(&data).expect("should parse");

    let expected_ja3_string = "771,4865-49196,0-10-11-35,23-24,0-1";
    let expected_hash = format!("{:x}", md5::compute(expected_ja3_string.as_bytes()));
    assert_eq!(ja3, expected_hash);
}

#[test]
fn compute_ja3_filters_grease_from_all_fields() {
    let mut groups_ext = Vec::new();
    let groups: &[u16] = &[0x2a2a, 0x0017];
    groups_ext.extend_from_slice(&((groups.len() * 2) as u16).to_be_bytes());
    for g in groups {
        groups_ext.extend_from_slice(&g.to_be_bytes());
    }

    let extensions = vec![(0x0a0a_u16, vec![]), (0x000a_u16, groups_ext)];

    let data = build_test_client_hello(0x0303, &[0x1a1a, 0x1301], &extensions);
    let ja3 = compute_ja3(&data).expect("should parse");

    let expected_ja3_string = "771,4865,10,23,";
    let expected_hash = format!("{:x}", md5::compute(expected_ja3_string.as_bytes()));
    assert_eq!(ja3, expected_hash);
}

#[test]
fn compute_ja3_returns_none_for_invalid_data() {
    assert!(compute_ja3(&[]).is_none());
    assert!(compute_ja3(&[0x17, 0x03, 0x01]).is_none());
    assert!(compute_ja3(&[0x16]).is_none());
}

#[test]
fn compute_ja3_uses_the_first_complete_hello_across_records() {
    let record = build_test_client_hello(
        0x0303,
        &[0xc02f, 0x0a0a, 0x009c],
        &[(10, vec![0, 6, 0, 29, 0x7a, 0x7a, 0, 23]), (0xfafa, vec![]), (11, vec![2, 0, 1])],
    );
    let expected = Some("73de4dd4aeedbdbb4d79f58c07d6934a".to_string());
    assert_eq!(compute_ja3(&record), expected);

    let handshake = &record[5..];
    for split in 1..handshake.len() {
        let mut records = handshake_record(&handshake[..split]);
        assert_eq!(compute_ja3(&records), None, "incomplete at split {split}");
        records.extend_from_slice(&handshake_record(&handshake[split..]));
        assert_eq!(compute_ja3(&records), expected, "split {split}");
    }

    let mut coalesced = handshake.to_vec();
    coalesced.extend_from_slice(&[0x02, 0, 0, 0]);
    let mut records = handshake_record(&coalesced);
    records.extend_from_slice(&[0x14, 3, 3, 0, 1, 1]);
    assert_eq!(compute_ja3(&records), expected);
}

#[test]
fn compute_ja3_rejects_invalid_record_and_handshake_lengths() {
    let record = build_test_client_hello(0x0303, &[0xc02f], &[(11, vec![1, 0])]);
    for offset in [4, 8] {
        let mut invalid = record.clone();
        invalid[offset] -= 1;
        assert_eq!(compute_ja3(&invalid), None, "short declared length at {offset}");
        invalid[offset] += 2;
        assert_eq!(compute_ja3(&invalid), None, "long declared length at {offset}");
    }

    let mut oversized = record[5..].to_vec();
    oversized.resize(16_385, 0);
    assert_eq!(compute_ja3(&handshake_record(&oversized)), None);

    let mut interrupted = handshake_record(&record[5..9]);
    interrupted.extend_from_slice(&[0x14, 3, 3, 0, 1, 1]);
    interrupted.extend_from_slice(&handshake_record(&record[9..]));
    assert_eq!(compute_ja3(&interrupted), None);
}

#[test]
fn compute_ja3_rejects_invalid_nested_vector_lengths() {
    for extension in [(10, vec![0, 3, 0, 29, 0]), (11, vec![2, 0])] {
        let record = build_test_client_hello(0x0303, &[0xc02f], &[extension]);
        assert_eq!(compute_ja3(&record), None);
    }

    let mut record = build_test_client_hello(0x0303, &[0xc02f], &[(11, vec![1, 0])]);
    record[45] = 1;
    assert_eq!(compute_ja3(&record), None);

    let mut record = build_test_client_hello(0x0303, &[0xc02f], &[(11, vec![1, 0])]);
    record[51] = 5;
    assert_eq!(compute_ja3(&record), None);
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
    assert!(recording.recorded_writes().is_empty());
}
