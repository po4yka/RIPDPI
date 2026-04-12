#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    common::failure_smoke();

    let response = common::http_response_from_bytes(data);
    if let Some(failure) = ripdpi_failure_classifier::classify_http_response_block(&response) {
        let retransmissions = Some(u32::from(data.first().copied().unwrap_or(0) % 5));
        let _ = ripdpi_failure_classifier::block_signal_from_failure(&failure, retransmissions);
    }
});
