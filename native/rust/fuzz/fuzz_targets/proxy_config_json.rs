#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    common::proxy_config_smoke();

    if let Ok(raw) = std::str::from_utf8(data) {
        let _ = ripdpi_proxy_config::parse_proxy_config_json(raw);
    }

    let structured = common::proxy_config_json_from_bytes(data);
    let _ = ripdpi_proxy_config::parse_proxy_config_json(&structured);

    if !structured.is_empty() {
        let truncated_len = structured.len().saturating_sub(1 + usize::from(data.first().copied().unwrap_or(0) % 4));
        let _ = ripdpi_proxy_config::parse_proxy_config_json(&structured[..truncated_len]);
    }
});
