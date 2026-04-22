#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;

fn structured_tls_variant(data: &[u8]) -> Vec<u8> {
    let seed = data.get(..4).and_then(|bytes| bytes.try_into().ok()).map(u32::from_le_bytes).unwrap_or(11);
    let host = format!("{}.example", common::ascii_label(data.get(4..).unwrap_or_default(), "tls-", 24));
    let randomized = ripdpi_packets::randomize_tls_seeded_like_c(ripdpi_packets::DEFAULT_FAKE_TLS, seed);
    let renamed = ripdpi_packets::change_tls_sni_seeded_like_c(
        &randomized.bytes,
        host.as_bytes(),
        randomized.bytes.len().saturating_add(host.len()).saturating_add(64),
        seed.rotate_left(1),
    );
    if ripdpi_packets::is_tls_client_hello(&renamed.bytes) {
        renamed.bytes
    } else {
        ripdpi_packets::DEFAULT_FAKE_TLS.to_vec()
    }
}

fn split_records(packet: &[u8], data: &[u8]) -> Vec<u8> {
    if packet.len() <= 10 {
        return packet.to_vec();
    }
    let handshake = &packet[5..];
    let split = 1 + data.first().copied().unwrap_or(0) as usize % handshake.len().saturating_sub(1).max(1);
    let mut output = Vec::with_capacity(packet.len() + 5);
    output.extend_from_slice(&packet[..3]);
    output.extend_from_slice(&(split as u16).to_be_bytes());
    output.extend_from_slice(&handshake[..split]);
    output.extend_from_slice(&packet[..3]);
    output.extend_from_slice(&((handshake.len() - split) as u16).to_be_bytes());
    output.extend_from_slice(&handshake[split..]);
    output
}

fuzz_target!(|data: &[u8]| {
    let _ = ripdpi_desync::parse_client_hello_offsets(data);

    let tls = structured_tls_variant(data);
    let _ = ripdpi_desync::parse_client_hello_offsets(&tls);

    let split = split_records(&tls, data.get(8..).unwrap_or_default());
    let _ = ripdpi_desync::parse_client_hello_offsets(&split);
});
