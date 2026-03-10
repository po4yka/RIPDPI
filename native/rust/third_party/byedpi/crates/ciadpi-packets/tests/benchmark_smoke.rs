use std::time::Instant;

use ciadpi_packets::{
    change_tls_sni_seeded_like_c, mod_http_like_c, parse_http, parse_tls, part_tls_like_c,
    randomize_tls_seeded_like_c, MH_HMIX, MH_SPACE,
};

#[allow(dead_code)]
#[path = "../../../tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

fn read_corpus(name: &str) -> Vec<u8> {
    match name {
        "http_request.bin" => rust_packet_seeds::http_request(),
        "tls_client_hello.bin" => rust_packet_seeds::tls_client_hello(),
        "tls_client_hello_ech.bin" => rust_packet_seeds::tls_client_hello_ech(),
        other => panic!("unexpected corpus file: {other}"),
    }
}

#[test]
#[ignore = "smoke benchmark for packet hot paths"]
fn benchmark_smoke() {
    let http = read_corpus("http_request.bin");
    let tls = read_corpus("tls_client_hello.bin");
    let ech = read_corpus("tls_client_hello_ech.bin");

    let start = Instant::now();
    let mut checksum = 0usize;

    for _ in 0..10_000 {
        checksum += parse_http(&http).expect("http").host.len();
        checksum += parse_tls(&tls).expect("tls").len();
        checksum += mod_http_like_c(&http, MH_HMIX | MH_SPACE).bytes.len();
        checksum += part_tls_like_c(&tls, 32).bytes.len();
        checksum += randomize_tls_seeded_like_c(&tls, 7).bytes.len();
        checksum += change_tls_sni_seeded_like_c(&ech, b"docs.example.test", ech.len() + 64, 1)
            .bytes
            .len();
    }

    eprintln!(
        "packet benchmark smoke: {:?}, checksum={checksum}",
        start.elapsed()
    );
    assert!(checksum > 0);
}
