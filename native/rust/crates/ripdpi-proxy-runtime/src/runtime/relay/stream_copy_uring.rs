//! Zero-copy relay using io_uring `IORING_OP_SEND_ZC`.
//!
//! This module provides an alternative implementation of the relay stream
//! copy that uses io_uring zero-copy send for the inbound half (upstream ->
//! client). The outbound half still uses the standard desync path.
//!
//! Enabled only when the `io-uring` feature is active and the kernel
//! supports `IORING_OP_SEND_ZC` (detected at runtime).

mod cleanup;
mod freeze_detector;
mod inbound_fallback;
mod inbound_zc;
mod observations;
mod orchestration;
mod outbound_desync;
mod uring_buffers;

#[cfg(test)]
mod tests;

pub(crate) use orchestration::relay_streams_uring;

const RELAY_IDLE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(60);
