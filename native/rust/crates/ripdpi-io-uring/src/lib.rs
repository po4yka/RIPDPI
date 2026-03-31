//! io_uring zero-copy networking support for RIPDPI.
//!
//! This crate provides optional io_uring integration for zero-copy send/recv
//! on Linux 6.0+ kernels. All types and functions are gated behind
//! `cfg(any(target_os = "linux", target_os = "android"))`.

#[cfg(any(target_os = "linux", target_os = "android"))]
mod bufpool;
#[cfg(any(target_os = "linux", target_os = "android"))]
mod probe;
#[cfg(any(target_os = "linux", target_os = "android"))]
mod ring;
#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod tun;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub use bufpool::{BufferHandle, RegisteredBufferPool};
#[cfg(any(target_os = "linux", target_os = "android"))]
pub use probe::{io_uring_capabilities, IoUringCapabilities};
#[cfg(any(target_os = "linux", target_os = "android"))]
pub use ring::{block_on_completion, CompletionFuture, CompletionResult, IoUringDriver, Submission};

// On non-Linux platforms, provide a stub capabilities struct that always
// reports unavailable so callers can use it unconditionally.
#[cfg(not(any(target_os = "linux", target_os = "android")))]
mod stub {
    #[derive(Debug, Clone, Copy, Default)]
    pub struct IoUringCapabilities {
        pub available: bool,
        pub send_zc: bool,
        pub recv_zc: bool,
        pub fixed_buffers: bool,
        pub multishot_recv: bool,
    }

    pub fn io_uring_capabilities() -> IoUringCapabilities {
        IoUringCapabilities::default()
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub use stub::{io_uring_capabilities, IoUringCapabilities};

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
            assert!(!caps.send_zc);
            assert!(!caps.recv_zc);
            assert!(!caps.fixed_buffers);
            assert!(!caps.multishot_recv);
        }
        // On Linux, just verify the function doesn't panic.
        #[cfg(any(target_os = "linux", target_os = "android"))]
        {
            let _ = caps;
        }
    }
}
