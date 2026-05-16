#![no_main]

//! Fuzz target for `ripdpi-vless::wire::parse_response_header` — the
//! sync variant of `read_response` exposed for fuzzing without an
//! async runtime. Mirrors the wire shape of the response header
//! (`Version(1B) | AddonsLen(1B) | Addons(var)`).

use libfuzzer_sys::fuzz_target;
use ripdpi_vless::wire::parse_response_header;

fuzz_target!(|data: &[u8]| {
    let _ = parse_response_header(data);
});
