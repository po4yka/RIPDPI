#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;

fn domain_from_bytes(data: &[u8]) -> String {
    format!("{}.example", common::ascii_label(data, "pkt-", 24))
}

fn tls_variant(data: &[u8]) -> Vec<u8> {
    let seed = data
        .get(..4)
        .and_then(|bytes| bytes.try_into().ok())
        .map(u32::from_le_bytes)
        .unwrap_or(7);
    let host = domain_from_bytes(data.get(4..).unwrap_or_default());

    let randomized = ripdpi_packets::randomize_tls_seeded_like_c(ripdpi_packets::DEFAULT_FAKE_TLS, seed);
    let sni_randomized = ripdpi_packets::randomize_tls_sni_seeded_like_c(&randomized.bytes, seed ^ 0x5a5a_5a5a);
    let duplicated = ripdpi_packets::duplicate_tls_session_id_like_c(&sni_randomized.bytes, ripdpi_packets::DEFAULT_FAKE_TLS);

    let desired_len = ripdpi_packets::DEFAULT_FAKE_TLS
        .len()
        .saturating_add(data.get(0).copied().unwrap_or(0) as usize % 48);
    let padded = ripdpi_packets::tune_tls_padding_size_like_c(&duplicated.bytes, desired_len);

    let payload_len = 1 + data.get(1).copied().unwrap_or(0) as usize % 64;
    let encapsulated = ripdpi_packets::padencap_tls_like_c(&padded.bytes, payload_len);

    let capacity = encapsulated.bytes.len().saturating_add(host.len()).saturating_add(64);
    let renamed =
        ripdpi_packets::change_tls_sni_seeded_like_c(&encapsulated.bytes, host.as_bytes(), capacity, seed.rotate_left(1));
    if ripdpi_packets::is_tls_client_hello(&renamed.bytes) {
        renamed.bytes
    } else {
        ripdpi_packets::DEFAULT_FAKE_TLS.to_vec()
    }
}

fn fuzz_structured_quic(packet: &[u8], data: &[u8]) {
    let Some(parsed) = ripdpi_packets::parse_quic_initial(packet) else {
        return;
    };

    let split_offset =
        1 + data.get(2).copied().unwrap_or(0) as usize % parsed.client_hello.len().saturating_sub(1).max(1);
    if let Some(split_packet) = ripdpi_packets::tamper_quic_initial_split_sni(packet, split_offset) {
        let _ = ripdpi_packets::parse_quic_initial(&split_packet);
        let _ = ripdpi_packets::tamper_quic_version(
            &split_packet,
            u32::from_be_bytes([
                data.first().copied().unwrap_or(0),
                data.get(1).copied().unwrap_or(0),
                data.get(2).copied().unwrap_or(0),
                data.get(3).copied().unwrap_or(1),
            ]),
        );
    }
}

fuzz_target!(|data: &[u8]| {
    common::packet_smoke();

    let _ = ripdpi_packets::parse_tls(data);
    let _ = ripdpi_packets::tls_marker_info(data);
    let _ = ripdpi_packets::parse_quic_initial(data);

    if let Some(raw_quic) = ripdpi_packets::parse_quic_initial(data) {
        let raw_split =
            1 + data.get(4).copied().unwrap_or(0) as usize % raw_quic.client_hello.len().saturating_sub(1).max(1);
        let _ = ripdpi_packets::tamper_quic_initial_split_sni(data, raw_split);
        let _ = ripdpi_packets::tamper_quic_version(data, raw_quic.version ^ 0x1a2a_3a4a);
    }

    let tls = tls_variant(data);
    let _ = ripdpi_packets::parse_tls(&tls);
    let _ = ripdpi_packets::tls_marker_info(&tls);

    let tls_split = (data.get(5).copied().unwrap_or(0) as isize) % (tls.len().max(1) as isize);
    let partial_tls = ripdpi_packets::part_tls_like_c(&tls, tls_split);
    let _ = ripdpi_packets::parse_tls(&partial_tls.bytes);

    let quic_version =
        if data.get(6).copied().unwrap_or(0) & 1 == 0 { ripdpi_packets::QUIC_V1_VERSION } else { ripdpi_packets::QUIC_V2_VERSION };
    if let Some(packet) = ripdpi_packets::build_quic_initial_from_tls(quic_version, &tls, data.get(7).copied().unwrap_or(0) as usize % 8)
    {
        fuzz_structured_quic(&packet, data);
    }

    if let Some(realistic) = ripdpi_packets::build_realistic_quic_initial(quic_version, Some(&domain_from_bytes(data))) {
        fuzz_structured_quic(&realistic, data);
    }
});
