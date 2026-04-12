#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    common::tunnel_config_smoke();

    if let Ok(raw) = std::str::from_utf8(data) {
        let _ = raw.parse::<ripdpi_tunnel_config::Config>();
    }

    let structured = common::tunnel_config_yaml_from_bytes(data);
    let _ = structured.parse::<ripdpi_tunnel_config::Config>();

    if !structured.is_empty() {
        let truncated_len = structured.len().saturating_sub(1 + usize::from(data.first().copied().unwrap_or(0) % 4));
        let _ = structured[..truncated_len].parse::<ripdpi_tunnel_config::Config>();
    }
});
