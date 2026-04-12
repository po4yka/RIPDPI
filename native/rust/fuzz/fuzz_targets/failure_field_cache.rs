#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    common::failure_smoke();

    let cache = common::field_cache_from_bytes(data);
    if let Some(failure) = ripdpi_failure_classifier::field_classifier::classify_from_fields(
        &cache,
        ripdpi_failure_classifier::bundled_blockpage_fingerprints(),
    ) {
        let retransmissions = Some(u32::from(data.first().copied().unwrap_or(0) % 5));
        let _ = ripdpi_failure_classifier::block_signal_from_failure(&failure, retransmissions);
    }
});
