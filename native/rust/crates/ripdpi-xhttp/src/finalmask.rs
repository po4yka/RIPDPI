mod bridge;
mod fragmentation;
mod masks;
mod spec;
mod sudoku;
#[cfg(test)]
mod tests;

pub use bridge::wrap_tcp_stream;

/// Fuzz-only entry point that exercises `FinalmaskSpec` parsing with
/// attacker-influenced `FinalmaskConfig` strings (including
/// `sudoku_seed`). Returns whether a non-noop spec was produced; an
/// `Err` indicates `FinalmaskSpec::from_config` rejected the input.
///
/// Hidden from generated docs — intended for `cargo-fuzz` harnesses
/// in `native/rust/fuzz/`. Not stable public API.
#[doc(hidden)]
pub fn __fuzz_parse_finalmask_spec(config: &crate::config::FinalmaskConfig) -> std::io::Result<bool> {
    Ok(spec::FinalmaskSpec::from_config(config)?.is_some())
}

/// Fuzz-only entry point that exercises the byte-stream
/// [`TcpInboundMask`] decoder with attacker-influenced payload bytes.
/// Constructs a `FinalmaskSpec` from `config`, builds an inbound
/// mask, and feeds `payload` to its decoder. Discards the result —
/// the fuzz harness checks for panics, overflows, and infinite
/// loops, not for content validity.
///
/// Hidden from generated docs — intended for `cargo-fuzz` harnesses
/// in `native/rust/fuzz/`. Not stable public API.
#[doc(hidden)]
pub fn __fuzz_decode_finalmask_payload(config: &crate::config::FinalmaskConfig, payload: &[u8]) -> std::io::Result<()> {
    let Some(spec) = spec::FinalmaskSpec::from_config(config)? else {
        return Ok(());
    };
    let mut mask = masks::TcpInboundMask::new(spec);
    // The decoder may return Err; that's allowed. The harness only
    // catches panics.
    let _ = mask.decode(payload);
    Ok(())
}
