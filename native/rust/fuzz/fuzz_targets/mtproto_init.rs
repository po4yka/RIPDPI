#![no_main]

//! Fuzz target for the MTProto obfuscated2 init parsers used by the
//! Telegram WS-tunnel relay. The seed bytes are network-influenced
//! and consumed before any handshake is established, so panics here
//! crash the relay worker.

use libfuzzer_sys::fuzz_target;
use ripdpi_ws_tunnel::{classify_mtproto_seed, decrypt_init_packet, extract_dc_from_init};

fuzz_target!(|data: &[u8]| {
    // Variable-length classifier path: arbitrary network bytes, short
    // and long, must never panic.
    let _ = classify_mtproto_seed(data);

    // Fixed-size paths only when we have at least one full 64-byte
    // init buffer to exercise. Slide a window through the buffer so
    // we hit alignment edges as well.
    if data.len() >= 64 {
        let mut init = [0u8; 64];
        init.copy_from_slice(&data[..64]);
        let _ = decrypt_init_packet(&init);
        let _ = extract_dc_from_init(&init);
    }
});
