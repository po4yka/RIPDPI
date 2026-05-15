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
