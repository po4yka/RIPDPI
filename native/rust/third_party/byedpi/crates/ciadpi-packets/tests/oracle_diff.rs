use std::sync::OnceLock;

use ciadpi_packets::{
    change_tls_sni_seeded_like_c, is_http_redirect, mod_http_like_c, parse_http, parse_tls,
    part_tls_like_c, randomize_tls_seeded_like_c, tls_session_id_mismatch, MH_DMIX, MH_HMIX,
    MH_SPACE,
};
use serde_json::Value;

#[allow(dead_code)]
#[path = "../../../tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

fn fixtures() -> &'static Value {
    static FIXTURES: OnceLock<Value> = OnceLock::new();
    FIXTURES.get_or_init(|| {
        serde_json::from_str(include_str!(
            "../../../tests/corpus/rust-fixtures/packets_oracle.json"
        ))
        .expect("packet fixtures")
    })
}

fn fixture(case: &str) -> &'static Value {
    &fixtures()[case]
}

fn read_corpus(name: &str) -> Vec<u8> {
    match name {
        "http_request.bin" => rust_packet_seeds::http_request(),
        "http_redirect_response.bin" => rust_packet_seeds::http_redirect_response(),
        "tls_client_hello.bin" => rust_packet_seeds::tls_client_hello(),
        "tls_client_hello_ech.bin" => rust_packet_seeds::tls_client_hello_ech(),
        "tls_server_hello_like.bin" => rust_packet_seeds::tls_server_hello_like(),
        other => panic!("unexpected corpus file: {other}"),
    }
}

fn hex(data: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(data.len() * 2);
    for byte in data {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0f) as usize] as char);
    }
    out
}

#[test]
fn parse_http_matches_fixture() {
    let expected = fixture("parse_http");
    let data = read_corpus("http_request.bin");
    let parsed = parse_http(&data).expect("rust parse_http");

    assert_eq!(parsed.host, expected["host"].as_str().unwrap().as_bytes());
    assert_eq!(parsed.port, expected["port"].as_u64().unwrap() as u16);
}

#[test]
fn parse_tls_matches_fixture() {
    let data = read_corpus("tls_client_hello.bin");
    let parsed = parse_tls(&data).expect("rust parse_tls");

    assert_eq!(
        parsed,
        fixture("parse_tls")["host"].as_str().unwrap().as_bytes()
    );
}

#[test]
fn http_redirect_matches_fixture() {
    let req = read_corpus("http_request.bin");
    let resp = read_corpus("http_redirect_response.bin");
    assert_eq!(
        is_http_redirect(&req, &resp),
        fixture("http_redirect")["ok"].as_bool().unwrap()
    );
}

#[test]
fn tls_session_id_mismatch_matches_fixture() {
    let req = read_corpus("tls_client_hello.bin");
    let resp = read_corpus("tls_server_hello_like.bin");
    assert_eq!(
        tls_session_id_mismatch(&req, &resp),
        fixture("tls_session_id_mismatch")["ok"].as_bool().unwrap()
    );
}

#[test]
fn mod_http_matches_fixture() {
    let expected = fixture("mod_http_hmix_space");
    let data = read_corpus("http_request.bin");
    let rust = mod_http_like_c(&data, MH_HMIX | MH_SPACE);

    assert_eq!(rust.rc, expected["rc"].as_i64().unwrap() as isize);
    assert_eq!(hex(&rust.bytes), expected["hex"].as_str().unwrap());
}

#[test]
fn part_tls_matches_fixture() {
    let expected = fixture("part_tls_32");
    let data = read_corpus("tls_client_hello.bin");
    let rust = part_tls_like_c(&data, 32);

    assert_eq!(rust.rc, expected["rc"].as_i64().unwrap() as isize);
    assert_eq!(hex(&rust.bytes), expected["hex"].as_str().unwrap());
}

#[test]
fn change_tls_sni_matches_fixture() {
    let expected = fixture("change_tls_sni_grow");
    let data = read_corpus("tls_client_hello_ech.bin");
    let capacity = data.len() + 64;
    let rust = change_tls_sni_seeded_like_c(&data, b"docs.example.test", capacity, 1);

    assert_eq!(rust.rc, expected["rc"].as_i64().unwrap() as isize);
    assert_eq!(hex(&rust.bytes), expected["hex"].as_str().unwrap());
}

#[test]
fn change_tls_sni_shrink_matches_fixture() {
    let expected = fixture("change_tls_sni_shrink");
    let data = read_corpus("tls_client_hello_ech.bin");
    let rust = change_tls_sni_seeded_like_c(&data, b"a.docs.example.test", data.len(), 1);

    assert_eq!(rust.rc, expected["rc"].as_i64().unwrap() as isize);
    assert_eq!(hex(&rust.bytes), expected["hex"].as_str().unwrap());
}

#[test]
fn randomize_tls_matches_deterministic_fixture() {
    let expected = fixture("randomize_tls_seeded");
    let data = read_corpus("tls_client_hello.bin");
    let rust = randomize_tls_seeded_like_c(&data, 7);

    assert_eq!(rust.rc, expected["rc"].as_i64().unwrap() as isize);
    assert_eq!(hex(&rust.bytes), expected["hex"].as_str().unwrap());
}

#[test]
fn mod_http_dmix_matches_fixture() {
    let expected = fixture("mod_http_hmix_space_dmix");
    let data = read_corpus("http_request.bin");
    let rust = mod_http_like_c(&data, MH_HMIX | MH_SPACE | MH_DMIX);

    assert_eq!(rust.rc, expected["rc"].as_i64().unwrap() as isize);
    assert_eq!(hex(&rust.bytes), expected["hex"].as_str().unwrap());
}
