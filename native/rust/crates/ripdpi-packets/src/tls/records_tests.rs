//! Multi-record ClientHello and strict SNI regression tests. The builders
//! here are independent of the parser under test.

use super::*;

fn ext(ext_type: u16, data: &[u8]) -> Vec<u8> {
    let mut out = ext_type.to_be_bytes().to_vec();
    out.extend_from_slice(&(data.len() as u16).to_be_bytes());
    out.extend_from_slice(data);
    out
}

fn sni_data(name_type: u8, host: &[u8], list_len: u16, host_len: u16) -> Vec<u8> {
    let mut data = list_len.to_be_bytes().to_vec();
    data.push(name_type);
    data.extend_from_slice(&host_len.to_be_bytes());
    data.extend_from_slice(host);
    data
}

fn sni(host: &[u8]) -> Vec<u8> {
    ext(0x0000, &sni_data(0, host, host.len() as u16 + 3, host.len() as u16))
}

fn alpn() -> Vec<u8> {
    ext(0x0010, &[0x00, 0x03, 0x02, b'h', b'2'])
}

fn key_share() -> Vec<u8> {
    let mut data = vec![0x00, 0x24, 0x00, 0x1d, 0x00, 0x20];
    data.extend_from_slice(&[0x42; 32]);
    ext(0x0033, &data)
}

fn ech() -> Vec<u8> {
    ext(0xfe0d, &[0x00, 0x00, 0x01, 0x00, 0x01, 0x07, 0x00, 0x02, 0x11, 0x22, 0x00, 0x02, 0x33, 0x44])
}

/// Payload offset of the first extension, counted field by field: handshake
/// header 4, version 2, random 32, session id 1 and 32, cipher suites 2 and 4,
/// compression 1 and 1, extensions length 2.
const FIRST_EXT_PAYLOAD_OFFSET: usize = 81;

fn client_hello_payload(extensions: &[Vec<u8>]) -> Vec<u8> {
    let ext_bytes = extensions.concat();
    let mut body = vec![0x03, 0x03];
    body.extend_from_slice(&[0xaa; 32]);
    body.push(32);
    body.extend_from_slice(&[0xbb; 32]);
    body.extend_from_slice(&[0x00, 0x04, 0x13, 0x01, 0x13, 0x02, 0x01, 0x00]);
    body.extend_from_slice(&(ext_bytes.len() as u16).to_be_bytes());
    body.extend_from_slice(&ext_bytes);
    let mut handshake = vec![0x01];
    handshake.extend_from_slice(&(body.len() as u32).to_be_bytes()[1..]);
    handshake.extend_from_slice(&body);
    handshake
}

fn records(payload: &[u8], split_at: &[usize]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut start = 0;
    for end in split_at.iter().copied().chain([payload.len()]) {
        out.extend_from_slice(&[0x16, 0x03, 0x01]);
        out.extend_from_slice(&((end - start) as u16).to_be_bytes());
        out.extend_from_slice(&payload[start..end]);
        start = end;
    }
    out
}

#[test]
fn sni_in_first_record_keeps_extensions_and_ech_offset_after_split() {
    let exts = [sni(b"example.com"), alpn(), key_share(), ech()];
    let payload = client_hello_payload(&exts);
    // Split right after the SNI extension (4 + 2 + 1 + 2 + 11 bytes).
    let split = FIRST_EXT_PAYLOAD_OFFSET + 20;
    let buffer = records(&payload, &[split]);

    let layout = parse_tls_client_hello_layout(&buffer).expect("layout");
    let types: Vec<u16> = layout.extensions.iter().map(|e| e.ext_type).collect();
    assert_eq!(types, vec![0x0000, 0x0010, 0x0033, 0xfe0d]);

    // Record 2 header (5 bytes) sits between SNI and ALPN in the buffer.
    let ech_offset = 5 + payload.len() + 5 - ech().len();
    let markers = tls_marker_info(&buffer).expect("markers");
    assert_eq!(markers.ech_ext_start, Some(ech_offset));
    assert_eq!(&buffer[ech_offset..ech_offset + 2], &[0xfe, 0x0d]);
    assert_eq!(parse_tls(&buffer), Some(&b"example.com"[..]));
    for ext in &layout.extensions {
        assert_eq!(&buffer[ext.type_offset..ext.type_offset + 2], &ext.ext_type.to_be_bytes());
    }
}

#[test]
fn sni_in_second_record_is_found_when_contiguous() {
    let exts = [sni(b"example.com"), alpn()];
    let payload = client_hello_payload(&exts);
    let buffer = records(&payload, &[FIRST_EXT_PAYLOAD_OFFSET]);

    assert_eq!(parse_tls(&buffer), Some(&b"example.com"[..]));
    let markers = tls_marker_info(&buffer).expect("markers");
    assert_eq!(&buffer[markers.host_start..markers.host_end], b"example.com");
    assert_eq!(&buffer[markers.sni_ext_start - 4..markers.sni_ext_start - 2], &[0x00, 0x00]);
}

#[test]
fn host_across_record_boundary_has_no_contiguous_marker() {
    let payload = client_hello_payload(&[sni(b"example.com"), alpn()]);
    let buffer = records(&payload, &[FIRST_EXT_PAYLOAD_OFFSET + 4 + 5 + 3]);

    assert_eq!(parse_tls(&buffer), None);
    assert_eq!(tls_marker_info(&buffer), None);
}

#[test]
fn partial_final_record_is_parsed() {
    let payload = client_hello_payload(&[sni(b"example.com"), alpn(), key_share()]);
    let mut buffer = records(&payload, &[FIRST_EXT_PAYLOAD_OFFSET + 20]);
    buffer.truncate(buffer.len() - 20);

    assert_eq!(parse_tls(&buffer), Some(&b"example.com"[..]));
}

fn one_record(extensions: &[Vec<u8>]) -> Vec<u8> {
    records(&client_hello_payload(extensions), &[])
}

#[test]
fn server_hello_echoing_a_different_session_id_length_is_a_mismatch() {
    let request = one_record(&[sni(b"example.com"), key_share()]);
    // ServerHello with an empty session id echo; the request sent 32 bytes.
    let extensions = [ext(0x002b, &[0x03, 0x04]), key_share()].concat();
    let mut body = vec![0x03, 0x03];
    body.extend_from_slice(&[0x55; 32]);
    body.push(0);
    body.extend_from_slice(&[0x13, 0x01, 0x00]);
    body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
    body.extend_from_slice(&extensions);
    let mut handshake = vec![0x02];
    handshake.extend_from_slice(&(body.len() as u32).to_be_bytes()[1..]);
    handshake.extend_from_slice(&body);
    let mut response = vec![0x16, 0x03, 0x03];
    response.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
    response.extend_from_slice(&handshake);

    assert!(tls_session_id_mismatch(&request, &response));
}

#[test]
fn malformed_server_name_extensions_yield_no_host() {
    let overrun = ext(0x0000, &sni_data(0, b"a.io", 7, 0x20));
    let wrong_type = ext(0x0000, &sni_data(1, b"example.com", 14, 11));
    let empty = ext(0x0000, &sni_data(0, b"", 3, 0));
    let list_too_long = ext(0x0000, &sni_data(0, b"example.com", 0x30, 11));
    let list_too_short = ext(0x0000, &sni_data(0, b"example.com", 5, 11));
    for (name, sni_ext) in [
        ("host_len overruns extension", overrun),
        ("name_type is not host_name", wrong_type),
        ("empty host", empty),
        ("list_len exceeds extension", list_too_long),
        ("list_len shorter than entry", list_too_short),
    ] {
        let buffer = one_record(&[sni_ext, alpn(), key_share()]);
        assert_eq!(parse_tls(&buffer), None, "{name}");
    }
}
