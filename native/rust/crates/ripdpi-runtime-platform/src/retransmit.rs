//! OS-primitive adapter — fake-retransmit / seqovl capability detection.
//!
//! Answers "does this platform support fake retransmission / sequence
//! overlap". `supports_fake_retransmit` is a compile-time `#[cfg]` constant;
//! `seqovl_supported` memoizes a one-shot `TCP_REPAIR` capability probe in a
//! process-global `OnceLock`. Surfaced through the `tcp` facade.

use std::sync::OnceLock;

static SEQOVL_SUPPORTED: OnceLock<bool> = OnceLock::new();

#[cfg(any(target_os = "linux", target_os = "android"))]
pub const fn supports_fake_retransmit() -> bool {
    true
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub const fn supports_fake_retransmit() -> bool {
    false
}

pub fn seqovl_supported() -> bool {
    *SEQOVL_SUPPORTED.get_or_init(|| {
        crate::ip_fragmentation::probe_ip_fragmentation_capabilities(None).is_ok_and(|caps| caps.tcp_repair)
    })
}
