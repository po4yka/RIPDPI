//! Runtime FFI layout sanity tests for issue #25.
//!
//! These tests duplicate the compile-time `const _: () = assert!(...)`
//! blocks at the struct definition sites with explicit runtime
//! assertions, so a regression that survives `cargo check` (e.g. via
//! a feature gate that disables the const-eval guard on some target)
//! still fails the test suite.
//!
//! The compile-time checks are the canonical guard; these tests are a
//! belt-and-suspenders cross-check that the FFI buffers the kernel
//! and BoringSSL receive match what the Rust struct definitions say
//! they receive.

use std::mem::{align_of, size_of};

use crate::linux::tcp_repair::TcpRepairWindow;

#[test]
fn tcp_repair_window_layout_matches_linux_uapi() {
    assert_eq!(size_of::<TcpRepairWindow>(), 20, "Linux uapi tcp_repair_window is 5 * u32 = 20 bytes");
    assert_eq!(align_of::<TcpRepairWindow>(), 4, "alignment matches u32 alignment");
}

#[test]
fn linux_tcp_info_size_is_at_least_field_total() {
    use crate::linux::tcp_info::LinuxTcpInfo;
    // 8 * u8 + 24 * u32 + 4 * u64 + 3 * u32 = 8 + 96 + 32 + 12 = 148.
    // Struct alignment depends on u64 alignment per target (8 on
    // aarch64/x86_64/armv7+VFP, 4 on i686 Linux). We assert a lower
    // bound that holds across all four Android targets.
    assert!(size_of::<LinuxTcpInfo>() >= 148, "field bytes total to 148; actual = {}", size_of::<LinuxTcpInfo>());
    assert!(align_of::<LinuxTcpInfo>() >= align_of::<u32>(), "must be at least u32-aligned");
}
