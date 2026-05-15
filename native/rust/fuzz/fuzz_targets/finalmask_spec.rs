#![no_main]

//! Fuzz target for the xHTTP FinalMask config parser. Drives
//! attacker-influenced strings into `FinalmaskConfig` and exercises
//! `FinalmaskSpec::from_config` via the fuzz-only `__fuzz_parse_finalmask_spec`
//! entry point. Covers the Sudoku-table seed path, header/trailer hex
//! decoders, and rand-range parser.

use libfuzzer_sys::fuzz_target;
use ripdpi_xhttp::{FinalmaskConfig, __fuzz_parse_finalmask_spec};

fn split_str(data: &[u8], take: usize) -> (String, &[u8]) {
    let n = take.min(data.len());
    let s = String::from_utf8_lossy(&data[..n]).into_owned();
    (s, &data[n..])
}

fn split_i32(data: &[u8]) -> (i32, &[u8]) {
    if data.len() < 4 {
        return (0, data);
    }
    let mut bytes = [0u8; 4];
    bytes.copy_from_slice(&data[..4]);
    (i32::from_le_bytes(bytes), &data[4..])
}

fuzz_target!(|data: &[u8]| {
    // Slice the input into per-field strings + ints. Any byte pattern
    // must parse-or-reject without panicking.
    let (type_field, rest) = split_str(data, 16);
    let (header_hex, rest) = split_str(rest, 64);
    let (trailer_hex, rest) = split_str(rest, 64);
    let (rand_range, rest) = split_str(rest, 32);
    let (sudoku_seed, rest) = split_str(rest, 64);
    let (fragment_packets, rest) = split_i32(rest);
    let (fragment_min_bytes, rest) = split_i32(rest);
    let (fragment_max_bytes, _) = split_i32(rest);

    let cfg = FinalmaskConfig {
        r#type: type_field,
        header_hex,
        trailer_hex,
        rand_range,
        sudoku_seed,
        fragment_packets,
        fragment_min_bytes,
        fragment_max_bytes,
    };

    let _ = __fuzz_parse_finalmask_spec(&cfg);
});
