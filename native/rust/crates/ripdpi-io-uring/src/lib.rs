//! io_uring zero-copy networking support for RIPDPI.
//!
//! This crate provides optional io_uring integration for zero-copy send/recv
//! on Linux 6.0+ kernels. All types and functions are gated behind
//! `cfg(any(target_os = "linux", target_os = "android"))`.

// Crate-local hardening for issue #16 (`Vec::from_raw_parts` /
// raw-buffer-transfer audit). `ripdpi-io-uring` owns the workspace's
// `RegisteredBufferPool` / `BufferHandle` shapes — the typed
// alternative to `Vec::from_raw_parts` for "Rust allocates a buffer,
// kernel writes into it" io_uring zero-copy paths. Per
// docs/rust-soundness-policy.md § "`Vec::from_raw_parts` ownership
// transfer", every `unsafe { }` in this crate MUST carry an inline
// SAFETY comment so the buffer-transfer discipline is auditable
// next to the operation. Workspace-wide `undocumented_unsafe_blocks`
// is still `allow` while the legacy corpus is being annotated;
// re-enabling it crate-locally locks the io_uring surface to the
// documentation contract immediately.
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

#[cfg(any(target_os = "linux", target_os = "android"))]
mod bufpool;
#[cfg(any(target_os = "linux", target_os = "android"))]
mod probe;
#[cfg(any(target_os = "linux", target_os = "android"))]
mod ring;
#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod tun;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub use bufpool::BufferHandle;
#[cfg(any(target_os = "linux", target_os = "android"))]
pub use probe::{IoUringCapabilities, io_uring_capabilities};
#[cfg(any(target_os = "linux", target_os = "android"))]
pub use ring::{CompletionFuture, CompletionResult, IoUringDriver, block_on_completion};

// On non-Linux platforms, provide a stub capabilities struct that always
// reports unavailable so callers can use it unconditionally.
#[cfg(not(any(target_os = "linux", target_os = "android")))]
mod stub {
    #[derive(Debug, Clone, Copy, Default)]
    pub struct IoUringCapabilities {
        pub available: bool,
        pub fixed_buffers: bool,
    }

    pub fn io_uring_capabilities() -> IoUringCapabilities {
        IoUringCapabilities::default()
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub use stub::{IoUringCapabilities, io_uring_capabilities};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capabilities_stub_defaults_to_unavailable() {
        // On any platform (including macOS CI), the stub or probe returns
        // a struct where all capabilities are false by default.
        let caps = io_uring_capabilities();
        // On macOS the stub always returns false; on Linux the probe may
        // return true if io_uring is available. We only assert the stub
        // contract on non-Linux.
        #[cfg(not(any(target_os = "linux", target_os = "android")))]
        {
            assert!(!caps.available);
            assert!(!caps.fixed_buffers);
        }
        // On Linux, just verify the function doesn't panic.
        #[cfg(any(target_os = "linux", target_os = "android"))]
        {
            let _ = caps;
        }
    }
}
