//! Public facade — TTL read/recv operations.
//!
//! Re-exports the `ttl` OS-primitive adapter: enabling per-recv TTL delivery
//! and reading a chunk together with its observed IP TTL. Thin `#[cfg]`-split
//! wrappers over `ripdpi-privileged-ops` with a plain-read non-Linux fallback.

pub use super::ttl::{enable_recv_ttl, read_chunk_with_ttl};
