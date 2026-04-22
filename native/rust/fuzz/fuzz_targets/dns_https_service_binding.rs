#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    common::dns_response_smoke();

    let _ = ripdpi_dns_resolver::parse_https_service_bindings(data);

    let structured = common::dns_response_packet_from_bytes(data);
    let _ = ripdpi_dns_resolver::parse_https_service_bindings(&structured);

    if !structured.is_empty() {
        let truncated_len = structured.len().saturating_sub(1 + usize::from(data.first().copied().unwrap_or(0) % 6));
        let _ = ripdpi_dns_resolver::parse_https_service_bindings(&structured[..truncated_len]);
    }
});
