//! OS-primitive adapter — io_uring capability probe.
//!
//! Feature-gated (`io-uring`) thin wrapper over `ripdpi-io-uring`. Surfaced
//! through the `socket` facade.

/// Return io_uring capabilities detected at startup.
pub fn io_uring_capabilities() -> ripdpi_io_uring::IoUringCapabilities {
    ripdpi_io_uring::io_uring_capabilities()
}
