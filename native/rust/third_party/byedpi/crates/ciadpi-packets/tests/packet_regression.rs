use ciadpi_packets::{
    change_tls_sni_seeded_like_c, is_http, is_http_redirect, is_tls_client_hello,
    is_tls_server_hello, mod_http_like_c, parse_http, parse_tls, part_tls_like_c,
    randomize_tls_seeded_like_c, tls_session_id_mismatch, MH_HMIX, MH_SPACE,
};

mod packet_test_support;

use packet_test_support::{
    exercise_packets_input, read_corpus, EXPECTED_HOST, GROWN_HOST, SHRUNK_HOST,
};

#[test]
fn http_request_regression_matches_c_lane() {
    let data = read_corpus("http_request.bin");

    exercise_packets_input(&data);
    assert!(is_http(&data));

    let parsed = parse_http(&data).expect("parse_http should accept the seed");
    assert_eq!(parsed.host, EXPECTED_HOST);
    assert_eq!(parsed.port, 80);

    let mutated = mod_http_like_c(&data, MH_HMIX | MH_SPACE);
    assert_eq!(mutated.rc, 0);

    let reparsed = parse_http(&mutated.bytes).expect("parse_http should accept the mutation");
    assert_eq!(reparsed.host, EXPECTED_HOST);
    assert_eq!(reparsed.port, 80);
}

#[test]
fn http_redirect_regression_matches_c_lane() {
    let req = read_corpus("http_request.bin");
    let resp = read_corpus("http_redirect_response.bin");

    assert!(is_http_redirect(&req, &resp));
}

#[test]
fn tls_request_regression_matches_c_lane() {
    let data = read_corpus("tls_client_hello.bin");

    exercise_packets_input(&data);
    assert!(is_tls_client_hello(&data));

    let parsed = parse_tls(&data).expect("parse_tls should accept the seed");
    assert_eq!(parsed, EXPECTED_HOST);

    let split = part_tls_like_c(&data, 32);
    assert_eq!(split.rc, 5);

    let randomized = randomize_tls_seeded_like_c(&data, 1);
    assert_eq!(randomized.rc, 0);

    let reparsed =
        parse_tls(&randomized.bytes).expect("parse_tls should accept the randomized payload");
    assert_eq!(reparsed, EXPECTED_HOST);
}

#[test]
fn tls_session_id_mismatch_regression_matches_c_lane() {
    let req = read_corpus("tls_client_hello.bin");
    let resp = read_corpus("tls_server_hello_like.bin");

    assert!(is_tls_server_hello(&resp));
    assert!(tls_session_id_mismatch(&req, &resp));
}

#[test]
fn tls_sni_change_with_ech_expand_matches_c_lane() {
    let data = read_corpus("tls_client_hello_ech.bin");
    let mutated = change_tls_sni_seeded_like_c(&data, GROWN_HOST, data.len() + 32, 1);

    assert_eq!(mutated.rc, 0);

    let reparsed =
        parse_tls(&mutated.bytes).expect("parse_tls should accept the grown ECH payload");
    assert_eq!(reparsed, GROWN_HOST);
}

#[test]
fn tls_sni_change_with_ech_shrink_matches_c_lane() {
    let data = read_corpus("tls_client_hello_ech.bin");
    let mutated = change_tls_sni_seeded_like_c(&data, SHRUNK_HOST, data.len(), 1);

    assert_eq!(mutated.rc, 0);

    let reparsed =
        parse_tls(&mutated.bytes).expect("parse_tls should accept the shrunken ECH payload");
    assert_eq!(reparsed, SHRUNK_HOST);
}
