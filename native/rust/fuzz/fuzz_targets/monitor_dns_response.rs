#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    common::dns_response_smoke();

    let expected_id =
        match data {
            [high, low, ..] => u16::from_be_bytes([*high, *low]),
            _ => 0,
        };
    let _ = ripdpi_monitor::fuzz_parse_dns_response(data, expected_id);

    let structured = common::dns_response_packet_from_bytes(data);
    let structured_id =
        match structured.as_slice() {
            [high, low, ..] => u16::from_be_bytes([*high, *low]),
            _ => 0,
        };
    let _ = ripdpi_monitor::fuzz_parse_dns_response(&structured, structured_id);
});
