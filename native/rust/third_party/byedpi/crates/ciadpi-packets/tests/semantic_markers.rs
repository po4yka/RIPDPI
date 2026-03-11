use ciadpi_packets::{http_marker_info, parse_http, parse_quic_initial, second_level_domain_span};

#[allow(dead_code)]
#[path = "../../../tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

#[test]
fn http_markers_keep_structural_sld_semantics() {
    let request = b"GET / HTTP/1.1\r\nHost: foo.example.co.uk\r\n\r\n";
    let markers = http_marker_info(request).expect("http markers");
    let host = &request[markers.host_start..markers.host_end];
    let (sld_start, sld_end) = second_level_domain_span(host).expect("structural sld");

    assert_eq!(host, b"foo.example.co.uk");
    assert_eq!(&host[sld_start..sld_end], b"co");
}

#[test]
fn http_markers_track_single_lf_preface_and_trim_port() {
    let request = b"\nGET / HTTP/1.1\r\nHost: api.example.test:8443\r\n\r\n";
    let markers = http_marker_info(request).expect("http markers");
    let parsed = parse_http(request).expect("parse http");

    assert_eq!(markers.method_start, 1);
    assert_eq!(&request[markers.host_start..markers.host_end], b"api.example.test");
    assert_eq!(markers.port, 8443);
    assert_eq!(parsed.host, b"api.example.test");
    assert_eq!(parsed.port, 8443);
}

#[test]
fn quic_initial_exposes_tls_marker_offsets_for_bare_client_hello() {
    let packet = rust_packet_seeds::quic_initial_v1();
    let parsed = parse_quic_initial(&packet).expect("parse quic initial");

    assert_eq!(&parsed.client_hello[parsed.tls_info.host_start..parsed.tls_info.host_end], b"docs.example.test");
    assert_eq!(parsed.tls_info.host_start, parsed.tls_info.sni_ext_start + 5);
    assert!(parsed.tls_info.ext_len_start < parsed.tls_info.sni_ext_start);
}
