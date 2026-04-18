use golden_test_support::{assert_text_golden, canonicalize_json};
use serde_json::json;

use super::*;

fn tls_record_lengths(buffer: &[u8]) -> Vec<usize> {
    let mut cursor = 0usize;
    let mut lengths = Vec::new();
    while cursor + 5 <= buffer.len() {
        let len = u16::from_be_bytes([buffer[cursor + 3], buffer[cursor + 4]]) as usize;
        lengths.push(len);
        cursor += 5 + len;
    }
    assert_eq!(cursor, buffer.len());
    lengths
}

fn flatten_tls_payload(buffer: &[u8]) -> Vec<u8> {
    let mut cursor = 0usize;
    let mut payload = Vec::new();
    while cursor + 5 <= buffer.len() {
        let len = u16::from_be_bytes([buffer[cursor + 3], buffer[cursor + 4]]) as usize;
        payload.extend_from_slice(&buffer[cursor + 5..cursor + 5 + len]);
        cursor += 5 + len;
    }
    assert_eq!(cursor, buffer.len());
    payload
}

fn assert_phase11_golden(name: &str, value: &serde_json::Value) {
    let actual = serde_json::to_string_pretty(value).expect("serialize golden summary");
    let actual = canonicalize_json(&actual).expect("canonicalize golden summary");
    assert_text_golden(env!("CARGO_MANIFEST_DIR"), &format!("tests/golden/{name}.json"), &actual);
}

#[test]
fn phase11_chrome_desktop_record_choreography_golden() {
    let payload = rust_packet_seeds::tls_client_hello();
    let rewritten =
        crate::apply_tls_template_record_choreography("chrome_desktop_stable", &payload).expect("chrome rewrite");
    let input_host = String::from_utf8_lossy(parse_tls(&payload).expect("parse input tls host")).into_owned();

    assert_phase11_golden(
        "phase11_chrome_desktop_record_choreography",
        &json!({
            "profile": "chrome_desktop_stable",
            "input_record_lengths": tls_record_lengths(&payload),
            "rewritten_record_lengths": tls_record_lengths(&rewritten.bytes),
            "payload_preserved": flatten_tls_payload(&rewritten.bytes) == payload[5..].to_vec(),
            "input_host": input_host,
        }),
    );
}

#[test]
fn phase11_firefox_ech_record_choreography_golden() {
    let payload = rust_packet_seeds::tls_client_hello_ech();
    let rewritten =
        crate::apply_tls_template_record_choreography("firefox_ech_stable", &payload).expect("firefox ech rewrite");
    let markers = tls_marker_info(&payload).expect("tls markers");
    let input_host = String::from_utf8_lossy(parse_tls(&payload).expect("parse input tls host")).into_owned();

    assert_phase11_golden(
        "phase11_firefox_ech_record_choreography",
        &json!({
            "profile": "firefox_ech_stable",
            "input_record_lengths": tls_record_lengths(&payload),
            "rewritten_record_lengths": tls_record_lengths(&rewritten.bytes),
            "payload_preserved": flatten_tls_payload(&rewritten.bytes) == payload[5..].to_vec(),
            "ech_extension_present": markers.ech_ext_start.is_some(),
            "input_host": input_host,
        }),
    );
}
