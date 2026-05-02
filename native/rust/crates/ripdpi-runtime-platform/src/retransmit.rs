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
        crate::ip_fragmentation::probe_ip_fragmentation_capabilities(None).map(|caps| caps.tcp_repair).unwrap_or(false)
    })
}
