#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    common::packet_smoke();

    let _ = ripdpi_packets::is_http(data);
    let _ = ripdpi_packets::http_marker_info(data);
    let _ = ripdpi_packets::parse_http(data);

    let _ = ripdpi_packets::is_tls_client_hello(data);
    let _ = ripdpi_packets::tls_marker_info(data);
    let _ = ripdpi_packets::parse_tls(data);

    let _ = ripdpi_packets::is_quic_initial(data);
    let _ = ripdpi_packets::parse_quic_initial(data);

    if !data.is_empty() {
        let host = common::ascii_label(data, "fuzz-", 24);
        let seed = u32::from(data[0]);
        let tls = ripdpi_packets::change_tls_sni_seeded_like_c(
            ripdpi_packets::DEFAULT_FAKE_TLS,
            host.as_bytes(),
            ripdpi_packets::DEFAULT_FAKE_TLS.len().saturating_add(host.len()).saturating_add(64),
            seed,
        );
        if tls.rc == 0 {
            let _ = ripdpi_packets::parse_tls(&tls.bytes);
            let _ = ripdpi_packets::tls_marker_info(&tls.bytes);
        }

        let version = if data[0] & 1 == 0 {
            ripdpi_packets::QUIC_V1_VERSION
        } else {
            ripdpi_packets::QUIC_V2_VERSION
        };
        if let Some(packet) = ripdpi_packets::build_realistic_quic_initial(version, Some(&host)) {
            let _ = ripdpi_packets::parse_quic_initial(&packet);
        }
    }
});
