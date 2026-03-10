#![allow(dead_code)]

use ciadpi_packets::{
    change_tls_sni_seeded_like_c, is_http, is_http_redirect, is_tls_client_hello, is_tls_server_hello, mod_http_like_c,
    parse_http, parse_tls, part_tls_like_c, randomize_tls_seeded_like_c, tls_session_id_mismatch, OracleRng, MH_HMIX,
    MH_SPACE,
};

#[allow(dead_code)]
#[path = "../../../tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

pub const EXPECTED_HOST: &[u8] = b"www.wikipedia.org";
pub const GROWN_HOST: &[u8] = b"docs.example.test";
pub const SHRUNK_HOST: &[u8] = b"a.docs.example.test";

pub fn read_corpus(name: &str) -> Vec<u8> {
    match name {
        "http_request.bin" => rust_packet_seeds::http_request(),
        "http_redirect_response.bin" => rust_packet_seeds::http_redirect_response(),
        "tls_client_hello.bin" => rust_packet_seeds::tls_client_hello(),
        "tls_client_hello_ech.bin" => rust_packet_seeds::tls_client_hello_ech(),
        "tls_server_hello_like.bin" => rust_packet_seeds::tls_server_hello_like(),
        other => panic!("unexpected corpus file: {other}"),
    }
}

pub fn corpus_cases() -> [(&'static str, Vec<u8>); 5] {
    [
        ("http_request.bin", read_corpus("http_request.bin")),
        ("http_redirect_response.bin", read_corpus("http_redirect_response.bin")),
        ("tls_client_hello.bin", read_corpus("tls_client_hello.bin")),
        ("tls_client_hello_ech.bin", read_corpus("tls_client_hello_ech.bin")),
        ("tls_server_hello_like.bin", read_corpus("tls_server_hello_like.bin")),
    ]
}

pub fn exercise_packets_input(data: &[u8]) {
    let split = data.len() / 2;

    let _ = is_http(data);
    let _ = is_tls_client_hello(data);
    let _ = is_tls_server_hello(data);
    let _ = parse_http(data);
    let _ = parse_tls(data);

    if split < data.len() {
        let _ = is_http_redirect(&data[..split], &data[split..]);
        let _ = tls_session_id_mismatch(&data[..split], &data[split..]);
    }

    let _ = mod_http_like_c(data, MH_HMIX | MH_SPACE);
    let _ = part_tls_like_c(data, if data.len() > 16 { 8 } else { 1 });
    let _ = randomize_tls_seeded_like_c(data, 1);

    if parse_tls(data).is_some() {
        let _ = change_tls_sni_seeded_like_c(data, GROWN_HOST, data.len() + 64, 1);
    }
}

pub fn mutate_bytes_like_c(data: &mut [u8], rng: &mut OracleRng) {
    if data.is_empty() {
        return;
    }

    let rounds = 1 + rng.next_mod(8);
    for _ in 0..rounds {
        let index = rng.next_mod(data.len());
        data[index] ^= rng.next_u8();

        if data.len() > 1 && (rng.next_raw() & 1) != 0 {
            let other = rng.next_mod(data.len());
            data.swap(index, other);
        }
    }
}
